package com.mgafk.app.data.repository

import com.mgafk.app.data.AppLog
import com.mgafk.app.data.model.ShopSnapshot
import com.mgafk.app.data.model.WatchlistItem
import com.mgafk.app.data.websocket.GameActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Quản lý watchlist và tự động mua item khi restock.
 *
 * Port từ Python:
 *   _do_check_watchlist()  → checkAndBuy()
 *   _watchlist_bought_this_cycle  → boughtThisCycle
 *   _watchlist_prev_restock       → prevRestockTimers
 *
 * Cách dùng:
 *   1. Gọi onShopsUpdated() mỗi khi nhận ShopsChanged event
 *   2. Gọi setItems() khi user thêm/xóa watchlist
 *   3. Gọi reset() khi session disconnect/reconnect
 */
class WatchlistManager(
    private val sessionId: String,
    private val onBought: (shopType: String, itemId: String, quantity: Int) -> Unit,
    private val onLog: (message: String) -> Unit,
) {
    companion object {
        private const val TAG = "WatchlistManager"
        /** Delay giữa mỗi lần gửi purchase command (ms) — tránh flood server */
        private const val BUY_DELAY_MS = 150L
        /** Nếu timer tăng > threshold này, coi là đã restock → reset cycle */
        private const val RESTOCK_DETECT_THRESHOLD_SECS = 10
    }

    private val mutex = Mutex()

    // Danh sách items cần theo dõi
    @Volatile
    var items: List<WatchlistItem> = emptyList()
        private set

    // (shopType, itemId) → số lượng đã mua trong cycle hiện tại
    private val boughtThisCycle = mutableMapOf<Pair<String, String>, Int>()

    // shopType → secondsUntilRestock của lần update trước
    private val prevRestockTimers = mutableMapOf<String, Int>()

    fun setItems(newItems: List<WatchlistItem>) {
        items = newItems
        AppLog.d(TAG, "[$sessionId] Watchlist updated: ${newItems.size} items")
    }

    /**
     * Gọi mỗi khi nhận ShopsChanged event.
     * Detect restock → reset cycle → mua items có trong watchlist.
     *
     * @param shops  danh sách shop snapshot mới nhất
     * @param actions GameActions để gửi purchase command
     */
    suspend fun onShopsUpdated(shops: List<ShopSnapshot>, actions: GameActions) {
        if (items.isEmpty()) return

        mutex.withLock {
            detectAndResetCycles(shops)
            checkAndBuy(shops, actions)
        }
    }

    /**
     * Reset toàn bộ state — gọi khi session disconnect/reconnect
     */
    fun reset() {
        boughtThisCycle.clear()
        prevRestockTimers.clear()
        AppLog.d(TAG, "[$sessionId] Watchlist state reset")
    }

    // ─── Private ────────────────────────────────────────────────────────────

    /**
     * Phát hiện restock bằng cách so sánh secondsUntilRestock.
     * Nếu timer tăng lên đáng kể → shop vừa restock → xóa bought cache của shop đó.
     *
     * Port từ Python: vòng lặp detect restock trong _do_check_watchlist()
     */
    private fun detectAndResetCycles(shops: List<ShopSnapshot>) {
        for (shop in shops) {
            val prevSecs = prevRestockTimers[shop.type]
            val currSecs = shop.secondsUntilRestock

            if (prevSecs != null && currSecs > prevSecs + RESTOCK_DETECT_THRESHOLD_SECS) {
                // Timer tăng lên → shop vừa restock
                val cleared = boughtThisCycle.keys.count { it.first == shop.type }
                boughtThisCycle.keys.removeAll { it.first == shop.type }
                onLog("🔄 ${shop.type} shop restocked (${prevSecs}s → ${currSecs}s), cleared $cleared bought entries")
                AppLog.d(TAG, "[$sessionId] Restock detected for ${shop.type}: ${prevSecs}s → ${currSecs}s")
            }

            prevRestockTimers[shop.type] = currSecs
        }
    }

    /**
     * Kiểm tra từng item trong watchlist, mua nếu có stock và chưa mua cycle này.
     *
     * Port từ Python: vòng lặp for entry in watchlist trong _do_check_watchlist()
     */
    private suspend fun checkAndBuy(shops: List<ShopSnapshot>, actions: GameActions) {
        // Build map (shopType, itemId) → available stock
        val available = mutableMapOf<Pair<String, String>, Int>()
        for (shop in shops) {
            for ((itemId, stock) in shop.itemStocks) {
                if (stock > 0) {
                    available[shop.type to itemId] = stock
                }
            }
        }

        for (entry in items) {
            val key = entry.shopType to entry.itemId

            // Đã mua trong cycle này → bỏ qua
            if (boughtThisCycle.containsKey(key)) continue

            val stock = available[key] ?: 0
            if (stock <= 0) continue

            // Có stock → mua hết
            onLog("🛒 Watchlist hit: ${entry.shopType}/${entry.itemId} (stock=$stock), buying...")
            AppLog.d(TAG, "[$sessionId] Buying watchlist item: ${entry.shopType}/${entry.itemId} x$stock")

            val sent = buyAll(actions, entry.shopType, entry.itemId, stock)
            boughtThisCycle[key] = sent

            onBought(entry.shopType, entry.itemId, sent)
            onLog("✅ Bought ${entry.itemId} x$sent")
        }
    }

    /**
     * Gửi N purchase commands với delay giữa mỗi lần.
     * Returns số lệnh đã gửi.
     *
     * Port từ Python: buy_item() trong buy.py
     */
    private suspend fun buyAll(
        actions: GameActions,
        shopType: String,
        itemId: String,
        quantity: Int,
    ): Int {
        var sent = 0
        try {
            repeat(quantity) { i ->
                actions.purchaseShopItem(shopType, itemId)
                sent++
                if (i < quantity - 1) delay(BUY_DELAY_MS)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "[$sessionId] Error buying $itemId: ${e.message}")
        }
        return sent
    }
}

package com.mgafk.app.service

import android.content.Context
import com.mgafk.app.data.AppLog
import com.mgafk.app.data.model.SessionStatus
import com.mgafk.app.data.model.WatchlistItem
import com.mgafk.app.ui.MainViewModel
import com.mgafk.app.ui.UiState
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HTTP + WebSocket server tương thích với Python dashboard.
 * Dùng cùng protocol: snapshot + update events, nhận lệnh 2 chiều.
 *
 * Routes:
 *   GET  /          → index.html (Python dashboard)
 *   GET  /ws        → WebSocket (Python dashboard protocol)
 *   GET  /api/status → JSON đơn giản
 */
class RemoteControlServer(
    private val context: Context,
    private val viewModel: MainViewModel,
) {
    private val TAG = "RemoteControlServer"
    private val PORT = 8080

    private var server: ApplicationEngine? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsClients = CopyOnWriteArrayList<DefaultWebSocketSession>()

    fun start() {
        if (server != null) return
        AppLog.d(TAG, "Starting remote control server on port $PORT")

        server = embeddedServer(Netty, port = PORT, host = "0.0.0.0") {
            install(WebSockets) {
            }
            routing {
                // ── Dashboard HTML ──
                get("/") { call.respondText(DASHBOARD_HTML, ContentType.Text.Html) }

                // ── Simple status API ──
                get("/api/status") {
                    val state = viewModel.state.value
                    val session = state.activeSession
                    call.respondText(
                        buildJsonObject {
                            put("connected", session.status.name)
                            put("sessionName", session.name)
                            put("weather", session.weather)
                        }.toString(),
                        ContentType.Application.Json
                    )
                }

                // ── WebSocket — Python dashboard protocol ──
                webSocket("/ws") {
                    wsClients.add(this)
                    AppLog.d(TAG, "WS client connected (total=${wsClients.size})")
                    try {
                        // Gửi snapshot ngay khi kết nối
                        send(Frame.Text(buildSnapshot(viewModel.state.value)))

                        // Nhận lệnh từ browser
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                handleCommand(frame.readText())
                            }
                        }
                    } finally {
                        wsClients.remove(this)
                        AppLog.d(TAG, "WS client disconnected (total=${wsClients.size})")
                    }
                }
            }
        }.start(wait = false)

        // Broadcast state khi có thay đổi
        scope.launch {
            viewModel.state.collectLatest { state ->
                broadcastUpdate(state)
            }
        }

        AppLog.d(TAG, "Remote server started at http://${getLocalIp()}:$PORT")
    }

    fun stop() {
        server?.stop(500L, 1000L, java.util.concurrent.TimeUnit.MILLISECONDS)
        server = null
        scope.cancel()
        AppLog.d(TAG, "Remote control server stopped")
    }

    fun getLocalIp(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                val addrs = Collections.list(iface.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
            "unknown"
        } catch (e: Exception) { "unknown" }
    }

    // ── Command handler ──────────────────────────────────────────────────────

    private suspend fun handleCommand(raw: String) {
        val obj = runCatching {
            Json.parseToJsonElement(raw) as? JsonObject
        }.getOrNull() ?: return

        val cmd = (obj["cmd"] as? JsonPrimitive)?.content ?: return
        val sessionId = (obj["sessionId"] as? JsonPrimitive)?.content ?: ""

        AppLog.d(TAG, "WS cmd: $cmd sessionId=$sessionId")

        when (cmd) {
            "connectSession" -> {
                val targetId = sessionId.ifBlank {
                    viewModel.state.value.activeSessionId
                }
                viewModel.connect(targetId)
            }

            "disconnectSession" -> {
                val targetId = sessionId.ifBlank {
                    viewModel.state.value.activeSessionId
                }
                viewModel.disconnect(targetId)
            }

            "setWatchlist" -> {
                // items: [{shopType, itemId}, ...]
                val itemsArr = obj["items"]
                if (itemsArr != null) {
                    val newItems = mutableListOf<WatchlistItem>()
                    (itemsArr as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
                        val item = el as? JsonObject ?: return@forEach
                        val shopType = (item["shopType"] as? JsonPrimitive)?.content ?: return@forEach
                        val itemId = (item["itemId"] as? JsonPrimitive)?.content ?: return@forEach
                        newItems.add(WatchlistItem(shopType = shopType, itemId = itemId))
                    }
                    // Sync: xoá hết rồi thêm lại
                    val current = viewModel.state.value.watchlist.toList()
                    current.forEach { viewModel.removeWatchlistItem(it.shopType, it.itemId) }
                    newItems.forEach { viewModel.addWatchlistItem(it.shopType, it.itemId) }

                    // Broadcast watchlistUpdate
                    broadcastRaw(buildJsonObject {
                        put("type", "watchlistUpdate")
                        put("sessionId", sessionId)
                        putJsonArray("watchlist") {
                            newItems.forEach { w ->
                                add(buildJsonObject {
                                    put("shopType", w.shopType)
                                    put("itemId", w.itemId)
                                })
                            }
                        }
                    }.toString())
                }
            }

            "clearWatchlist" -> {
                val current = viewModel.state.value.watchlist.toList()
                current.forEach { viewModel.removeWatchlistItem(it.shopType, it.itemId) }
                broadcastRaw(buildJsonObject {
                    put("type", "watchlistUpdate")
                    put("sessionId", sessionId)
                    putJsonArray("watchlist") {}
                }.toString())
            }

            "buyNow" -> {
                val shopType = (obj["shopType"] as? JsonPrimitive)?.content ?: return
                val itemId = (obj["itemId"] as? JsonPrimitive)?.content ?: return
                val targetId = sessionId.ifBlank { viewModel.state.value.activeSessionId }
                // Dùng purchaseAllShopItem (mua hết stock)
                viewModel.purchaseAllShopItem(targetId, shopType, itemId)

                broadcastRaw(buildJsonObject {
                    put("type", "buyNowResult")
                    put("sessionId", targetId)
                    put("shopType", shopType)
                    put("itemId", itemId)
                    put("skipped", false)
                    put("sent", 1)
                }.toString())
            }

            "setAutoReconnect" -> {
                // Android không có autoReconnect toggle riêng — ignore hoặc map
            }

            "setNotifyThreshold" -> {
                // Không có trong Android — ignore
            }
        }
    }

    // ── Broadcast helpers ────────────────────────────────────────────────────

    private suspend fun broadcastRaw(json: String) {
        val dead = mutableListOf<DefaultWebSocketSession>()
        wsClients.forEach { ws ->
            runCatching { ws.send(Frame.Text(json)) }
                .onFailure { dead.add(ws) }
        }
        wsClients.removeAll(dead)
    }

    private var lastState: UiState? = null

    private suspend fun broadcastUpdate(state: UiState) {
        val prev = lastState
        lastState = state

        if (prev == null) {
            // Lần đầu — gửi snapshot
            broadcastRaw(buildSnapshot(state))
            return
        }

        // Gửi update cho từng session có thay đổi
        state.sessions.forEach { session ->
            val prevSession = prev.sessions.find { it.id == session.id }
            if (prevSession != session) {
                broadcastRaw(buildJsonObject {
                    put("type", "update")
                    put("sessionId", session.id)
                    putJsonObject("payload") {
                        put("state", session.status.toDashboardState())
                        put("weather", session.weather)
                        put("playerName", session.playerName)
                        put("playerCount", session.players)
                        put("connectedAt", if (session.connectedAt > 0) session.connectedAt / 1000.0 else null)
                        putJsonArray("pets") { session.pets.forEach { pet -> add(petToJson(pet)) } }
                        putJsonObject("shops") { session.shops.forEach { shop -> put(shop.type, shopToJson(shop)) } }
                        putJsonArray("watchlist") {
                            state.watchlist.forEach { w ->
                                add(buildJsonObject {
                                    put("shopType", w.shopType)
                                    put("itemId", w.itemId)
                                })
                            }
                        }
                        // restockTimes
                        putJsonObject("restockTimes") {
                            session.shops.forEach { shop ->
                                put(shop.type, shop.secondsUntilRestock)
                            }
                        }
                    }
                }.toString())
            }
        }
    }

    // ── Snapshot builder — Python dashboard format ───────────────────────────

    private fun buildSnapshot(state: UiState): String {
        return buildJsonObject {
            put("type", "snapshot")
            put("ts", System.currentTimeMillis() / 1000.0)
            put("tunnelUrl", "")
            putJsonArray("sessions") {
                state.sessions.forEach { session ->
                    add(buildJsonObject {
                        put("sessionId", session.id)
                        put("name", session.name)
                        put("state", session.status.toDashboardState())
                        put("room", session.room)
                        put("version", session.gameVersion)
                        put("playerName", session.playerName)
                        put("playerCount", session.players)
                        put("uptime", if (session.connectedAt > 0)
                            (System.currentTimeMillis() - session.connectedAt) / 1000.0 else 0.0)
                        put("connectedAt", if (session.connectedAt > 0) session.connectedAt / 1000.0 else null)
                        put("weather", session.weather)
                        put("autoReconnect", true)
                        put("notifyThreshold", 0.10)
                        put("lastAbility", "")

                        // Pets
                        putJsonArray("pets") {
                            session.pets.forEach { pet -> add(petToJson(pet)) }
                        }

                        // Shops — object keyed by type
                        putJsonObject("shops") {
                            session.shops.forEach { shop ->
                                put(shop.type, shopToJson(shop))
                            }
                        }

                        // restockAt — map type → epoch seconds
                        putJsonObject("restockAt") {
                            val now = System.currentTimeMillis() / 1000.0
                            session.shops.forEach { shop ->
                                if (shop.secondsUntilRestock > 0) {
                                    put(shop.type, now + shop.secondsUntilRestock)
                                }
                            }
                        }

                        // Watchlist
                        putJsonArray("watchlist") {
                            state.watchlist.forEach { w ->
                                add(buildJsonObject {
                                    put("shopType", w.shopType)
                                    put("itemId", w.itemId)
                                })
                            }
                        }

                        // Logs (ability logs)
                        putJsonArray("logs") {
                            session.logs.takeLast(50).forEach { log ->
                                add(buildJsonArray {
                                    add(JsonPrimitive(log.timestamp / 1000.0))
                                    add(JsonPrimitive("ABILITY"))
                                    add(JsonPrimitive(log.action))
                                })
                            }
                        }
                    })
                }
            }
        }.toString()
    }

    // ── Model converters ─────────────────────────────────────────────────────

    private fun petToJson(pet: com.mgafk.app.data.model.PetSnapshot): kotlinx.serialization.json.JsonElement {
        val maxHunger = mapOf(
            "worm" to 500, "snail" to 1000, "bee" to 1500, "chicken" to 3000,
            "bunny" to 750, "dragonfly" to 250, "pig" to 50000, "cow" to 25000,
            "turkey" to 500, "squirrel" to 15000, "turtle" to 100000, "goat" to 20000,
            "snowfox" to 14000, "stoat" to 10000, "whitecaribou" to 30000,
            "caribou" to 30000, "pony" to 4000, "horse" to 4000,
            "firehorse" to 200000, "butterfly" to 25000, "capybara" to 150000,
            "peacock" to 100000,
        )
        val max = maxHunger[pet.species.lowercase()] ?: 1000
        val hungerPct = if (max > 0) (pet.hunger / max).coerceIn(0.0, 1.0) else 0.0
        return buildJsonObject {
            put("name", pet.name)
            put("species", pet.species)
            put("hunger", hungerPct)
            put("xp", pet.xp)
            put("mutations", buildJsonArray { pet.mutations.forEach { add(JsonPrimitive(it)) } })
            put("abilities", buildJsonArray { pet.abilities.forEach { add(JsonPrimitive(it)) } })
            put("rarity", "")
            put("strength", JsonPrimitive(null as String?))
            put("maxStrength", JsonPrimitive(null as String?))
        }
    }

    private fun shopToJson(shop: com.mgafk.app.data.model.ShopSnapshot): kotlinx.serialization.json.JsonElement {
        return buildJsonObject {
            put("secondsUntilRestock", shop.secondsUntilRestock)
            putJsonArray("items") {
                shop.itemStocks.forEach { (itemId, stock) ->
                    add(buildJsonObject {
                        // Set đúng key theo loại shop
                        val key = when (shop.type) {
                            "seed" -> "species"
                            "tool" -> "toolId"
                            "egg" -> "eggId"
                            "decor" -> "decorId"
                            else -> "id"
                        }
                        put(key, itemId)
                        put("initialStock", stock)
                        put("stock", stock)
                    })
                }
            }
        }
    }

    private fun SessionStatus.toDashboardState(): String = when (this) {
        SessionStatus.IDLE -> "idle"
        SessionStatus.CONNECTING -> "connecting"
        SessionStatus.CONNECTED -> "connected"
        SessionStatus.ERROR -> "error"
    }

    // ── Dashboard HTML (Python index.html được nhúng vào) ────────────────────

    companion object {
        val DASHBOARD_HTML: String get() = INDEX_HTML
    }
}

// index.html từ Python project — đặt ở ngoài companion để tránh string quá dài trong class
// REPLACE_MARKER — nội dung sẽ được thay bằng index.html thực tế
private val INDEX_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>MG AFK Dashboard</title>
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap" />
  <style>
    :root {
      --bg: #070d14;
      --surface: #0d1b2a;
      --surface2: #112236;
      --border: #1e3553;
      --accent: #00c896;
      --accent2: #0ea5e9;
      --text: #e2f0ff;
      --text-dim: #617594;
      --red: #f87171;
      --yellow: #fbbf24;
      --purple: #a78bfa;
      --green: #4ade80;
      --orange: #fb923c;
      --card-radius: 14px;
      --font: 'Inter', sans-serif;
      --mono: 'JetBrains Mono', monospace;
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: var(--bg); color: var(--text); font-family: var(--font); min-height: 100vh; overflow-x: hidden; }

    body::before {
      content: '';
      position: fixed; top: -40%; left: 50%; transform: translateX(-50%);
      width: 800px; height: 600px;
      background: radial-gradient(ellipse at center, rgba(0,200,150,0.06) 0%, transparent 70%);
      pointer-events: none; z-index: 0;
    }

    /* ─── Header ─────────────────────────────────── */
    header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 18px 28px; border-bottom: 1px solid var(--border);
      background: rgba(13,27,42,0.9); backdrop-filter: blur(12px);
      position: sticky; top: 0; z-index: 100;
    }
    .logo { display: flex; align-items: center; gap: 10px; font-size: 20px; font-weight: 700; color: var(--accent); }
    .logo span { font-size: 26px; }
    .header-right { display: flex; align-items: center; gap: 16px; }
    #connection-dot {
      width: 8px; height: 8px; border-radius: 50%;
      background: var(--red); box-shadow: 0 0 10px var(--red); transition: all 0.3s;
    }
    #connection-dot.live { background: var(--green); box-shadow: 0 0 10px var(--green); }
    .clock { font-family: var(--mono); font-size: 13px; color: var(--text-dim); }

    /* ─── Main layout ────────────────────────────── */
    main { position: relative; z-index: 1; padding: 24px 28px; display: flex; flex-direction: column; gap: 24px; max-width: 1600px; margin: 0 auto; }

    /* ─── Sessions grid ──────────────────────────── */
    #sessions-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(380px, 1fr)); gap: 18px; }

    /* ─── Card ───────────────────────────────────── */
    .session-card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--card-radius); overflow: hidden; transition: transform 0.2s, box-shadow 0.2s; }
    .session-card:hover { transform: translateY(-2px); box-shadow: 0 12px 40px rgba(0,0,0,0.4); }
    .card-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; border-bottom: 1px solid var(--border); background: var(--surface2); }
    .card-title { font-size: 14px; font-weight: 600; color: var(--accent2); display: flex; align-items: center; gap: 8px; }
    .card-title .room-code { font-family: var(--mono); font-size: 11px; color: var(--text-dim); background: rgba(255,255,255,0.05); padding: 2px 7px; border-radius: 4px; }
    .status-badge { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 20px; letter-spacing: 0.05em; text-transform: uppercase; }
    .status-connected    { background: rgba(74,222,128,0.15);  color: var(--green); }
    .status-connecting   { background: rgba(251,191,36,0.15);   color: var(--yellow); animation: pulse 1.5s infinite; }
    .status-reconnecting { background: rgba(251,191,36,0.15);   color: var(--yellow); animation: pulse 1.5s infinite; }
    .status-disconnected { background: rgba(248,113,113,0.15);  color: var(--red); }
    .status-error        { background: rgba(248,113,113,0.2);   color: var(--red); }
    .status-idle         { background: rgba(255,255,255,0.05);  color: var(--text-dim); }
    @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.5} }

    .card-body { padding: 16px 18px; display: flex; flex-direction: column; gap: 14px; }

    /* Stats */
    .stat-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .stat { background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; }
    .stat-label { font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--text-dim); margin-bottom: 4px; }
    .stat-value { font-size: 15px; font-weight: 600; color: var(--text); font-family: var(--mono); }

    /* Section title */
    .section-title { font-size: 11px; text-transform: uppercase; letter-spacing: 0.1em; color: var(--text-dim); margin-bottom: 8px; }

    /* Pets */
    .pet-list { display: flex; flex-direction: column; gap: 8px; }
    .pet-row { display: flex; align-items: center; justify-content: space-between; padding: 8px 10px; background: rgba(255,255,255,0.03); border-radius: 8px; border: 1px solid var(--border); gap: 8px; }
    .pet-info { display: flex; flex-direction: column; gap: 2px; }
    .pet-name { font-size: 13px; font-weight: 600; }
    .pet-species { font-size: 11px; color: var(--text-dim); }
    .pet-mutations { font-size: 10px; color: var(--yellow); }
    .hunger-wrap { display: flex; align-items: center; gap: 8px; }
    .hunger-bar-bg { width: 80px; height: 7px; background: rgba(255,255,255,0.08); border-radius: 4px; overflow: hidden; }
    .hunger-bar-fill { height: 100%; border-radius: 4px; transition: width 0.5s ease; }
    .hunger-pct { font-family: var(--mono); font-size: 11px; color: var(--text-dim); width: 36px; text-align: right; }
    .no-pets { color: var(--text-dim); font-size: 13px; font-style: italic; }
    .hunger-high { background: linear-gradient(90deg,#4ade80,#22c55e); }
    .hunger-mid  { background: linear-gradient(90deg,#fbbf24,#f59e0b); }
    .hunger-low  { background: linear-gradient(90deg,#f87171,#ef4444); }

    /* Shops */
    .shops-wrap { display: flex; flex-direction: column; gap: 8px; }
    .shop-section { background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; }
    .shop-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
    .shop-type-label { font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; color: var(--accent2); font-weight: 600; }
    .shop-restock { font-size: 10px; color: var(--text-dim); font-family: var(--mono); }
    .shop-items-grid { display: flex; flex-wrap: wrap; gap: 5px; }
    .shop-item {
      display: flex; align-items: center; gap: 6px;
      background: rgba(0,200,150,0.08); border: 1px solid rgba(0,200,150,0.2);
      border-radius: 6px; padding: 4px 8px; cursor: pointer;
      transition: all 0.15s; position: relative;
    }
    .shop-item:hover { background: rgba(0,200,150,0.2); border-color: rgba(0,200,150,0.5); }
    .shop-item.in-watchlist { background: rgba(251,191,36,0.15); border-color: rgba(251,191,36,0.5); }
    .shop-item.in-watchlist:hover { background: rgba(251,191,36,0.25); }
    .shop-item-name { font-size: 11px; color: var(--accent); font-weight: 500; }
    .shop-item-stock { font-size: 10px; color: var(--text-dim); font-family: var(--mono); }
    .shop-item-watch-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--yellow); display: none; }
    .shop-item.in-watchlist .shop-item-watch-dot { display: block; }
    .shop-empty { font-size: 12px; color: var(--text-dim); font-style: italic; }

    /* Watchlist panel */
    .watchlist-panel { background: rgba(251,191,36,0.05); border: 1px solid rgba(251,191,36,0.2); border-radius: 10px; padding: 12px 14px; }
    .watchlist-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
    .watchlist-title { font-size: 12px; font-weight: 600; color: var(--yellow); display: flex; align-items: center; gap: 6px; }
    .watchlist-clear-btn { background: none; border: 1px solid rgba(248,113,113,0.3); color: var(--red); border-radius: 5px; padding: 2px 8px; font-size: 10px; cursor: pointer; transition: 0.2s; }
    .watchlist-clear-btn:hover { background: rgba(248,113,113,0.1); }
    .watchlist-items { display: flex; flex-wrap: wrap; gap: 6px; min-height: 28px; }
    .watchlist-tag { display: flex; align-items: center; gap: 6px; background: rgba(251,191,36,0.15); border: 1px solid rgba(251,191,36,0.4); border-radius: 20px; padding: 3px 10px; font-size: 11px; color: var(--yellow); }
    .watchlist-tag-remove { background: none; border: none; color: var(--text-dim); cursor: pointer; font-size: 13px; line-height: 1; padding: 0; margin-left: 2px; transition: color 0.15s; }
    .watchlist-tag-remove:hover { color: var(--red); }
    .watchlist-empty { font-size: 11px; color: var(--text-dim); font-style: italic; }
    .watchlist-hint { font-size: 10px; color: var(--text-dim); margin-top: 8px; }

    /* ─── Hunger Notify panel ────────────────────── */
    .notify-panel {
      background: rgba(167,139,250,0.05);
      border: 1px solid rgba(167,139,250,0.2);
      border-radius: 10px; padding: 10px 14px;
      display: flex; align-items: center; justify-content: space-between; gap: 10px;
    }
    .notify-left { display: flex; align-items: center; gap: 8px; }
    .notify-label { font-size: 12px; font-weight: 600; color: var(--purple); }
    .notify-sublabel { font-size: 10px; color: var(--text-dim); margin-top: 2px; }
    .notify-controls { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
    .notify-threshold-wrap {
      display: flex; align-items: center; gap: 4px;
      background: rgba(255,255,255,0.05); border: 1px solid rgba(167,139,250,0.3);
      border-radius: 7px; padding: 3px 8px;
      transition: border-color 0.2s;
    }
    .notify-threshold-wrap:focus-within { border-color: var(--purple); }
    .notify-threshold-input {
      width: 36px; background: none; border: none;
      color: var(--text); font-size: 13px; font-family: var(--mono);
      font-weight: 600; outline: none; text-align: center;
      -moz-appearance: textfield;
    }
    .notify-threshold-input::-webkit-outer-spin-button,
    .notify-threshold-input::-webkit-inner-spin-button { -webkit-appearance: none; }
    .notify-pct-sign { font-size: 11px; color: var(--text-dim); }
    .notify-toggle-wrap { display: flex; align-items: center; gap: 6px; }
    .notify-toggle-label { font-size: 11px; color: var(--text-dim); }
    /* reuse .toggle style từ modal */

    /* Ability */
    .ability-badge { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: rgba(167,139,250,0.1); border: 1px solid rgba(167,139,250,0.2); border-radius: 8px; }
    .ability-icon { font-size: 16px; }
    .ability-text { font-size: 12px; color: var(--purple); font-weight: 500; }

    /* Flash animations */
    @keyframes flashBuy { 0%{box-shadow:0 0 0 0 rgba(0,200,150,0.8)} 100%{box-shadow:0 0 0 16px rgba(0,200,150,0)} }
    .flash-buy { animation: flashBuy 0.6s ease-out; }
    @keyframes fadeIn { from{opacity:0;transform:translateY(4px)} to{opacity:1} }

    /* ─── Log panel ──────────────────────────────── */
    .log-panel { background: var(--surface); border: 1px solid var(--border); border-radius: var(--card-radius); overflow: hidden; }
    .log-header { display: flex; align-items: center; justify-content: space-between; padding: 12px 18px; border-bottom: 1px solid var(--border); background: var(--surface2); }
    .log-title { font-size: 13px; font-weight: 600; }
    .log-clear-btn { background: none; border: 1px solid var(--border); color: var(--text-dim); border-radius: 6px; padding: 3px 10px; font-size: 11px; cursor: pointer; transition: 0.2s; }
    .log-clear-btn:hover { border-color: var(--accent); color: var(--accent); }
    #log-container { height: 260px; overflow-y: auto; padding: 10px 0; scroll-behavior: smooth; font-family: var(--mono); font-size: 12px; }
    #log-container::-webkit-scrollbar { width: 4px; }
    #log-container::-webkit-scrollbar-track { background: var(--surface); }
    #log-container::-webkit-scrollbar-thumb { background: var(--border); border-radius: 4px; }
    .log-row { display: flex; align-items: flex-start; padding: 3px 18px; gap: 10px; animation: fadeIn 0.3s ease; }
    .log-row:hover { background: rgba(255,255,255,0.02); }
    .log-ts { color: var(--text-dim); width: 70px; flex-shrink: 0; }
    .log-session { color: var(--text-dim); width: 90px; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .log-level { width: 70px; flex-shrink: 0; font-weight: 600; }
    .log-msg { color: var(--text); flex: 1; }
    .level-INFO    { color: var(--accent2); }
    .level-ERROR   { color: var(--red); }
    .level-WARN    { color: var(--yellow); }
    .level-ABILITY { color: var(--purple); }
    .level-DEBUG   { color: var(--text-dim); }
    .level-BUY     { color: var(--green); }
    .level-NOTIFY  { color: var(--purple); }

    /* Empty state */
    #empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 200px; gap: 12px; color: var(--text-dim); }
    .empty-icon { font-size: 48px; opacity: 0.3; }

    /* Toast */
    #toast-container { position: fixed; bottom: 24px; right: 24px; z-index: 9999; display: flex; flex-direction: column; gap: 8px; }
    .toast { background: var(--surface2); border: 1px solid var(--border); border-radius: 10px; padding: 10px 16px; font-size: 13px; display: flex; align-items: center; gap: 10px; animation: fadeIn 0.3s ease; min-width: 220px; }
    .toast.buy  { border-color: rgba(0,200,150,0.5); }
    .toast.warn { border-color: rgba(251,191,36,0.5); }

    @media (max-width: 640px) {
      header { padding: 12px 16px; }
      main { padding: 16px; gap: 16px; }
      #sessions-grid { grid-template-columns: 1fr; }
    }

    /* ─── Connect Modal ──────────────────────── */
    #connect-modal {
      display: none; position: fixed; inset: 0; z-index: 9999;
      background: rgba(0,0,0,0.75); backdrop-filter: blur(6px);
      align-items: center; justify-content: center; padding: 20px;
    }
    #connect-modal.open { display: flex; }
    .connect-panel {
      background: var(--surface); border: 1px solid var(--border);
      border-radius: var(--card-radius); padding: 28px;
      width: 100%; max-width: 460px; position: relative;
    }
    .connect-panel h2 { font-size: 17px; font-weight: 700; color: var(--accent); margin-bottom: 6px; }
    .connect-panel > p { font-size: 12px; color: var(--text-dim); margin-bottom: 20px; }
    .modal-close-btn { position: absolute; top: 14px; right: 16px; background: none; border: none; color: var(--text-dim); font-size: 22px; cursor: pointer; line-height: 1; padding: 0; }
    .modal-close-btn:hover { color: var(--text); }
    .form-row { display: flex; flex-direction: column; gap: 5px; margin-bottom: 14px; }
    .form-label { font-size: 11px; text-transform: uppercase; letter-spacing: .08em; color: var(--text-dim); }
    .form-input { background: rgba(255,255,255,0.05); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; color: var(--text); font-size: 14px; font-family: var(--mono); outline: none; width: 100%; transition: border-color .2s; box-sizing: border-box; }
    .form-input:focus { border-color: var(--accent); }
    .form-row-inline { display: flex; align-items: center; margin-bottom: 18px; }
    .toggle-wrap { display: flex; align-items: center; gap: 10px; }
    .toggle-label { font-size: 12px; color: var(--text-dim); }
    .toggle { position: relative; width: 40px; height: 22px; cursor: pointer; flex-shrink: 0; }
    .toggle input { opacity: 0; width: 0; height: 0; }
    .toggle-slider { position: absolute; inset: 0; background: rgba(255,255,255,0.1); border-radius: 22px; transition: .3s; }
    .toggle-slider::before { content: ''; position: absolute; width: 16px; height: 16px; left: 3px; top: 3px; background: white; border-radius: 50%; transition: .3s; }
    .toggle input:checked + .toggle-slider { background: var(--accent); }
    .toggle input:checked + .toggle-slider::before { transform: translateX(18px); }
    .btn-connect { width: 100%; padding: 13px; border-radius: 10px; border: none; cursor: pointer; background: var(--accent); color: #000; font-weight: 700; font-size: 14px; transition: opacity .2s; }
    .btn-connect:hover { opacity: .85; }
    .btn-connect:disabled { opacity: .4; cursor: not-allowed; }

    /* ─── Session header actions ─────────────── */
    .session-actions { display: flex; align-items: center; gap: 6px; }
    .ar-label { font-size: 10px; color: var(--text-dim); }
    .btn-icon { background: none; border: 1px solid var(--border); color: var(--text-dim); border-radius: 6px; padding: 3px 9px; font-size: 13px; cursor: pointer; transition: .2s; }
    .btn-icon:hover { border-color: var(--red); color: var(--red); }
    .btn-icon.reconnect { border-color: rgba(0,200,150,.4); color: var(--accent); }
    .btn-icon.reconnect:hover { border-color: var(--accent); background: rgba(0,200,150,.1); }

    /* ─── Idle overlay inside card ───────────── */
    .idle-overlay { display: flex; flex-direction: column; align-items: center; gap: 14px; padding: 28px 18px; text-align: center; }
    .idle-overlay .idle-icon { font-size: 36px; opacity: .3; }
    .idle-overlay .idle-msg { font-size: 13px; color: var(--text-dim); }
    .idle-overlay .idle-btns { display: flex; gap: 10px; }
    .btn-reconnect { padding: 9px 20px; border-radius: 8px; border: none; cursor: pointer; background: var(--accent); color: #000; font-weight: 700; font-size: 13px; }
    .btn-reconnect:hover { opacity: .85; }
    .btn-delete { padding: 9px 16px; border-radius: 8px; border: 1px solid var(--border); background: none; color: var(--text-dim); font-size: 13px; cursor: pointer; }
    .btn-delete:hover { border-color: var(--red); color: var(--red); }
  </style>
</head>
<body>
  <header>
    <div class="logo"><span>🌱</span> MG AFK</div>
    <div class="header-right">
      <button onclick="openModal()" style="font-size:12px;background:rgba(0,200,150,0.12);color:var(--accent);border:1px solid var(--accent);border-radius:6px;padding:6px 14px;cursor:pointer;font-weight:600">+ Thêm session</button>
      <a id="tunnel-link" href="#" target="_blank" style="display:none;font-size:12px;color:var(--accent);text-decoration:none;padding:3px 10px;border:1px solid var(--accent);border-radius:6px;margin-right:8px">🌐 Remote</a>
      <a href="/analytics" target="_blank" style="font-size:12px;color:var(--accent2);text-decoration:none;padding:3px 10px;border:1px solid var(--accent2);border-radius:6px">📊 Analytics</a>
      <a href="/ability-timer" target="_blank" style="font-size:12px;color:var(--purple);text-decoration:none;padding:3px 10px;border:1px solid var(--purple);border-radius:6px">⚡ Ability Timer</a>
      <a href="/connection-history" target="_blank" style="font-size:12px;color:var(--accent2);text-decoration:none;padding:3px 10px;border:1px solid var(--accent2);border-radius:6px">📡 History</a>
      <div id="connection-dot" title="WebSocket connection"></div>
      <span class="clock" id="clock">00:00:00</span>
    </div>
  </header>

  <main>
    <!-- Connect Modal -->
    <div id="connect-modal" onclick="if(event.target===this)closeModal()">
      <div class="connect-panel">
        <button class="modal-close-btn" onclick="closeModal()">✕</button>
        <h2>🌱 Kết nối tài khoản</h2>
        <p>Dán token mc_jwt vào đây để bắt đầu bot</p>
        <div class="form-row">
          <label class="form-label">mc_jwt Token *</label>
          <input class="form-input" id="cp-cookie" type="password" placeholder="mc_jwt=eyJ..." autocomplete="off">
        </div>
        <div class="form-row">
          <label class="form-label">Tên hiển thị</label>
          <input class="form-input" id="cp-name" type="text" placeholder="Account 1" value="Account 1">
        </div>
        <div class="form-row">
          <label class="form-label">Room code (để trống = tự động)</label>
          <input class="form-input" id="cp-room" type="text" placeholder="auto">
        </div>
        <div class="form-row-inline">
          <div class="toggle-wrap">
            <label class="toggle">
              <input type="checkbox" id="cp-auto-reconnect" checked>
              <span class="toggle-slider"></span>
            </label>
            <span class="toggle-label">Tự động kết nối lại khi mất mạng</span>
          </div>
        </div>
        <button class="btn-connect" id="btn-connect" onclick="submitConnect()">🔌 Kết nối</button>
      </div>
    </div>

    <div id="sessions-grid">
      <div id="empty-state">
        <div class="empty-icon">🌱</div>
        <div>Đang kết nối dashboard...</div>
      </div>
    </div>

    <div class="log-panel">
      <div class="log-header">
        <span class="log-title">📋 Activity Log</span>
        <button class="log-clear-btn" onclick="clearLogs()">Clear</button>
      </div>
      <div id="log-container"></div>
    </div>
  </main>

  <div id="toast-container"></div>

  <script>

    // ── Sprite map ──────────────────────────────────
    const SPRITES = {"Carrot": "https://mg-api.ariedam.fr/assets/sprites/seeds/Carrot.png?v=90", "Cabbage": "https://mg-api.ariedam.fr/assets/sprites/seeds/Cabbage.png?v=90", "Strawberry": "https://mg-api.ariedam.fr/assets/sprites/seeds/Strawberry.png?v=90", "Aloe": "https://mg-api.ariedam.fr/assets/sprites/seeds/Aloe.png?v=90", "Beet": "https://mg-api.ariedam.fr/assets/sprites/seeds/Beet.png?v=90", "Rose": "https://mg-api.ariedam.fr/assets/sprites/seeds/Rose.png?v=90", "FavaBean": "https://mg-api.ariedam.fr/assets/sprites/seeds/FavaBean.png?v=90", "Delphinium": "https://mg-api.ariedam.fr/assets/sprites/seeds/Delphinium.png?v=90", "Blueberry": "https://mg-api.ariedam.fr/assets/sprites/seeds/Blueberry.png?v=90", "Apple": "https://mg-api.ariedam.fr/assets/sprites/seeds/Apple.png?v=90", "OrangeTulip": "https://mg-api.ariedam.fr/assets/sprites/seeds/Tulip.png?v=90", "Tomato": "https://mg-api.ariedam.fr/assets/sprites/seeds/Tomato.png?v=90", "Daffodil": "https://mg-api.ariedam.fr/assets/sprites/seeds/Daffodil.png?v=90", "Corn": "https://mg-api.ariedam.fr/assets/sprites/seeds/Corn.png?v=90", "Watermelon": "https://mg-api.ariedam.fr/assets/sprites/seeds/Watermelon.png?v=90", "Pumpkin": "https://mg-api.ariedam.fr/assets/sprites/seeds/Pumpkin.png?v=90", "Echeveria": "https://mg-api.ariedam.fr/assets/sprites/seeds/Echeveria.png?v=90", "Pear": "https://mg-api.ariedam.fr/assets/sprites/seeds/Pear.png?v=90", "Gentian": "https://mg-api.ariedam.fr/assets/sprites/seeds/Gentian.png?v=90", "Coconut": "https://mg-api.ariedam.fr/assets/sprites/seeds/Coconut.png?v=90", "PineTree": "https://mg-api.ariedam.fr/assets/sprites/seeds/Pinecone.png?v=90", "Banana": "https://mg-api.ariedam.fr/assets/sprites/seeds/Banana.png?v=90", "Lily": "https://mg-api.ariedam.fr/assets/sprites/seeds/Lily.png?v=90", "Camellia": "https://mg-api.ariedam.fr/assets/sprites/seeds/Camellia.png?v=90", "Squash": "https://mg-api.ariedam.fr/assets/sprites/seeds/Squash.png?v=90", "Peach": "https://mg-api.ariedam.fr/assets/sprites/seeds/Peach.png?v=90", "BurrosTail": "https://mg-api.ariedam.fr/assets/sprites/seeds/BurrosTail.png?v=90", "Mushroom": "https://mg-api.ariedam.fr/assets/sprites/seeds/Mushroom.png?v=90", "Cactus": "https://mg-api.ariedam.fr/assets/sprites/seeds/Cactus.png?v=90", "Bamboo": "https://mg-api.ariedam.fr/assets/sprites/seeds/Bamboo.png?v=90", "Poinsettia": "https://mg-api.ariedam.fr/assets/sprites/seeds/Poinsettia.png?v=90", "VioletCort": "https://mg-api.ariedam.fr/assets/sprites/seeds/VioletCort.png?v=90", "Chrysanthemum": "https://mg-api.ariedam.fr/assets/sprites/seeds/Chrysanthemum.png?v=90", "Date": "https://mg-api.ariedam.fr/assets/sprites/seeds/Date.png?v=90", "Grape": "https://mg-api.ariedam.fr/assets/sprites/seeds/Grape.png?v=90", "Pepper": "https://mg-api.ariedam.fr/assets/sprites/seeds/Pepper.png?v=90", "Lemon": "https://mg-api.ariedam.fr/assets/sprites/seeds/Lemon.png?v=90", "PassionFruit": "https://mg-api.ariedam.fr/assets/sprites/seeds/PassionFruit.png?v=90", "DragonFruit": "https://mg-api.ariedam.fr/assets/sprites/seeds/DragonFruit.png?v=90", "Cacao": "https://mg-api.ariedam.fr/assets/sprites/seeds/Cacao.png?v=90", "Lychee": "https://mg-api.ariedam.fr/assets/sprites/seeds/Lychee.png?v=90", "Sunflower": "https://mg-api.ariedam.fr/assets/sprites/seeds/Sunflower.png?v=90", "Starweaver": "https://mg-api.ariedam.fr/assets/sprites/seeds/Starweaver.png?v=90", "DawnCelestial": "https://mg-api.ariedam.fr/assets/sprites/seeds/DawnCelestial.png?v=90", "MoonCelestial": "https://mg-api.ariedam.fr/assets/sprites/seeds/MoonCelestial.png?v=90", "Worm": "https://mg-api.ariedam.fr/assets/sprites/pets/Worm.png?v=90", "Snail": "https://mg-api.ariedam.fr/assets/sprites/pets/Snail.png?v=90", "Bee": "https://mg-api.ariedam.fr/assets/sprites/pets/Bee.png?v=90", "Chicken": "https://mg-api.ariedam.fr/assets/sprites/pets/Chicken.png?v=90", "Bunny": "https://mg-api.ariedam.fr/assets/sprites/pets/Bunny.png?v=90", "Dragonfly": "https://mg-api.ariedam.fr/assets/sprites/pets/Dragonfly.png?v=90", "Pig": "https://mg-api.ariedam.fr/assets/sprites/pets/Pig.png?v=90", "Cow": "https://mg-api.ariedam.fr/assets/sprites/pets/Cow.png?v=90", "Turkey": "https://mg-api.ariedam.fr/assets/sprites/pets/Turkey.png?v=90", "Squirrel": "https://mg-api.ariedam.fr/assets/sprites/pets/Squirrel.png?v=90", "Turtle": "https://mg-api.ariedam.fr/assets/sprites/pets/Turtle.png?v=90", "Goat": "https://mg-api.ariedam.fr/assets/sprites/pets/Goat.png?v=90", "SnowFox": "https://mg-api.ariedam.fr/assets/sprites/pets/SnowFox.png?v=90", "Stoat": "https://mg-api.ariedam.fr/assets/sprites/pets/Stoat.png?v=90", "WhiteCaribou": "https://mg-api.ariedam.fr/assets/sprites/pets/WhiteCaribou.png?v=90", "Pony": "https://mg-api.ariedam.fr/assets/sprites/pets/Pony.png?v=90", "Horse": "https://mg-api.ariedam.fr/assets/sprites/pets/Horse.png?v=90", "FireHorse": "https://mg-api.ariedam.fr/assets/sprites/pets/FireHorse.png?v=90", "Butterfly": "https://mg-api.ariedam.fr/assets/sprites/pets/Butterfly.png?v=90", "Peacock": "https://mg-api.ariedam.fr/assets/sprites/pets/Peacock.png?v=90", "Capybara": "https://mg-api.ariedam.fr/assets/sprites/pets/Capybara.png?v=90", "CommonEgg": "https://mg-api.ariedam.fr/assets/sprites/pets/CommonEgg.png?v=90", "UncommonEgg": "https://mg-api.ariedam.fr/assets/sprites/pets/UncommonEgg.png?v=90", "RareEgg": "https://mg-api.ariedam.fr/assets/sprites/pets/RareEgg.png?v=90", "LegendaryEgg": "https://mg-api.ariedam.fr/assets/sprites/pets/LegendaryEgg.png?v=90", "SnowEgg": "https://mg-api.ariedam.fr/assets/sprites/pets/SnowEgg.png?v=90", "HorseEgg": "https://mg-api.ariedam.fr/assets/sprites/pets/HorseEgg.png?v=90", "MythicalEgg": "https://mg-api.ariedam.fr/assets/sprites/pets/MythicalEgg.png?v=90", "WinterEgg": "https://mg-api.ariedam.fr/assets/sprites/pets/WinterEgg.png?v=90", "WateringCan": "https://mg-api.ariedam.fr/assets/sprites/items/WateringCan.png?v=90", "PlanterPot": "https://mg-api.ariedam.fr/assets/sprites/items/PlanterPot.png?v=90", "CropCleanser": "https://mg-api.ariedam.fr/assets/sprites/items/CropCleanser.png?v=90", "WetPotion": "https://mg-api.ariedam.fr/assets/sprites/items/WetPotion.png?v=90", "ChilledPotion": "https://mg-api.ariedam.fr/assets/sprites/items/ChilledPotion.png?v=90", "DawnlitPotion": "https://mg-api.ariedam.fr/assets/sprites/items/DawnlitPotion.png?v=90", "Shovel": "https://mg-api.ariedam.fr/assets/sprites/items/Shovel.png?v=90", "FrozenPotion": "https://mg-api.ariedam.fr/assets/sprites/items/FrozenPotion.png?v=90", "AmberlitPotion": "https://mg-api.ariedam.fr/assets/sprites/items/AmberlitPotion.png?v=90", "GoldPotion": "https://mg-api.ariedam.fr/assets/sprites/items/GoldPotion.png?v=90", "RainbowPotion": "https://mg-api.ariedam.fr/assets/sprites/items/RainbowPotion.png?v=90", "Sunny": "/static/weather/Sunny.png", "Clear Skies": "/static/weather/Sunny.png", "ClearSkies": "/static/weather/Sunny.png", "clear_skies": "/static/weather/Sunny.png", "clearskies": "/static/weather/Sunny.png", "Rain": "/static/weather/Rain.png", "Frost": "/static/weather/Frost.png", "Snow": "/static/weather/Frost.png", "Thunderstorm": "/static/weather/Thunderstorm.png", "Dawn": "/static/weather/Dawn.png", "AmberMoon": "/static/weather/AmberMoon.png", "Amber Moon": "/static/weather/AmberMoon.png"};
    function sprite(id) { return SPRITES[id] || ''; }
    function weatherSprite(w) {
      if (!w) return '';
      return SPRITES[w] || SPRITES[w.replace(/\s+/g,'')] || SPRITES['Sunny'] || '';
    }

    // ── Ability display names ─────────────────────
    const ABILITY_NAMES = {"EggGrowthBoost":"Egg Growth Boost I","EggGrowthBoostII":"Egg Growth Boost II","EggGrowthBoostII_NEW":"Egg Growth Boost II","RainbowGranter":"Rainbow Granter","SnowGranter":"Snow Granter","FrostGranter":"Frost Granter","DawnlitGranter":"Dawnlit Granter","AmberlitGranter":"Amberlit Granter","CoinFinderI":"Coin Finder I","CoinFinderII":"Coin Finder II","CoinFinderIII":"Coin Finder III","SeedFinderI":"Seed Finder I","SeedFinderII":"Seed Finder II","SeedFinderIII":"Seed Finder III","SellBoostI":"Sell Boost I","SellBoostII":"Sell Boost II","SellBoostIII":"Sell Boost III","SellBoostIV":"Sell Boost IV","HungerRestore":"Hunger Restore I","HungerRestoreII":"Hunger Restore II","HungerBoost":"Hunger Boost I","HungerBoostII":"Hunger Boost II","PlantGrowthBoost":"Plant Growth I","PlantGrowthBoostII":"Plant Growth II","ProduceEater":"Produce Eater","ProduceScaleBoost":"Produce Scale I","ProduceScaleBoostII":"Produce Scale II","ProduceMutationBoost":"Produce Mutation I","ProduceMutationBoostII":"Produce Mutation II","ProduceRefund":"Produce Refund","PetMutationBoost":"Pet Mutation I","PetMutationBoostII":"Pet Mutation II","PetAgeBoost":"Pet Age Boost I","PetAgeBoostII":"Pet Age Boost II","PetHatchSizeBoost":"Hatch Size I","PetHatchSizeBoostII":"Hatch Size II","PetXpBoost":"Pet XP Boost I","PetXpBoostII":"Pet XP Boost II","PetRefund":"Pet Refund","PetRefundII":"Pet Refund II","DoubleHatch":"Double Hatch","DoubleHarvest":"Double Harvest","RainDance":"Rain Dance","SnowyCoinFinder":"Snowy Coin Finder","SnowyHungerBoost":"Snowy Hunger Boost","SnowyPetXpBoost":"Snowy Pet XP Boost","SnowyPlantGrowthBoost":"Snowy Plant Growth","SnowyCropMutationBoost":"Snowy Crop Mutation","SnowyCropSizeBoost":"Snowy Crop Size","DawnBoost":"Dawn Boost","DawnPlantGrowthBoost":"Dawn Plant Growth","AmberMoonBoost":"Amber Moon Boost","AmberPlantGrowthBoost":"Amber Plant Growth"};
    function abilityName(id) { return ABILITY_NAMES[id] || id; }

    // ── Clock ─────────────────────────────────────
    const clockEl = document.getElementById('clock');
    function updateClock() { clockEl.textContent = new Date().toLocaleTimeString('en-GB'); }
    setInterval(updateClock, 1000); updateClock();

    // ── State ─────────────────────────────────────
    const sessions   = {};   // sessionId → session data
    const watchlists = {};   // sessionId → [{shopType, itemId}]
    // sessionId → ngưỡng % (number, 1–99), khởi tạo từ snapshot
    const notifyThresholds = {};

    const logContainer = document.getElementById('log-container');
    let autoScroll = true;
    logContainer.addEventListener('scroll', () => {
      const { scrollTop, scrollHeight, clientHeight } = logContainer;
      autoScroll = scrollHeight - scrollTop - clientHeight < 40;
    });

    function clearLogs() { logContainer.innerHTML = ''; }

    // ── Utilities ─────────────────────────────────
    function esc(s) {
      return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }
    function fmt_uptime(seconds) {
      seconds = Math.floor(seconds);
      const h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60), s = seconds % 60;
      if (h) return `${'$'}{h}h ${'$'}{String(m).padStart(2,'0')}m ${'$'}{String(s).padStart(2,'0')}s`;
      if (m) return `${'$'}{m}m ${'$'}{String(s).padStart(2,'0')}s`;
      return `${'$'}{s}s`;
    }
    function hungerColor(pct) {
      if (pct > 0.6) return 'hunger-high';
      if (pct > 0.25) return 'hunger-mid';
      return 'hunger-low';
    }
    function showToast(msg, type = '', duration = 3000) {
      const el = document.createElement('div');
      el.className = `toast ${'$'}{type}`;
      el.textContent = msg;
      document.getElementById('toast-container').appendChild(el);
      setTimeout(() => el.remove(), duration);
    }

    // ── Notify threshold helpers ──────────────────
    function getThreshold(sessionId) {
      return notifyThresholds[sessionId] ?? 10;
    }

    // Gửi lệnh cập nhật ngưỡng xuống server
    function sendNotifyThreshold(sessionId, pct) {
      sendCmd('setNotifyThreshold', sessionId, { threshold: pct / 100 });
    }

    // Được gọi khi user thay đổi input
    function onThresholdChange(sessionId, inputEl) {
      let pct = parseInt(inputEl.value);
      if (isNaN(pct) || pct < 1)  pct = 1;
      if (pct > 99) pct = 99;
      inputEl.value = pct;
      notifyThresholds[sessionId] = pct;
      sendNotifyThreshold(sessionId, pct);
      showToast(`🔔 Ngưỡng thông báo: ${'$'}{pct}%`, '', 2000);
    }

    // ── Watchlist helpers ─────────────────────────
    function isInWatchlist(sessionId, shopType, itemId) {
      return (watchlists[sessionId] || []).some(w => w.shopType === shopType && w.itemId === itemId);
    }
    function toggleWatchlist(sessionId, shopType, itemId) {
      const list = watchlists[sessionId] || [];
      const idx = list.findIndex(w => w.shopType === shopType && w.itemId === itemId);
      if (idx >= 0) { list.splice(idx, 1); showToast(`🗑 Đã bỏ theo dõi: ${'$'}{itemId}`, 'warn'); }
      else { list.push({ shopType, itemId }); showToast(`👁 Theo dõi: ${'$'}{itemId} — sẽ mua khi xuất hiện`, 'buy'); }
      watchlists[sessionId] = list;
      sendWatchlist(sessionId);
      const card = document.getElementById(`card-${'$'}{sessionId}`);
      if (card) refreshCard(sessionId);
    }
    function removeFromWatchlist(sessionId, shopType, itemId) {
      watchlists[sessionId] = (watchlists[sessionId] || []).filter(
        w => !(w.shopType === shopType && w.itemId === itemId)
      );
      sendWatchlist(sessionId);
      const card = document.getElementById(`card-${'$'}{sessionId}`);
      if (card) refreshCard(sessionId);
    }
    function sendWatchlist(sessionId) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ cmd: 'setWatchlist', sessionId, items: watchlists[sessionId] || [] }));
      }
    }
    function sendBuyNow(sessionId, shopType, itemId, quantity) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ cmd: 'buyNow', sessionId, shopType, itemId, quantity }));
      }
    }

    // ── Card rendering ────────────────────────────
    function buildSessionCard(sd) {
      const card = document.createElement('div');
      card.className = 'session-card';
      card.id = `card-${'$'}{sd.sessionId}`;
      card.innerHTML = renderCard(sd);
      attachCardEvents(card, sd.sessionId);
      return card;
    }
    function refreshCard(sessionId) {
      const sd = sessions[sessionId];
      if (!sd) return;
      const card = document.getElementById(`card-${'$'}{sessionId}`);
      if (!card) return;
      card.innerHTML = renderCard(sd);
      attachCardEvents(card, sessionId);
    }
    function attachCardEvents(card, sessionId) {
      // Shop items
      card.querySelectorAll('.shop-item').forEach(el => {
        const shopType = el.dataset.shopType;
        const itemId   = el.dataset.itemId;
        const stock    = parseInt(el.dataset.stock || '0');
        el.addEventListener('click', () => toggleWatchlist(sessionId, shopType, itemId));
        el.addEventListener('contextmenu', (e) => {
          e.preventDefault();
          if (stock > 0) {
            sendBuyNow(sessionId, shopType, itemId, stock);
            el.classList.add('flash-buy');
            setTimeout(() => el.classList.remove('flash-buy'), 700);
            showToast(`🛒 Đang mua ${'$'}{stock}x ${'$'}{itemId}...`, 'buy');
          }
        });
      });
      // Watchlist tag remove
      card.querySelectorAll('.watchlist-tag-remove').forEach(btn => {
        btn.addEventListener('click', () =>
          removeFromWatchlist(sessionId, btn.dataset.shopType, btn.dataset.itemId)
        );
      });
      // Watchlist clear
      const clearBtn = card.querySelector('.watchlist-clear-btn');
      if (clearBtn) {
        clearBtn.addEventListener('click', () => {
          watchlists[sessionId] = [];
          sendWatchlist(sessionId);
          refreshCard(sessionId);
        });
      }
      // Notify threshold input — dùng event listener thay vì inline onchange
      // để tránh re-render cướp focus khi user đang gõ
      const threshInput = card.querySelector('.notify-threshold-input');
      if (threshInput) {
        threshInput.addEventListener('change', () => onThresholdChange(sessionId, threshInput));
        threshInput.addEventListener('keydown', (e) => {
          if (e.key === 'Enter') { threshInput.blur(); }
        });
        // Chặn card hover transform khi focus input
        threshInput.addEventListener('focus', () => {
          card.style.transform = 'none';
        });
        threshInput.addEventListener('blur', () => {
          card.style.transform = '';
        });
      }
    }

    function renderCard(sd) {
      const statusClass = `status-${'$'}{sd.state}`;
      const uptime = sd.connectedAt ? fmt_uptime(Date.now()/1000 - sd.connectedAt) : '—';
      const wl = watchlists[sd.sessionId] || [];
      const threshPct = getThreshold(sd.sessionId);

      // ── Pets ──
      let petsHtml = '';
      if (sd.pets && sd.pets.length) {
        petsHtml = `<div class="section-title">🐾 Pets</div><div class="pet-list">`;
        for (const pet of sd.pets) {
          const h = pet.hunger != null ? pet.hunger : null;
          const petPct = h !== null ? Math.max(0, Math.min(1, h)) : null;
          const isHungry = petPct !== null && petPct * 100 < threshPct;
          const barHtml = petPct !== null
            ? `<div class="hunger-wrap">
                <div class="hunger-bar-bg"><div class="hunger-bar-fill ${'$'}{hungerColor(petPct)}" style="width:${'$'}{(petPct*100).toFixed(0)}%"></div></div>
                <span class="hunger-pct" style="${'$'}{isHungry ? 'color:var(--red);font-weight:700' : ''}">${'$'}{(petPct*100).toFixed(0)}%${'$'}{isHungry ? ' 🔔' : ''}</span>
               </div>`
            : `<span class="hunger-pct" style="color:var(--text-dim)">—</span>`;
          const mutations = (pet.mutations || []).filter(Boolean);
          const mutHtml = mutations.length ? `<span class="pet-mutations">✨ ${'$'}{mutations.join(', ')}</span>` : '';
          const abilities = pet.abilities || [];
          const possible  = pet.possibleAbilities || [];
          let abilityHtml2 = '';
          if (abilities.length) {
            const tags = abilities.map(a => `<span style="display:inline-block;background:rgba(14,165,233,0.15);color:var(--accent2);border-radius:4px;padding:2px 7px;margin:1px;font-size:10px;font-weight:500">${'$'}{esc(abilityName(a))}</span>`).join('');
            abilityHtml2 = `<div style="margin-top:3px">⚡ ${'$'}{tags}</div>`;
          } else if (possible.length) {
            abilityHtml2 = `<div style="margin-top:2px;font-size:10px;color:var(--text-dim);font-style:italic">⚡ ${'$'}{esc(possible.join(' / '))}</div>`;
          }
          // ── Rarity / Strength / XP ───────────────────────────────
          const petXp          = pet.xp          || 0;
          const petStrength    = pet.strength;        // null nếu server không gửi
          const petMaxStrength = pet.maxStrength;
          const petRarity      = pet.rarity || '';

          const RARITY_COLOR = {
            Common:'#9ca3af', Uncommon:'#4ade80', Rare:'#60a5fa',
            Legendary:'#f59e0b', Mythic:'#c084fc', Divine:'#f472b6', Celestial:'#fde68a'
          };

          let strengthHtml = '';
          {
            const rarityColor = RARITY_COLOR[petRarity] || 'var(--text-dim)';

            // Ưu tiên hiển thị Strength nếu server gửi
            if (petStrength != null) {
              const strPct = petMaxStrength ? Math.min(100, (petStrength / petMaxStrength * 100)).toFixed(0) : 0;
              const strColor = strPct >= 80 ? '#a78bfa' : strPct >= 50 ? '#60a5fa' : '#94a3b8';
              strengthHtml = `<div style="margin-top:5px;display:flex;flex-direction:column;gap:4px">
                <div style="display:flex;align-items:center;gap:5px;flex-wrap:wrap">
                  ${'$'}{petRarity ? `<span style="font-size:10px;background:rgba(255,255,255,0.07);color:${'$'}{rarityColor};border-radius:4px;padding:1px 6px;font-weight:600">${'$'}{petRarity}</span>` : ''}
                  <span style="font-size:11px;font-weight:600;color:var(--text);font-family:var(--mono)">💪 ${'$'}{petStrength}${'$'}{petMaxStrength ? ' / ' + petMaxStrength : ''}</span>
                  ${'$'}{petXp > 0 ? `<span style="font-size:10px;color:var(--text-dim)">XP: ${'$'}{petXp.toLocaleString()}</span>` : ''}
                </div>
                ${'$'}{petMaxStrength ? `<div title="Strength: ${'$'}{petStrength}/${'$'}{petMaxStrength} (${'$'}{strPct}%)" style="width:100%;height:5px;background:rgba(255,255,255,0.08);border-radius:3px;overflow:hidden">
                  <div style="width:${'$'}{strPct}%;height:100%;border-radius:3px;background:${'$'}{strColor};transition:width 0.5s"></div>
                </div>` : ''}
              </div>`;
            } else {
              // Fallback: hiển thị XP bar (ước tính)
              const maxHunger = pet.maxHunger || 1000;
              const xpEstMax  = maxHunger * 38.88;
              const xpPct     = petXp > 0 ? Math.min(100, (petXp / xpEstMax * 100)).toFixed(0) : 0;
              strengthHtml = `<div style="margin-top:5px;display:flex;flex-direction:column;gap:4px">
                <div style="display:flex;align-items:center;gap:5px;flex-wrap:wrap">
                  ${'$'}{petRarity ? `<span style="font-size:10px;background:rgba(255,255,255,0.07);color:${'$'}{rarityColor};border-radius:4px;padding:1px 6px;font-weight:600">${'$'}{petRarity}</span>` : ''}
                  ${'$'}{petXp > 0 ? `<span style="font-size:10px;color:var(--text-dim)">✨ XP: ${'$'}{petXp.toLocaleString()}</span>` : ''}
                </div>
                ${'$'}{petXp > 0 ? `<div title="XP ${'$'}{petXp.toLocaleString()}" style="width:100%;height:4px;background:rgba(255,255,255,0.08);border-radius:2px;overflow:hidden">
                  <div style="width:${'$'}{xpPct}%;height:100%;border-radius:2px;background:linear-gradient(90deg,#a78bfa,#60a5fa);transition:width 0.5s"></div>
                </div>` : ''}
              </div>`;
            }
          }

          const petSpriteUrl = sprite(pet.species);
          const petAvatar = petSpriteUrl
            ? `<div style="width:48px;height:48px;background:rgba(255,255,255,0.04);border-radius:10px;display:flex;align-items:center;justify-content:center;flex-shrink:0"><img src="${'$'}{petSpriteUrl}" style="width:42px;height:42px;object-fit:contain;image-rendering:pixelated"></div>`
            : `<div style="width:48px;height:48px;background:rgba(255,255,255,0.05);border-radius:10px;flex-shrink:0"></div>`;
          petsHtml += `<div class="pet-row" style="${'$'}{isHungry ? 'border-color:rgba(248,113,113,0.4);background:rgba(248,113,113,0.05)' : ''}">${'$'}{petAvatar}<div class="pet-info" style="flex:1;min-width:0"><span class="pet-name">${'$'}{esc(pet.name||'?')}</span><span class="pet-species">${'$'}{esc(pet.species||'?')}</span>${'$'}{abilityHtml2}${'$'}{mutHtml}${'$'}{strengthHtml}</div>${'$'}{barHtml}</div>`;
        }
        petsHtml += '</div>';
      } else {
        petsHtml = '<p class="no-pets">No pets found</p>';
      }

      // ── Shops ──
      let shopsHtml = '';
      const shopData = sd.shops || {};
      const shopTypes = Object.entries(shopData);
      if (shopTypes.length) {
        shopsHtml = `<div class="section-title">🏪 Shops <span style="font-size:10px;color:var(--text-dim);font-weight:400">(click=theo dõi · right-click=mua ngay)</span></div><div class="shops-wrap">`;
        for (const [type, shop] of shopTypes) {
          const items = (shop.items || []).slice(0, 20);
          const restockAt = (sd.restockAt || {})[type];
          const remaining = restockAt ? restockAt - Date.now() / 1000 : null;
          const restockStr = remaining != null
            ? (remaining > 0 ? `restock ${'$'}{fmtSeconds(remaining)}` : 'restocking...')
            : '';
          let itemsHtml = '';
          if (items.length) {
            for (const item of items) {
              const id      = item.species || item.toolId || item.eggId || item.decorId || '?';
              const stock   = item.initialStock || item.stock || 0;
              const watched = isInWatchlist(sd.sessionId, type, id);
              const itemSprite = sprite(id);
              const itemImg = itemSprite
                ? `<img src="${'$'}{itemSprite}" style="width:26px;height:26px;object-fit:contain;image-rendering:pixelated;flex-shrink:0">`
                : '';
              itemsHtml += `<div class="shop-item ${'$'}{watched ? 'in-watchlist' : ''}"
                data-shop-type="${'$'}{esc(type)}" data-item-id="${'$'}{esc(id)}" data-stock="${'$'}{stock}"
                title="${'$'}{watched ? '🟡 Đang theo dõi — click để bỏ' : 'Click = theo dõi | Right-click = mua ngay'}">
                <div class="shop-item-watch-dot"></div>
                ${'$'}{itemImg}
                <span class="shop-item-name">${'$'}{esc(id)}</span>
                <span class="shop-item-stock">×${'$'}{stock}</span>
              </div>`;
            }
          } else {
            itemsHtml = '<span class="shop-empty">Trống</span>';
          }
          shopsHtml += `<div class="shop-section">
            <div class="shop-header">
              <span class="shop-type-label">${'$'}{esc(type)}</span>
              ${'$'}{restockStr ? `<span class="shop-restock" data-shop-type="${'$'}{esc(type)}">⏱ ${'$'}{esc(restockStr)}</span>` : ''}
            </div>
            <div class="shop-items-grid">${'$'}{itemsHtml}</div>
          </div>`;
        }
        shopsHtml += '</div>';
      }

      // ── Watchlist ──
      let watchlistHtml = '';
      {
        const tags = wl.map(w =>
          `<div class="watchlist-tag">
            <span>${'$'}{esc(w.shopType)}/${'$'}{esc(w.itemId)}</span>
            <button class="watchlist-tag-remove" data-shop-type="${'$'}{esc(w.shopType)}" data-item-id="${'$'}{esc(w.itemId)}" title="Bỏ theo dõi">×</button>
          </div>`
        ).join('');
        watchlistHtml = `<div class="watchlist-panel">
          <div class="watchlist-header">
            <div class="watchlist-title">👁 Watchlist ${'$'}{wl.length ? `<span style="background:rgba(251,191,36,0.2);border-radius:10px;padding:1px 7px;font-size:10px">${'$'}{wl.length}</span>` : ''}</div>
            ${'$'}{wl.length ? `<button class="watchlist-clear-btn">Xóa tất cả</button>` : ''}
          </div>
          <div class="watchlist-items">
            ${'$'}{tags || '<span class="watchlist-empty">Chưa có item nào — click vào item trong shop để theo dõi</span>'}
          </div>
          ${'$'}{wl.length ? '<div class="watchlist-hint">Bot sẽ tự động mua khi item xuất hiện trong shop</div>' : ''}
        </div>`;
      }

      // ── Hunger Notify panel ──
      const notifyHtml = `
        <div class="notify-panel">
          <div class="notify-left">
            <span style="font-size:18px">🔔</span>
            <div>
              <div class="notify-label">Thông báo đói</div>
              <div class="notify-sublabel">Termux notification khi pet dưới ngưỡng</div>
            </div>
          </div>
          <div class="notify-controls">
            <span style="font-size:11px;color:var(--text-dim)">Ngưỡng</span>
            <div class="notify-threshold-wrap">
              <input
                class="notify-threshold-input"
                type="number" min="1" max="99" step="1"
                value="${'$'}{threshPct}"
                title="Gửi thông báo khi hunger dưới mức này">
              <span class="notify-pct-sign">%</span>
            </div>
          </div>
        </div>`;

      // ── Items link + Ability ──
      const itemsLinkHtml = `<a href="/items" style="font-size:11px;color:var(--accent2);text-decoration:none;display:inline-flex;align-items:center;gap:4px;margin-top:4px;opacity:0.8" target="_blank">📦 Xem tất cả vật phẩm →</a>`;
      let abilityHtml = '';
      if (sd.lastAbility) {
        abilityHtml = `<div class="ability-badge"><span class="ability-icon">⚡</span><span class="ability-text">${'$'}{esc(sd.lastAbility)}</span></div>`;
      }

      // ── Idle overlay ──
      const idleHtml = sd.state === 'idle' ? `
        <div class="idle-overlay">
          <div class="idle-icon">🔌</div>
          <div class="idle-msg">Session chưa kết nối</div>
          <div class="idle-btns">
            <button class="btn-reconnect" onclick="reconnectSession('${'$'}{esc(sd.sessionId)}','${'$'}{esc(sd.name)}')">↻ Kết nối lại</button>
            <button class="btn-delete" onclick="deleteSession('${'$'}{esc(sd.sessionId)}')">🗑 Xóa</button>
          </div>
        </div>` : '';

      return `
        <div class="card-header">
          <div class="card-title">
            ${'$'}{esc(sd.name)}
            ${'$'}{sd.room ? `<span class="room-code">${'$'}{esc(sd.room)}</span>` : ''}
          </div>
          <div class="session-actions">
            <label class="toggle" title="Tự động kết nối lại" style="width:32px;height:18px">
              <input type="checkbox" ${'$'}{sd.autoReconnect !== false ? 'checked' : ''}
                onchange="setAutoReconnect('${'$'}{esc(sd.sessionId)}', this.checked)">
              <span class="toggle-slider"></span>
            </label>
            <span class="ar-toggle-label">Auto</span>
            <span class="status-badge ${'$'}{statusClass}">${'$'}{esc(sd.state)}</span>
            ${'$'}{sd.state !== 'idle' ? `<button class="btn-disconnect" onclick="disconnectSession('${'$'}{esc(sd.sessionId)}')">✕</button>` : ''}
          </div>
        </div>
        <div class="card-body">
          ${'$'}{idleHtml}
          <div style="${'$'}{sd.state==='idle'?'display:none':''}">
            <div class="stat-grid">
              <div class="stat"><div class="stat-label">Player</div><div class="stat-value" style="font-size:13px">${'$'}{esc(sd.playerName||'—')}</div></div>
              <div class="stat"><div class="stat-label">Online</div><div class="stat-value">${'$'}{sd.playerCount??'—'}</div></div>
              <div class="stat"><div class="stat-label">Uptime</div><div class="stat-value" style="font-size:12px">${'$'}{sd.state==='connected'?uptime:'—'}</div></div>
              <div class="stat"><div class="stat-label">Weather</div><div class="stat-value" style="font-size:12px;display:flex;align-items:center;gap:5px">${'$'}{(()=>{const ws=weatherSprite(sd.weather);return ws?`<img src="${'$'}{ws}" style="width:22px;height:22px;object-fit:contain">`:''})()}${'$'}{esc(sd.weather||'—')}</div></div>
            </div>
            ${'$'}{petsHtml}
            ${'$'}{notifyHtml}
            ${'$'}{shopsHtml}
            ${'$'}{watchlistHtml}
            ${'$'}{itemsLinkHtml}
            ${'$'}{abilityHtml}
          </div>
        </div>`;
    }

    function fmtSeconds(s) {
      s = Math.max(0, Math.floor(s));
      const m = Math.floor(s / 60), sec = s % 60;
      if (m > 0) return `${'$'}{m}m ${'$'}{String(sec).padStart(2,'0')}s`;
      return `${'$'}{sec}s`;
    }

    function upsertCard(sd) {
      const grid  = document.getElementById('sessions-grid');
      const empty = document.getElementById('empty-state');
      if (empty) empty.remove();
      const existing = document.getElementById(`card-${'$'}{sd.sessionId}`);
      if (existing) {
        // Giữ lại focus nếu user đang gõ vào threshold input
        const focused = existing.querySelector('.notify-threshold-input') === document.activeElement;
        if (focused) return; // không re-render khi input đang focus
        existing.innerHTML = renderCard(sd);
        attachCardEvents(existing, sd.sessionId);
      } else {
        grid.appendChild(buildSessionCard(sd));
      }
    }

    function addLog(sessionName, level, msg) {
      const ts  = new Date().toLocaleTimeString('en-GB');
      const row = document.createElement('div');
      row.className = 'log-row';
      const levelClass = `level-${'$'}{level.toUpperCase()}`;
      row.innerHTML = `<span class="log-ts">${'$'}{ts}</span><span class="log-session">${'$'}{esc(sessionName)}</span><span class="log-level ${'$'}{levelClass}">[${'$'}{esc(level)}]</span><span class="log-msg">${'$'}{esc(msg)}</span>`;
      logContainer.appendChild(row);
      while (logContainer.children.length > 300) logContainer.removeChild(logContainer.firstChild);
      if (autoScroll) logContainer.scrollTop = logContainer.scrollHeight;
    }

    // ── WebSocket ─────────────────────────────────
    const dot = document.getElementById('connection-dot');
    let ws, reconnectTimer;

    function connect() {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      ws = new WebSocket(`${'$'}{proto}://${'$'}{location.host}/ws`);
      ws.onopen  = () => { dot.classList.add('live'); clearTimeout(reconnectTimer); };
      ws.onclose = () => { dot.classList.remove('live'); reconnectTimer = setTimeout(connect, 3000); };
      ws.onerror = () => { dot.classList.remove('live'); };
      ws.onmessage = (evt) => {
        let data; try { data = JSON.parse(evt.data); } catch { return; }
        handleMessage(data);
      };
    }

    function handleMessage(data) {
      if (data.type === 'snapshot') {
        console.log('[snapshot] sessions:', data.sessions?.length);
        if (data.tunnelUrl) {
          const el = document.getElementById('tunnel-link');
          el.href = data.tunnelUrl; el.style.display = 'inline-block'; el.title = data.tunnelUrl;
        }
        if (data.sessions && data.sessions.length > 0) {
          const empty = document.getElementById('empty-state');
          if (empty) empty.remove();
        }
        for (const sd of data.sessions) {
          if (sd.autoReconnect === undefined) sd.autoReconnect = true;
          // Lấy threshold từ snapshot (server trả về notifyThreshold, đơn vị 0.0–1.0)
          if (sd.notifyThreshold != null) {
            notifyThresholds[sd.sessionId] = Math.round(sd.notifyThreshold * 100);
          }
          sessions[sd.sessionId] = sd;
          if (sd.watchlist) watchlists[sd.sessionId] = sd.watchlist;
          upsertCard(sd);
          if (Array.isArray(sd.logs)) {
            for (const [ts, level, msg] of sd.logs) addLog(sd.name, level, msg);
          }
        }
        maybeShowConnectPanel();
        return;
      }

      if (data.type === 'update') {
        const sd = sessions[data.sessionId];
        if (!sd) return;
        if (data.payload.restockTimes) {
          if (!sd.restockAt) sd.restockAt = {};
          const now = Date.now() / 1000;
          for (const [shopType, secs] of Object.entries(data.payload.restockTimes)) {
            if (secs != null) sd.restockAt[shopType] = now + secs;
          }
        }
        // Sync threshold nếu server broadcast thay đổi
        if (data.payload.notifyThreshold != null) {
          notifyThresholds[data.sessionId] = Math.round(data.payload.notifyThreshold * 100);
        }
        Object.assign(sd, data.payload);
        upsertCard(sd);
        return;
      }

      if (data.type === 'notifyThresholdUpdate') {
        // Server xác nhận đã lưu ngưỡng mới
        notifyThresholds[data.sessionId] = Math.round(data.threshold * 100);
        const sd = sessions[data.sessionId];
        const name = sd ? sd.name : data.sessionId;
        addLog(name, 'NOTIFY', `🔔 Ngưỡng thông báo: ${'$'}{Math.round(data.threshold * 100)}%`);
        return;
      }

      if (data.type === 'log') {
        const sd = sessions[data.sessionId];
        addLog(sd ? sd.name : data.sessionId, data.level, data.message);
        return;
      }

      if (data.type === 'abilityLog') {
        const sd = sessions[data.sessionId];
        // Hiển thị dạng "AbilityName (PetName, Species)" trong card
        const ability = data.action || data.detail || '';
        const pet     = [data.petName, data.petSpecies].filter(Boolean).join(', ');
        const display = pet ? `${'$'}{ability} (${'$'}{pet})` : ability;
        if (sd) { sd.lastAbility = display; upsertCard(sd); }
        addLog(sd ? sd.name : data.sessionId, 'ABILITY', display);
        return;
      }

      if (data.type === 'watchlistUpdate') {
        watchlists[data.sessionId] = data.watchlist || [];
        const sd = sessions[data.sessionId];
        if (sd) upsertCard(sd);
        return;
      }

      if (data.type === 'watchlistBought') {
        const sd   = sessions[data.sessionId];
        const name = sd ? sd.name : data.sessionId;
        if (!data.skipped) {
          addLog(name, 'BUY', `✅ Watchlist: mua ${'$'}{data.quantity}x ${'$'}{data.itemId} (${'$'}{data.shopType})`);
          showToast(`✅ Đã mua ${'$'}{data.quantity}x ${'$'}{data.itemId}`, 'buy', 4000);
          const card = document.getElementById(`card-${'$'}{data.sessionId}`);
          if (card) {
            const el = card.querySelector(`[data-item-id="${'$'}{data.itemId}"]`);
            if (el) { el.classList.add('flash-buy'); setTimeout(() => el.classList.remove('flash-buy'), 700); }
          }
        } else {
          addLog(name, 'WARN', `⚠ Watchlist skip: ${'$'}{data.itemId} — ${'$'}{data.skipReason}`);
        }
        return;
      }

      if (data.type === 'buyNowResult') {
        const sd   = sessions[data.sessionId];
        const name = sd ? sd.name : data.sessionId;
        if (!data.skipped) {
          addLog(name, 'BUY', `🛒 Mua ngay: ${'$'}{data.sent}x ${'$'}{data.itemId} (${'$'}{data.shopType})`);
          showToast(`✅ Đã mua ${'$'}{data.sent}x ${'$'}{data.itemId}`, 'buy');
        } else {
          addLog(name, 'WARN', `Mua ngay thất bại: ${'$'}{data.itemId} — ${'$'}{data.skipReason}`);
          showToast(`⚠ Thất bại: ${'$'}{data.skipReason}`, 'warn');
        }
        return;
      }

      if (data.type === 'sessionIdle') {
        const sd = sessions[data.sessionId];
        if (sd) { sd.state = 'idle'; upsertCard(sd); }
        maybeShowConnectPanel();
        if (data.message) showToast(data.message, 'warn');
        return;
      }

      if (data.type === 'connectError') {
        showToast(data.message || 'Lỗi kết nối', 'error');
        const btn = document.getElementById('btn-connect');
        if (btn) { btn.disabled = false; btn.textContent = '🔌 Kết nối'; }
        return;
      }

      if (data.type === 'update' && data.payload?.state === 'connected') {
        const sd = sessions[data.sessionId];
        if (!sd) return;
        Object.assign(sd, data.payload);
        if (sd.autoReconnect === undefined) sd.autoReconnect = true;
        upsertCard(sd);
        closeModal();
        const btn = document.getElementById('btn-connect');
        if (btn) { btn.disabled = false; btn.textContent = '🔌 Kết nối'; }
        return;
      }
    }

    // ── Tickers ───────────────────────────────────
    setInterval(() => {
      // Uptime
      for (const sd of Object.values(sessions)) {
        if (sd.state === 'connected' && sd.connectedAt) {
          const card = document.getElementById(`card-${'$'}{sd.sessionId}`);
          if (!card) continue;
          const els = card.querySelectorAll('.stat-value');
          if (els[2]) els[2].textContent = fmt_uptime(Date.now()/1000 - sd.connectedAt);
        }
      }
      // Restock countdown
      const now = Date.now() / 1000;
      for (const sd of Object.values(sessions)) {
        if (!sd.restockAt) continue;
        const card = document.getElementById(`card-${'$'}{sd.sessionId}`);
        if (!card) continue;
        card.querySelectorAll('.shop-restock').forEach(el => {
          const shopType = el.dataset.shopType;
          if (!shopType) return;
          const restockAt = sd.restockAt[shopType];
          if (restockAt == null) return;
          const remaining = restockAt - now;
          el.textContent = remaining > 0 ? `⏱ restock ${'$'}{fmtSeconds(remaining)}` : '⏱ restocking...';
        });
      }
    }, 1000);

    // ── Modal helpers ─────────────────────────────
    const _modal = () => document.getElementById('connect-modal');

    function openModal(reuseSessionId, reuseSessionName) {
      const m = _modal(); if (!m) return;
      m._reuseId = reuseSessionId || '';
      if (reuseSessionName) {
        document.getElementById('cp-name').value = reuseSessionName;
      } else {
        const count = Object.keys(sessions).length + 1;
        const el = document.getElementById('cp-name');
        if (el && /^Account \d+${'$'}/.test(el.value)) el.value = 'Account ' + count;
      }
      m.classList.add('open');
      setTimeout(() => document.getElementById('cp-cookie')?.focus(), 80);
    }

    function closeModal() {
      const m = _modal(); if (m) m.classList.remove('open');
    }

    function maybeShowConnectPanel() {
      const hasActive = Object.values(sessions).some(s => s.state !== 'idle');
      if (!hasActive) openModal();
    }

    function submitConnect() {
      const cookie = document.getElementById('cp-cookie').value.trim();
      const name   = document.getElementById('cp-name').value.trim() || 'Account 1';
      const room   = document.getElementById('cp-room').value.trim() || 'auto';
      const autoRC = document.getElementById('cp-auto-reconnect').checked;
      if (!cookie) { showToast('Vui lòng nhập token mc_jwt', 'error'); return; }
      const btn = document.getElementById('btn-connect');
      btn.disabled = true; btn.textContent = '⏳ Đang kết nối...';
      const m = _modal();
      const reuseId = m ? (m._reuseId || '') : '';
      if (m) m._reuseId = '';
      sendCmd('connectSession', reuseId, { cookie, name, room, autoReconnect: autoRC });
      setTimeout(() => { btn.disabled = false; btn.textContent = '🔌 Kết nối'; }, 5000);
      setTimeout(() => { document.getElementById('cp-cookie').value = ''; closeModal(); }, 800);
    }

    function reconnectSession(sessionId, name) { openModal(sessionId, name); }

    function deleteSession(sessionId) {
      delete sessions[sessionId];
      const card = document.getElementById('card-' + sessionId);
      if (card) card.remove();
      if (Object.keys(sessions).length === 0) maybeShowConnectPanel();
    }

    function disconnectSession(sessionId) {
      if (!confirm('Ngắt kết nối session này?')) return;
      sendCmd('disconnectSession', sessionId, {});
    }

    function setAutoReconnect(sessionId, enabled) {
      sendCmd('setAutoReconnect', sessionId, { enabled });
      const sd = sessions[sessionId];
      if (sd) { sd.autoReconnect = enabled; }
    }

    function sendCmd(cmd, sessionId, data) {
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        showToast('Mất kết nối dashboard', 'error'); return;
      }
      ws.send(JSON.stringify({ cmd, sessionId, ...data }));
    }

    connect();
  </script>
</body>
</html>

""".trimIndent()

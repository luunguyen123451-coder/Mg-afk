package com.mgafk.app.data.model

import kotlinx.serialization.Serializable

/**
 * Một item trong watchlist — khi restock sẽ tự động mua hết stock.
 *
 * Port từ Python: _do_check_watchlist() trong main.py
 */
@Serializable
data class WatchlistItem(
    val shopType: String,   // "seed" | "tool" | "egg" | "thunder"
    val itemId: String,     // e.g. "Carrot", "WateringCan", "CatEgg", "ThunderEgg"
)

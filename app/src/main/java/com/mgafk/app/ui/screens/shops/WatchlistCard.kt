package com.mgafk.app.ui.screens.shops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mgafk.app.data.model.ShopSnapshot
import com.mgafk.app.data.model.WatchlistItem
import com.mgafk.app.data.repository.MgApi
import com.mgafk.app.ui.components.AppCard
import com.mgafk.app.ui.components.SpriteImage
import com.mgafk.app.ui.theme.Accent
import com.mgafk.app.ui.theme.AccentDim
import com.mgafk.app.ui.theme.SurfaceBorder
import com.mgafk.app.ui.theme.SurfaceDark
import com.mgafk.app.ui.theme.TextMuted
import com.mgafk.app.ui.theme.TextPrimary

private val WATCHLIST_SHOP_TYPES = listOf("seed", "tool", "egg", "dawn", "snow", "thunder")

/**
 * Card hiển thị danh sách Watchlist items.
 * User có thể thêm item từ shop hiện tại hoặc xoá từng item.
 *
 * @param watchlist  Danh sách item đang theo dõi (từ UiState)
 * @param shops      Danh sách shop hiện tại để chọn item thêm vào
 * @param onAdd      Callback khi user thêm item
 * @param onRemove   Callback khi user xoá item
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchlistCard(
    watchlist: List<WatchlistItem>,
    shops: List<ShopSnapshot>,
    onAdd: (shopType: String, itemId: String) -> Unit,
    onRemove: (shopType: String, itemId: String) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    AppCard(
        title = "Watchlist",
        persistKey = "watchlist",
        trailing = {
            // Add button
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AccentDim)
                    .clickable { showAddDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add watchlist item",
                    tint = TextPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
    ) {
        if (watchlist.isEmpty()) {
            Text(
                text = "No items — tap + to add.\nApp will auto-buy when shop restocks.",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                watchlist.forEach { item ->
                    WatchlistChip(
                        item = item,
                        onRemove = { onRemove(item.shopType, item.itemId) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Auto-buy all stock on restock",
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (showAddDialog) {
        AddWatchlistDialog(
            shops = shops,
            existingWatchlist = watchlist,
            onAdd = { shopType, itemId ->
                onAdd(shopType, itemId)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun WatchlistChip(
    item: WatchlistItem,
    onRemove: () -> Unit,
) {
    val displayName = MgApi.itemDisplayName(item.itemId)
    val spriteCategory = when (item.shopType) {
        "seed" -> "plants"
        "tool" -> "items"
        "egg" -> "eggs"
        "dawn" -> "plants"
        "snow" -> "plants"
        else -> "items"
    }
    val spriteUrl = MgApi.findItem(item.itemId)?.sprite?.let {
        MgApi.spriteUrl(spriteCategory, it.removeSuffix(".png"))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .border(1.dp, SurfaceBorder, RoundedCornerShape(20.dp))
            .background(SurfaceDark, RoundedCornerShape(20.dp))
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        if (spriteUrl != null) {
            SpriteImage(
                url = spriteUrl,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = displayName,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = TextMuted,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun AddWatchlistDialog(
    shops: List<ShopSnapshot>,
    existingWatchlist: List<WatchlistItem>,
    onAdd: (shopType: String, itemId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val existing = existingWatchlist.map { it.shopType to it.itemId }.toSet()

    // Lấy toàn bộ items từ MgApi, chia theo nhóm
    val groupedItems = remember(existingWatchlist, shops) {
        val dawnShopItems = shops.find { it.type == "dawn" }?.let { dawnShop ->
            dawnShop.itemNames.mapNotNull { itemId ->
                MgApi.findItem(itemId)?.takeIf { ("dawn" to itemId) !in existing }
            }.sortedBy { it.name }
        } ?: emptyList()

        val snowShopItems = shops.find { it.type == "snow" }?.let { snowShop ->
            snowShop.itemNames.mapNotNull { itemId ->
                MgApi.findItem(itemId)?.takeIf { ("snow" to itemId) !in existing }
            }.sortedBy { it.name }
        } ?: emptyList()

        val thunderShopItems = shops.find { it.type == "thunder" }?.let { thunderShop ->
            thunderShop.itemNames.mapNotNull { itemId ->
                MgApi.findItem(itemId)?.takeIf { ("thunder" to itemId) !in existing }
            }.sortedBy { it.name }
        } ?: emptyList()

        mapOf(
            "seed" to MgApi.getPlants().values
                .filter { (it.id to "seed") !in existing.map { e -> e.second to e.first } }
                .let { _ ->
                    MgApi.getPlants().values
                        .filter { entry -> ("seed" to entry.id) !in existing }
                        .sortedBy { it.name }
                },
            "tool" to MgApi.getItems().values
                .filter { ("tool" to it.id) !in existing }
                .sortedBy { it.name },
            "egg" to MgApi.getEggs().values
                .filter { ("egg" to it.id) !in existing }
                .sortedBy { it.name },
            "dawn" to dawnShopItems,
            "snow" to snowShopItems,
            "thunder" to thunderShopItems,
        )
    }

    val tabLabels = listOf("Seed", "Tool", "Egg", "Dawn", "Snow", "Thunder")
    val tabTypes = listOf("seed", "tool", "egg", "dawn", "snow", "thunder")
    var selectedTab by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = {
            focusManager.clearFocus()
            onDismiss()
        },
        title = { Text("Add to Watchlist", color = TextPrimary) },
        containerColor = SurfaceDark,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Chọn item để tự động mua khi shop restock.",
                    color = TextMuted,
                    fontSize = 13.sp,
                )

                // Tab bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SurfaceBorder, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    tabLabels.forEachIndexed { index, label ->
                        val selected = selectedTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (selected) AccentDim else SurfaceDark)
                                .clickable {
                                    selectedTab = index
                                    searchQuery = ""
                                    focusManager.clearFocus()
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = if (selected) TextPrimary else TextMuted,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                // Search box
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search…", color = TextMuted, fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentDim,
                        unfocusedBorderColor = SurfaceBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )

                // Item list
                val shopType = tabTypes[selectedTab]
                val items = groupedItems[shopType]
                    ?.filter { searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true) }
                    ?: emptyList()

                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (items.isEmpty()) {
                        item {
                            Text(
                                "Không có item nào",
                                color = TextMuted,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else {
                        items(items.size) { index ->
                            val entry = items[index]
                            val spriteCategory = when (shopType) {
                                "seed" -> "plants"
                                "tool" -> "items"
                                "egg" -> "eggs"
                                "dawn" -> "plants"
                                "snow" -> "plants"
                                else -> "items"
                            }
                            val spriteUrl = entry.sprite?.let {
                                MgApi.spriteUrl(spriteCategory, it.removeSuffix(".png"))
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        focusManager.clearFocus()
                                        onAdd(shopType, entry.id)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                if (spriteUrl != null) {
                                    SpriteImage(
                                        url = spriteUrl,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = entry.name,
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = {
                focusManager.clearFocus()
                onDismiss()
            }) {
                Text("Đóng", color = TextMuted)
            }
        },
    )
}

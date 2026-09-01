package com.mgafk.app.ui.screens.garden

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mgafk.app.data.model.BLPCounter
import com.mgafk.app.data.model.InventoryPetItem
import com.mgafk.app.data.repository.MgApi
import com.mgafk.app.ui.components.AppCard
import com.mgafk.app.ui.components.SpriteImage
import com.mgafk.app.ui.theme.Accent
import com.mgafk.app.ui.theme.SurfaceDark
import com.mgafk.app.ui.theme.TextMuted
import com.mgafk.app.ui.theme.TextPrimary
import kotlin.math.roundToInt

private val RAINBOW_COLOR = Color(0xFFFF6BCB)
private val GOLD_COLOR    = Color(0xFFFFD700)
private val SPECIES_COLOR = Color(0xFF60A5FA)
private val GUARANTEED_COLOR = Color(0xFF4ADE80)

@Composable
fun BadLuckCard(
    blpCounters: Map<String, BLPCounter>,
    lastHatchedPet: InventoryPetItem? = null,
    onReset: (eggId: String) -> Unit = {},
    onIncrement: (eggId: String, speciesId: String, isRainbow: Boolean) -> Unit = {},
) {
    // Chỉ hiển thị các egg có BLP guarantees
    val trackedEggs = remember {
        BLPCounter.EGG_SPECIES_GUARANTEES.keys
            .mapNotNull { eggId -> MgApi.findItem(eggId)?.let { eggId to it } }
            .sortedBy { it.second.name }
    }
    if (trackedEggs.isEmpty()) return

    AppCard(
        title = "Chống Vận Rủi (BLP)",
        collapsible = true,
        persistKey = "garden.blp",
    ) {
        Text(
            "Đếm số lần hatch liên tiếp chưa ra kết quả hiếm. Auto reset khi hatch được.",
            fontSize = 11.sp, color = TextMuted, lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )

        // Legend
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            LegendDot(RAINBOW_COLOR, "🌈 Cầu Vồng (lượt 2000)")
            LegendDot(GOLD_COLOR, "✨ Vàng (lượt 200)")
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            LegendDot(SPECIES_COLOR, "🐾 Thú hiếm per egg")
        }

        trackedEggs.forEach { (eggId, eggEntry) ->
            val counter = blpCounters[eggId] ?: BLPCounter()
            val speciesGuarantees = BLPCounter.EGG_SPECIES_GUARANTEES[eggId] ?: emptyList()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = if (counter.rainbowGuaranteed || counter.goldGuaranteed) 1.5.dp else 1.dp,
                        color = if (counter.rainbowGuaranteed || counter.goldGuaranteed)
                            GUARANTEED_COLOR else Color(0xFF2A3342),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .background(SurfaceDark)
                    .padding(10.dp)
            ) {
                // Header: egg icon + name + reset
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpriteImage(category = "eggs", name = eggId, size = 26.dp, contentDescription = eggEntry.name)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        eggEntry.name,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = TextPrimary, modifier = Modifier.weight(1f),
                    )
                    if (counter.totalHatches > 0) {
                        Text(
                            "${counter.totalHatches} hatches",
                            fontSize = 10.sp, color = TextMuted,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(
                        "Reset",
                        fontSize = 10.sp, color = Accent.copy(alpha = 0.6f),
                        modifier = Modifier.clickable { onReset(eggId) },
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 🌈 Rainbow bar
                BlpProgressBar(
                    label = "🌈 Cầu Vồng",
                    misses = counter.rainbowMisses,
                    threshold = BLPCounter.RAINBOW_THRESHOLD,
                    color = RAINBOW_COLOR,
                )
                Spacer(Modifier.height(4.dp))

                // ✨ Gold bar
                BlpProgressBar(
                    label = "✨ Vàng",
                    misses = counter.goldMisses,
                    threshold = BLPCounter.GOLD_THRESHOLD,
                    color = GOLD_COLOR,
                )

                // 🐾 Species bars
                speciesGuarantees.forEach { (speciesId, odds, threshold) ->
                    val speciesMisses = counter.speciesMisses[speciesId] ?: 0
                    val petEntry = MgApi.findPet(speciesId)
                    val speciesName = petEntry?.name ?: speciesId
                    Spacer(Modifier.height(4.dp))
                    BlpProgressBar(
                        label = "🐾 $speciesName (${(odds * 100).toInt()}%)",
                        misses = speciesMisses,
                        threshold = threshold,
                        color = SPECIES_COLOR,
                        petIcon = speciesId,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Manual controls
                Text("Nhập thủ công:", fontSize = 10.sp, color = TextMuted)
                Spacer(Modifier.height(4.dp))

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SmallChip("Miss +1", Color(0xFF374151)) {
                        onIncrement(eggId, "", false)
                    }
                    SmallChip("🌈 Cầu Vồng!", Color(0xFF6B21A8)) {
                        onIncrement(eggId, "", true)
                    }
                    speciesGuarantees.forEach { (speciesId, _, _) ->
                        val name = MgApi.findPet(speciesId)?.name ?: speciesId
                        SmallChip("🐾 $name!", Color(0xFF1E40AF)) {
                            onIncrement(eggId, speciesId, false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlpProgressBar(
    label: String,
    misses: Int,
    threshold: Int,
    color: Color,
    petIcon: String? = null,
) {
    val pct = (misses * 100f / threshold).coerceAtMost(100f)
    val isGuaranteed = misses >= threshold
    val remaining = (threshold - misses).coerceAtLeast(0)

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (petIcon != null) {
            SpriteImage(category = "pets", name = petIcon, size = 16.dp, contentDescription = null)
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, fontSize = 10.sp, color = TextMuted)
                if (isGuaranteed) {
                    Text(
                        "✅ ĐẢM BẢO!",
                        fontSize = 10.sp, color = GUARANTEED_COLOR,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Text(
                        "$misses/$threshold  (còn $remaining)",
                        fontSize = 10.sp,
                        color = if (pct >= 80f) color else TextMuted,
                        fontWeight = if (pct >= 80f) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF1E293B))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pct / 100f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            when {
                                isGuaranteed -> GUARANTEED_COLOR
                                pct >= 80f   -> color
                                pct >= 50f   -> color.copy(alpha = 0.7f)
                                else         -> color.copy(alpha = 0.4f)
                            }
                        )
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Text(label, fontSize = 9.sp, color = TextMuted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmallChip(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

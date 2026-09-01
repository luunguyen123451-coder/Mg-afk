package com.mgafk.app.data.model

import kotlinx.serialization.Serializable

/**
 * Local Bad Luck Protection counter cho mỗi loại trứng.
 * Server không expose counter — đếm locally.
 *
 * 3 loại counter độc lập:
 *   1. Rainbow pet (0.1% → guarantee lượt 2000)
 *   2. Gold pet    (1%   → guarantee lượt 200)
 *   3. Species hiếm per egg (5% → lượt 40, Phoenix 2% → lượt 100)
 *
 * Counter reset về 0 khi ra kết quả đó (dù do may mắn hay đảm bảo).
 */
@Serializable
data class BLPCounter(
    val rainbowMisses: Int = 0,
    val goldMisses: Int = 0,
    // Species counter — keyed by speciesId (vì Amber Egg có nhiều species hiếm)
    val speciesMisses: Map<String, Int> = emptyMap(),
    val totalHatches: Int = 0,
) {
    companion object {
        const val RAINBOW_THRESHOLD = 2000   // 0.1% × 2000 = 2×
        const val GOLD_THRESHOLD    = 200    // 1%   × 200  = 2×

        // Species guarantee thresholds per egg
        // Format: eggId -> list of (speciesId, odds%, threshold)
        val EGG_SPECIES_GUARANTEES: Map<String, List<Triple<String, Float, Int>>> = mapOf(
            "CommonEgg"    to listOf(Triple("Bee",         0.05f, 40)),
            "UncommonEgg"  to listOf(Triple("Dragonfly",   0.05f, 40)),
            "RareEgg"      to listOf(Triple("Turkey",      0.05f, 40)),
            "LegendaryEgg" to listOf(Triple("Goat",        0.05f, 40)),
            "SnowEgg"      to listOf(Triple("Caribou",     0.05f, 40)),
            "WinterEgg"    to listOf(Triple("Caribou",     0.05f, 40)),
            "DawnEgg"      to listOf(Triple("Ostrich",     0.05f, 40)),
            "ThunderEgg"   to listOf(Triple("ThunderWolf", 0.05f, 40)),
            "AmberEgg"     to listOf(
                Triple("Phoenix",   0.02f, 100),  // Phoenix 2% → lượt 100
                Triple("FireHorse", 0.05f, 40),   // FireHorse 5% → lượt 40
            ),
            "MythicalEgg"  to listOf(Triple("Capybara",   0.05f, 40)),
        )
    }

    val rainbowPct: Float get() = (rainbowMisses * 100f / RAINBOW_THRESHOLD).coerceAtMost(100f)
    val goldPct: Float    get() = (goldMisses * 100f / GOLD_THRESHOLD).coerceAtMost(100f)

    fun speciesPct(speciesId: String, threshold: Int): Float =
        ((speciesMisses[speciesId] ?: 0) * 100f / threshold).coerceAtMost(100f)

    fun speciesLeft(speciesId: String, threshold: Int): Int =
        (threshold - (speciesMisses[speciesId] ?: 0)).coerceAtLeast(0)

    val rainbowGuaranteed: Boolean get() = rainbowMisses >= RAINBOW_THRESHOLD
    val goldGuaranteed: Boolean    get() = goldMisses >= GOLD_THRESHOLD

    /**
     * Gọi khi hatch 1 pet.
     * @param speciesId  species của pet vừa ra (e.g. "Bee", "Ostrich")
     * @param isRainbow  pet có mutation Rainbow
     * @param isGold     pet có mutation Gold/Golden
     */
    fun onHatch(speciesId: String, isRainbow: Boolean, isGold: Boolean): BLPCounter {
        val newSpeciesMisses = speciesMisses.toMutableMap()
        val allTrackedSpecies = EGG_SPECIES_GUARANTEES.values.flatten().map { it.first }.distinct()

        if (speciesId == "__miss__") {
            // Miss thuần — tăng tất cả species counters
            allTrackedSpecies.forEach { sid ->
                newSpeciesMisses[sid] = (newSpeciesMisses[sid] ?: 0) + 1
            }
        } else {
            // Hatch ra speciesId cụ thể — reset counter của nó, tăng counter các loài khác
            allTrackedSpecies.forEach { sid ->
                newSpeciesMisses[sid] = if (sid == speciesId) 0
                                        else (newSpeciesMisses[sid] ?: 0) + 1
            }
        }

        return copy(
            rainbowMisses = if (isRainbow) 0 else rainbowMisses + 1,
            goldMisses    = if (isGold || isRainbow) 0 else goldMisses + 1,
            speciesMisses = newSpeciesMisses,
            totalHatches  = totalHatches + 1,
        )
    }

    fun resetAll() = BLPCounter(totalHatches = totalHatches)
}

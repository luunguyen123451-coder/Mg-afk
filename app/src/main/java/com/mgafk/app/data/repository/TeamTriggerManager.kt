package com.mgafk.app.data.repository

import com.mgafk.app.data.AppLog
import com.mgafk.app.data.model.GardenPlantSnapshot
import com.mgafk.app.data.model.PetTeam
import com.mgafk.app.data.model.TeamTrigger
import com.mgafk.app.data.model.TeamTrigger.TriggerType

/**
 * Đánh giá triggers và tìm team phù hợp nhất.
 *
 * Thứ tự ưu tiên: trigger có priority cao hơn → được check trước.
 * Trong cùng priority: WEATHER > GARDEN_GROWTH > GARDEN_MUTATION > HUNGER > DEFAULT.
 */
object TeamTriggerManager {

    private const val TAG = "TeamTriggerManager"

    data class EvalContext(
        val currentWeather: String,
        val activeTeamId: String?,
        /** petId → hunger% (0.0–100.0) của các pet đang active */
        val petHungerMap: Map<String, Double>,
        /** Tất cả cây trong vườn */
        val garden: List<GardenPlantSnapshot>,
    )

    /**
     * Tìm team cần switch sang, hoặc null nếu không cần đổi.
     */
    fun evaluate(
        teams: List<PetTeam>,
        ctx: EvalContext,
    ): PetTeam? {
        val triggered = teams.filter { it.triggers.isNotEmpty() }
        if (triggered.isEmpty()) return null

        data class Candidate(val team: PetTeam, val score: Int)
        val candidates = mutableListOf<Candidate>()

        for (team in triggered) {
            for (trigger in team.triggers.sortedByDescending { it.priority }) {
                val matches = checkTrigger(trigger, ctx, team)
                if (matches) {
                    // score = priority * 10 + type base score
                    val typeBase = when (trigger.type) {
                        TriggerType.WEATHER         -> 5
                        TriggerType.GARDEN_GROWTH   -> 4
                        TriggerType.GARDEN_MUTATION -> 3
                        TriggerType.HUNGER          -> 2
                        TriggerType.DEFAULT         -> 0
                    }
                    val score = trigger.priority * 10 + typeBase
                    candidates.add(Candidate(team, score))
                    AppLog.d(TAG, "Trigger match: ${team.name} type=${trigger.type} score=$score")
                    break // Chỉ lấy trigger khớp đầu tiên của mỗi team
                }
            }
        }

        if (candidates.isEmpty()) return null
        val best = candidates.maxByOrNull { it.score } ?: return null
        if (best.team.id == ctx.activeTeamId) return null

        AppLog.d(TAG, "Auto-switch → ${best.team.name}")
        return best.team
    }

    private fun checkTrigger(
        trigger: TeamTrigger,
        ctx: EvalContext,
        team: PetTeam,
    ): Boolean = when (trigger.type) {

        TriggerType.WEATHER -> {
            val w = ctx.currentWeather.trim().lowercase()
            trigger.weathers.any { w.contains(it.lowercase()) || it.lowercase().contains(w) }
        }

        TriggerType.HUNGER -> {
            // Chỉ check khi đây không phải team đang active
            if (team.id == ctx.activeTeamId) false
            else ctx.petHungerMap.values.any { it <= trigger.hungerThresholdPercent }
        }

        TriggerType.GARDEN_GROWTH -> {
            val plants = filterBySpecies(ctx.garden, trigger.growthSpeciesFilter)
            if (plants.isEmpty()) false
            else {
                val notFull = plants.count { it.targetScale < 1.0 }
                val pct = notFull * 100 / plants.size
                pct >= trigger.growthMinPercent
            }
        }

        TriggerType.GARDEN_MUTATION -> {
            val plants = filterBySpecies(ctx.garden, trigger.mutationSpeciesFilter)
            if (plants.isEmpty()) false
            else {
                val noMutation = plants.count { plant ->
                    if (trigger.mutationName.isBlank()) {
                        plant.mutations.isEmpty()
                    } else {
                        plant.mutations.none { m ->
                            m.lowercase().contains(trigger.mutationName.lowercase())
                        }
                    }
                }
                val pct = noMutation * 100 / plants.size
                pct >= trigger.mutationMinPercent
            }
        }

        TriggerType.DEFAULT -> {
            team.id != ctx.activeTeamId
        }
    }

    private fun filterBySpecies(
        plants: List<GardenPlantSnapshot>,
        speciesFilter: String,
    ): List<GardenPlantSnapshot> {
        if (speciesFilter.isBlank()) return plants
        return plants.filter {
            it.species.lowercase().contains(speciesFilter.lowercase())
        }
    }
}

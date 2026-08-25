package com.mgafk.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PetTeam(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val petIds: List<String> = listOf("", "", ""),
    val petSpecies: List<String> = listOf("", "", ""),
    val petNames: List<String> = listOf("", "", ""),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Returns the number of non-empty pet slots. */
    val filledSlots: Int get() = petIds.count { it.isNotBlank() }

    /** True when all 3 slots are occupied. */
    val isFull: Boolean get() = filledSlots == MAX_PETS

    /** True when no slots are occupied. */
    val isEmpty: Boolean get() = filledSlots == 0

    companion object {
        const val MAX_PETS = 3
        const val MAX_TEAMS = 30
    }
}

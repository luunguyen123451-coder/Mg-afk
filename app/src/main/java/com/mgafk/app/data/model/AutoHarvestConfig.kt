package com.mgafk.app.data.model

import kotlinx.serialization.Serializable

/**
 * Cấu hình tự động thu hoạch cây.
 * Thu hoạch khi cây đạt 100% VÀ thỏa điều kiện đột biến.
 */
@Serializable
data class AutoHarvestConfig(
    /** Bật/tắt tính năng */
    val enabled: Boolean = false,

    /** Điều kiện đột biến */
    val mutationCondition: MutationCondition = MutationCondition.ANY,

    /**
     * Tên mutation cụ thể cần có (chỉ dùng khi mutationCondition = SPECIFIC).
     * Ví dụ: "Rainbow", "Gold"
     */
    val requiredMutation: String = "",

    /**
     * Chỉ thu hoạch cây thuộc loài này. Rỗng = tất cả loài.
     */
    val speciesFilter: String = "",
) {
    enum class MutationCondition {
        /** Thu hoạch bất kể có đột biến hay không */
        NONE,
        /** Thu hoạch khi có bất kỳ đột biến nào */
        ANY,
        /** Thu hoạch khi có đột biến cụ thể */
        SPECIFIC,
    }
}

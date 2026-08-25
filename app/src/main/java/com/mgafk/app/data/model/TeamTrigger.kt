package com.mgafk.app.data.model

import kotlinx.serialization.Serializable

/**
 * Điều kiện để tự động kích hoạt một Pet Team.
 * Mỗi team có thể có nhiều trigger — hệ thống check theo priority cao → thấp.
 */
@Serializable
data class TeamTrigger(
    val id: String = java.util.UUID.randomUUID().toString(),

    /** Loại trigger */
    val type: TriggerType = TriggerType.WEATHER,

    /** Priority — số càng cao càng được ưu tiên */
    val priority: Int = 0,

    // ── WEATHER ──────────────────────────────────────────────────────────
    /** Danh sách weather kích hoạt team (e.g. ["Dawn", "Thunderstorm"]) */
    val weathers: List<String> = emptyList(),

    // ── HUNGER ───────────────────────────────────────────────────────────
    /** Kích hoạt khi pet của team đang active có hunger% <= ngưỡng này */
    val hungerThresholdPercent: Int = 30,

    // ── GARDEN_GROWTH ─────────────────────────────────────────────────────
    /**
     * Kích hoạt khi % cây CHƯA đạt 100% scale >= growthMinPercent.
     * Ví dụ: growthMinPercent=50 → kích hoạt khi >=50% cây chưa full size.
     */
    val growthMinPercent: Int = 50,

    /**
     * Nếu không rỗng, chỉ đếm cây thuộc loài này.
     * Ví dụ: "Sunflower", "Rose". Rỗng = đếm tất cả cây.
     */
    val growthSpeciesFilter: String = "",

    // ── GARDEN_MUTATION ───────────────────────────────────────────────────
    /**
     * Kích hoạt khi % cây CHƯA có mutation cụ thể >= mutationMinPercent.
     * mutationName: tên mutation cần check, e.g. "Rainbow", "Gold".
     * Rỗng = chưa có BẤT KỲ mutation nào.
     */
    val mutationMinPercent: Int = 30,
    val mutationName: String = "",
    val mutationSpeciesFilter: String = "",
) {
    enum class TriggerType {
        /** Kích hoạt khi thời tiết khớp */
        WEATHER,

        /** Kích hoạt khi pet của team active đói */
        HUNGER,

        /** Kích hoạt khi % cây chưa full size đủ nhiều */
        GARDEN_GROWTH,

        /** Kích hoạt khi % cây chưa có mutation đủ nhiều */
        GARDEN_MUTATION,

        /** Team mặc định — kích hoạt khi không trigger nào khác khớp */
        DEFAULT,
    }
}

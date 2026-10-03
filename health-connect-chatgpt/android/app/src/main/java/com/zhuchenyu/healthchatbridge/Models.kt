package com.zhuchenyu.healthchatbridge

import org.json.JSONArray
import org.json.JSONObject

data class DailyHealthSnapshot(
    val date: String,
    val steps: Long?,
    val distanceM: Double?,
    val activeCaloriesKcal: Double?,
    val totalCaloriesKcal: Double?,
    val avgHeartRateBpm: Long?,
    val minHeartRateBpm: Long?,
    val maxHeartRateBpm: Long?,
    val heartRateMeasurements: Long?,
    val restingHeartRateBpm: Long?,
    val avgSpo2Pct: Double?,
    val minSpo2Pct: Double?,
    val maxSpo2Pct: Double?,
    val sleepMinutes: Long?,
    val weightKg: Double?,
    val sourcePackages: Set<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        putNullable("steps", steps)
        putNullable("distance_m", distanceM)
        putNullable("active_calories_kcal", activeCaloriesKcal)
        putNullable("total_calories_kcal", totalCaloriesKcal)
        putNullable("avg_heart_rate_bpm", avgHeartRateBpm)
        putNullable("min_heart_rate_bpm", minHeartRateBpm)
        putNullable("max_heart_rate_bpm", maxHeartRateBpm)
        putNullable("heart_rate_measurements", heartRateMeasurements)
        putNullable("resting_heart_rate_bpm", restingHeartRateBpm)
        putNullable("avg_spo2_pct", avgSpo2Pct)
        putNullable("min_spo2_pct", minSpo2Pct)
        putNullable("max_spo2_pct", maxSpo2Pct)
        putNullable("sleep_minutes", sleepMinutes)
        putNullable("weight_kg", weightKg)
        put("source_packages", JSONArray(sourcePackages.sorted()))
    }
}

fun List<DailyHealthSnapshot>.toUploadJson(deviceId: String): String = JSONObject().apply {
    put("schema_version", 1)
    put("device_id", deviceId)
    put("timezone", "Asia/Shanghai")
    put("generated_at", java.time.Instant.now().toString())
    put("days", JSONArray().apply { this@toUploadJson.forEach { put(it.toJson()) } })
}.toString()

private fun JSONObject.putNullable(key: String, value: Any?) {
    put(key, value ?: JSONObject.NULL)
}

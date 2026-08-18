package com.zhuchenyu.healthchatbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectRepository(private val context: Context) {
    private val zone = ZoneId.of("Asia/Shanghai")

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context.applicationContext) == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient by lazy {
        check(isAvailable()) { "Health Connect 当前不可用，请先安装/更新 Health Connect" }
        HealthConnectClient.getOrCreate(context.applicationContext)
    }

    fun requestedPermissions(): Set<String> {
        val result = mutableSetOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class)
        )
        if (!isAvailable()) return result

        if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            result += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        }
        if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            result += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        }
        return result
    }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun readRecentDays(days: Int): List<DailyHealthSnapshot> {
        require(days in 1..90)
        val today = LocalDate.now(zone)
        return (days - 1 downTo 0).map { readDay(today.minusDays(it.toLong())) }
    }

    private suspend fun readDay(date: LocalDate): DailyHealthSnapshot {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val filter = TimeRangeFilter.between(start, end)
        val sources = linkedSetOf<String>()

        val activity = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL
                    ),
                    timeRangeFilter = filter
                )
            )
        }.getOrNull()

        val heart = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        HeartRateRecord.BPM_AVG,
                        HeartRateRecord.BPM_MIN,
                        HeartRateRecord.BPM_MAX,
                        HeartRateRecord.MEASUREMENTS_COUNT
                    ),
                    timeRangeFilter = filter
                )
            )
        }.getOrNull()

        val resting = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(RestingHeartRateRecord.BPM_AVG),
                    timeRangeFilter = filter
                )
            )[RestingHeartRateRecord.BPM_AVG]
        }.getOrNull()

        val sleepMinutes = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter = filter
                )
            )[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()
        }.getOrNull()

        val weightKg = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = setOf(WeightRecord.WEIGHT_AVG),
                    timeRangeFilter = filter
                )
            )[WeightRecord.WEIGHT_AVG]?.inKilograms
        }.getOrNull()

        val spo2 = runCatching {
            client.readRecords(
                ReadRecordsRequest<OxygenSaturationRecord>(timeRangeFilter = filter)
            ).records
        }.getOrDefault(emptyList())
        spo2.forEach { sources += it.metadata.dataOrigin.packageName }
        val spo2Values = spo2.map { it.percentage.value }.filter { it in 1.0..100.0 }

        runCatching {
            client.readRecords(ReadRecordsRequest<StepsRecord>(timeRangeFilter = filter)).records
        }.getOrDefault(emptyList()).forEach { sources += it.metadata.dataOrigin.packageName }

        runCatching {
            client.readRecords(ReadRecordsRequest<HeartRateRecord>(timeRangeFilter = filter)).records
        }.getOrDefault(emptyList()).forEach { sources += it.metadata.dataOrigin.packageName }

        runCatching {
            client.readRecords(ReadRecordsRequest<SleepSessionRecord>(timeRangeFilter = filter)).records
        }.getOrDefault(emptyList()).forEach { sources += it.metadata.dataOrigin.packageName }

        return DailyHealthSnapshot(
            date = date.toString(),
            steps = activity?.get(StepsRecord.COUNT_TOTAL),
            distanceM = activity?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            activeCaloriesKcal = activity?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            totalCaloriesKcal = activity?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            avgHeartRateBpm = heart?.get(HeartRateRecord.BPM_AVG),
            minHeartRateBpm = heart?.get(HeartRateRecord.BPM_MIN),
            maxHeartRateBpm = heart?.get(HeartRateRecord.BPM_MAX),
            heartRateMeasurements = heart?.get(HeartRateRecord.MEASUREMENTS_COUNT),
            restingHeartRateBpm = resting,
            avgSpo2Pct = spo2Values.takeIf { it.isNotEmpty() }?.average(),
            minSpo2Pct = spo2Values.minOrNull(),
            maxSpo2Pct = spo2Values.maxOrNull(),
            sleepMinutes = sleepMinutes,
            weightKg = weightKg,
            sourcePackages = sources
        )
    }
}

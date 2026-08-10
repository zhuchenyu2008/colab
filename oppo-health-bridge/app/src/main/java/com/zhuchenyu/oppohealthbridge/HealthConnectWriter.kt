package com.zhuchenyu.oppohealthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.ZoneId
import kotlin.math.min


data class SyncStats(
    val heartRateRecords: Int,
    val restingHeartRateRecords: Int,
    val spo2Records: Int,
    val stepsRecords: Int,
    val caloriesRecords: Int,
    val sleepRecords: Int,
    val sourceWarnings: List<String>
) {
    val totalRecords: Int
        get() = heartRateRecords + restingHeartRateRecords + spo2Records + stepsRecords + caloriesRecords + sleepRecords
}

class HealthConnectWriter(private val context: Context) {
    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class)
        )
    }

    private val zone = ZoneId.systemDefault()
    private val device = Device(type = Device.TYPE_WATCH, manufacturer = "OPPO", model = "Watch 3 Pro")

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context.applicationContext)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient by lazy {
        check(isAvailable()) { "Health Connect SDK 当前不可用，状态码=${sdkStatus()}" }
        HealthConnectClient.getOrCreate(context.applicationContext)
    }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean = isAvailable() && grantedPermissions().containsAll(PERMISSIONS)

    suspend fun write(snapshot: OppoSnapshot): SyncStats {
        check(isAvailable()) { "Health Connect SDK 当前不可用，状态码=${sdkStatus()}" }

        val version = snapshot.generatedAtMs
        val records = mutableListOf<Record>()
        var hrCount = 0
        var restingCount = 0
        var spo2Count = 0
        var stepsCount = 0
        var caloriesCount = 0
        var sleepCount = 0

        // One stable record per local calendar day. OppoHealthReader always reads complete calendar
        // days for previous days, so a rolling-window partial day can no longer overwrite history.
        snapshot.heartRates
            .filter { it.bpm in 1..300 && it.timeMs > 0L }
            .groupBy { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }
            .forEach { (day, points) ->
                val samples = points.sortedBy { it.timeMs }.map {
                    HeartRateRecord.Sample(
                        time = Instant.ofEpochMilli(it.timeMs),
                        beatsPerMinute = it.bpm.toLong()
                    )
                }
                if (samples.isNotEmpty()) {
                    val start = day.atStartOfDay(zone).toInstant()
                    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
                    val now = Instant.now()
                    val end = minOfInstant(dayEnd, now).takeIf { it.isAfter(start) } ?: start.plusMillis(1)
                    records += HeartRateRecord(
                        startTime = start,
                        startZoneOffset = start.atZone(zone).offset,
                        endTime = end,
                        endZoneOffset = end.atZone(zone).offset,
                        samples = samples.filter { !it.time.isBefore(start) && it.time.isBefore(end) },
                        metadata = metadata("oppo-heart-rate-$day", version)
                    )
                    hrCount++
                }
            }

        snapshot.restingHeartRates
            .filter { it.bpm in 1..300 && it.timeMs > 0L }
            .forEach { point ->
                val time = Instant.ofEpochMilli(point.timeMs)
                records += RestingHeartRateRecord(
                    time = time,
                    zoneOffset = time.atZone(zone).offset,
                    beatsPerMinute = point.bpm.toLong(),
                    metadata = metadata("oppo-resting-heart-rate-${point.timeMs}", version)
                )
                restingCount++
            }

        snapshot.spo2
            .filter { it.percentage in 1..100 && it.timeMs > 0L }
            .forEach { point ->
                val time = Instant.ofEpochMilli(point.timeMs)
                records += OxygenSaturationRecord(
                    time = time,
                    zoneOffset = time.atZone(zone).offset,
                    percentage = Percentage(point.percentage.toDouble()),
                    metadata = metadata("oppo-spo2-${point.timeMs}", version)
                )
                spo2Count++
            }

        val nowMs = System.currentTimeMillis()
        snapshot.dailyActivity.forEach { activity ->
            val startMs = activity.dayStartMs
            val nextDayStartMs = Instant.ofEpochMilli(startMs)
                .atZone(zone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
            val endMs = min(nextDayStartMs, nowMs)
            if (startMs <= 0L || endMs <= startMs) return@forEach
            val start = Instant.ofEpochMilli(startMs)
            val end = Instant.ofEpochMilli(endMs)

            if (activity.steps in 1L..1_000_000L) {
                records += StepsRecord(
                    startTime = start,
                    startZoneOffset = start.atZone(zone).offset,
                    endTime = end,
                    endZoneOffset = end.atZone(zone).offset,
                    count = activity.steps,
                    metadata = metadata("oppo-steps-$startMs", version)
                )
                stepsCount++
            }

            if (activity.caloriesKcal > 0.0) {
                records += TotalCaloriesBurnedRecord(
                    startTime = start,
                    startZoneOffset = start.atZone(zone).offset,
                    endTime = end,
                    endZoneOffset = end.atZone(zone).offset,
                    energy = Energy.kilocalories(activity.caloriesKcal),
                    metadata = metadata("oppo-calories-$startMs", version)
                )
                caloriesCount++
            }
        }

        snapshot.sleep
            .filter { it.startMs > 0L && it.endMs > it.startMs }
            .forEach { sleep ->
                val start = Instant.ofEpochMilli(sleep.startMs)
                val end = Instant.ofEpochMilli(sleep.endMs)
                val stages = sanitizeStages(sleep, start, end)
                records += SleepSessionRecord(
                    startTime = start,
                    startZoneOffset = start.atZone(zone).offset,
                    endTime = end,
                    endZoneOffset = end.atZone(zone).offset,
                    metadata = metadata("oppo-sleep-${sleep.startMs}-${sleep.endMs}", version),
                    title = "OPPO Watch 睡眠",
                    notes = sleep.score?.let { "OPPO 睡眠评分：$it" },
                    stages = stages
                )
                sleepCount++
            }

        records.chunked(100).forEach { chunk ->
            client.insertRecords(chunk)
        }

        return SyncStats(
            heartRateRecords = hrCount,
            restingHeartRateRecords = restingCount,
            spo2Records = spo2Count,
            stepsRecords = stepsCount,
            caloriesRecords = caloriesCount,
            sleepRecords = sleepCount,
            sourceWarnings = snapshot.warnings
        )
    }

    private fun sanitizeStages(
        sleep: SleepWindow,
        sessionStart: Instant,
        sessionEnd: Instant
    ): List<SleepSessionRecord.Stage> {
        val result = mutableListOf<SleepSessionRecord.Stage>()
        var cursor = sessionStart

        sleep.stages.sortedBy { it.startMs }.forEach { source ->
            var start = Instant.ofEpochMilli(source.startMs)
            val end = Instant.ofEpochMilli(source.endMs)
            if (start.isBefore(sessionStart)) start = sessionStart
            if (start.isBefore(cursor)) start = cursor
            val clippedEnd = if (end.isAfter(sessionEnd)) sessionEnd else end
            if (!clippedEnd.isAfter(start)) return@forEach

            result += SleepSessionRecord.Stage(
                startTime = start,
                endTime = clippedEnd,
                stage = mapOppoSleepStage(source.stage)
            )
            cursor = clippedEnd
        }

        return result
    }

    private fun mapOppoSleepStage(oppoStage: Int): Int = when (oppoStage) {
        1 -> SleepSessionRecord.STAGE_TYPE_SLEEPING
        2 -> SleepSessionRecord.STAGE_TYPE_DEEP
        3 -> SleepSessionRecord.STAGE_TYPE_REM
        4 -> SleepSessionRecord.STAGE_TYPE_LIGHT
        5 -> SleepSessionRecord.STAGE_TYPE_AWAKE
        6 -> SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
        else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
    }

    private fun minOfInstant(a: Instant, b: Instant): Instant = if (a.isBefore(b)) a else b

    private fun metadata(clientRecordId: String, version: Long): Metadata =
        Metadata.autoRecorded(
            device = device,
            clientRecordId = clientRecordId,
            clientRecordVersion = version
        )
}

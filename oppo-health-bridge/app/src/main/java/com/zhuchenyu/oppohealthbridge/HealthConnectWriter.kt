package com.zhuchenyu.oppohealthbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthPermission
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
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context.applicationContext) }
    private val device = Device(type = Device.TYPE_WATCH, manufacturer = "OPPO", model = "Watch 3 Pro")

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(PERMISSIONS)

    suspend fun write(snapshot: OppoSnapshot): SyncStats {
        val version = snapshot.generatedAtMs
        val records = mutableListOf<Record>()
        var hrCount = 0
        var restingCount = 0
        var spo2Count = 0
        var stepsCount = 0
        var caloriesCount = 0
        var sleepCount = 0

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
                    val start = samples.first().time
                    val last = samples.last().time
                    val end = if (last.isAfter(start)) last else start.plusMillis(1)
                    records += HeartRateRecord(
                        startTime = start,
                        startZoneOffset = start.atZone(zone).offset,
                        endTime = end,
                        endZoneOffset = end.atZone(zone).offset,
                        samples = samples,
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
            val endMs = min(startMs + 24L * 60L * 60L * 1000L, nowMs)
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
                records += SleepSessionRecord(
                    startTime = start,
                    startZoneOffset = start.atZone(zone).offset,
                    endTime = end,
                    endZoneOffset = end.atZone(zone).offset,
                    metadata = metadata("oppo-sleep-${sleep.startMs}-${sleep.endMs}", version),
                    title = "OPPO Watch 睡眠",
                    notes = sleep.score?.let { "OPPO 睡眠评分：$it" }
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

    private fun metadata(clientRecordId: String, version: Long): Metadata =
        Metadata.autoRecorded(
            device = device,
            clientRecordId = clientRecordId,
            clientRecordVersion = version
        )
}

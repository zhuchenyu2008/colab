package com.zhuchenyu.oppohealthbridge

import android.app.Activity
import android.content.Context
import com.heytap.databaseengine.HeytapHealthApi
import com.heytap.databaseengine.apiv2.HResponse
import com.heytap.databaseengine.apiv2.auth.AuthResult
import com.heytap.databaseengine.apiv2.common.util.InstallUtils
import com.heytap.databaseengine.apiv3.DataReadRequest
import com.heytap.databaseengine.apiv3.data.DataPoint
import com.heytap.databaseengine.apiv3.data.DataSet
import com.heytap.databaseengine.apiv3.data.DataType
import com.heytap.databaseengine.apiv3.data.Element
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OppoSdkException(val errorCode: Int, message: String = "OPPO 健康 SDK 错误：$errorCode") : Exception(message)

data class HeartRatePoint(val timeMs: Long, val bpm: Int)
data class Spo2Point(val timeMs: Long, val percentage: Int)
data class DailyActivityPoint(
    val dayStartMs: Long,
    val steps: Long,
    val caloriesKcal: Double,
    val distanceMeters: Long
)
data class SleepWindow(
    val startMs: Long,
    val endMs: Long,
    val score: Int?
)
data class OppoSnapshot(
    val generatedAtMs: Long,
    val heartRates: List<HeartRatePoint>,
    val restingHeartRates: List<HeartRatePoint>,
    val spo2: List<Spo2Point>,
    val dailyActivity: List<DailyActivityPoint>,
    val sleep: List<SleepWindow>,
    val warnings: List<String>
)

class OppoHealthReader(private val context: Context) {
    companion object {
        const val HEALTH_PACKAGE = "com.heytap.health"
        private const val SUCCESS = 100000
    }

    private var initialized = false
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun isHealthInstalled(): Boolean = InstallUtils.isAppInstalled(context, HEALTH_PACKAGE)

    fun initialize() {
        if (initialized) return
        HeytapHealthApi.setLoggable(BuildConfig.DEBUG)
        HeytapHealthApi.init(context.applicationContext)
        initialized = true
    }

    suspend fun requestAuthorization(activity: Activity) {
        initialize()
        suspendCancellableCoroutine<Unit> { continuation ->
            HeytapHealthApi.getInstance().authorityApi().request(activity, object : HResponse<AuthResult> {
                override fun onSuccess(result: AuthResult) {
                    val code = result.errorCode
                    if (code == SUCCESS) {
                        if (continuation.isActive) continuation.resume(Unit)
                    } else if (continuation.isActive) {
                        continuation.resumeWithException(OppoSdkException(code))
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(OppoSdkException(errorCode))
                }
            })
        }
    }

    suspend fun authorizedScopes(): List<String> {
        initialize()
        return suspendCancellableCoroutine { continuation ->
            HeytapHealthApi.getInstance().authorityApi().valid(object : HResponse<List<String>> {
                override fun onSuccess(scopes: List<String>) {
                    if (continuation.isActive) continuation.resume(scopes)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(OppoSdkException(errorCode))
                }
            })
        }
    }

    suspend fun readSnapshot(days: Int = 7): OppoSnapshot {
        initialize()
        val safeDays = days.coerceIn(1, 29)
        val end = System.currentTimeMillis()
        val start = end - safeDays * 24L * 60L * 60L * 1000L
        val warnings = mutableListOf<String>()

        suspend fun readOrEmpty(type: DataType, label: String): List<DataPoint> {
            return try {
                readData(type, start, end)
            } catch (t: Throwable) {
                warnings += "$label：${t.message ?: t.javaClass.simpleName}"
                emptyList()
            }
        }

        val heartPoints = readOrEmpty(DataType.TYPE_HEART_RATE, "心率")
            .mapNotNull { point ->
                val bpm = safeInt(point, Element.ELEMENT_HEART_RATE) ?: return@mapNotNull null
                val time = pointTime(point)
                if (time <= 0L || bpm !in 1..300) null else HeartRatePoint(time, bpm)
            }
            .distinctBy { it.timeMs to it.bpm }
            .sortedBy { it.timeMs }

        val restingPoints = readOrEmpty(DataType.TYPE_HEART_RATE_COUNT, "静息心率")
            .mapNotNull { point ->
                val bpm = safeInt(point, Element.ELEMENT_REST_HR) ?: return@mapNotNull null
                val time = normalizeTimestamp(point.timeStamp)
                if (time <= 0L || bpm !in 1..300) null else HeartRatePoint(time, bpm)
            }
            .distinctBy { it.timeMs }
            .sortedBy { it.timeMs }

        val spo2Points = readOrEmpty(DataType.TYPE_BLOOD_OXYGEN, "血氧")
            .mapNotNull { point ->
                val value = safeInt(point, Element.ELEMENT_BLOOD_OXYGEN) ?: return@mapNotNull null
                val time = pointTime(point)
                if (time <= 0L || value !in 1..100) null else Spo2Point(time, value)
            }
            .distinctBy { it.timeMs }
            .sortedBy { it.timeMs }

        val activityPoints = readOrEmpty(DataType.TYPE_DAILY_ACTIVITY_COUNT, "每日活动")
            .mapNotNull { point ->
                val dayStart = dayStartFromRaw(point.timeStamp) ?: return@mapNotNull null
                val steps = (safeInt(point, Element.ELEMENT_STEP) ?: 0).toLong().coerceAtLeast(0L)
                val calories = (safeInt(point, Element.ELEMENT_CALORIE) ?: 0).toDouble().coerceAtLeast(0.0)
                val distance = (safeInt(point, Element.ELEMENT_DISTANCE) ?: 0).toLong().coerceAtLeast(0L)
                DailyActivityPoint(dayStart, steps, calories, distance)
            }
            .distinctBy { it.dayStartMs }
            .sortedBy { it.dayStartMs }

        val sleepPoints = readOrEmpty(DataType.TYPE_SLEEP, "睡眠")
            .mapNotNull(::sleepWindowFromPoint)
            .distinctBy { it.startMs to it.endMs }
            .sortedBy { it.startMs }

        return OppoSnapshot(
            generatedAtMs = end,
            heartRates = heartPoints,
            restingHeartRates = restingPoints,
            spo2 = spo2Points,
            dailyActivity = activityPoints,
            sleep = sleepPoints,
            warnings = warnings
        )
    }

    private suspend fun readData(type: DataType, startMs: Long, endMs: Long): List<DataPoint> {
        val request = DataReadRequest.Builder()
            .read(type)
            .setTimeRange(startMs, endMs)
            .build()

        return suspendCancellableCoroutine { continuation ->
            HeytapHealthApi.getInstance().dataApi().read(request, object : HResponse<List<DataSet>> {
                override fun onSuccess(dataSets: List<DataSet>) {
                    val points = dataSets.flatMap { it.dataPoints ?: emptyList() }
                    if (continuation.isActive) continuation.resume(points)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(OppoSdkException(errorCode))
                }
            })
        }
    }

    private fun sleepWindowFromPoint(point: DataPoint): SleepWindow? {
        val score = safeInt(point, Element.ELEMENT_SLEEP_SCORE)
        val fallAsleep = safeInt(point, Element.ELEMENT_FALL_ASLEEP)
        val sleepOut = safeInt(point, Element.ELEMENT_SLEEP_OUT)
        val date = localDateFromRaw(point.timeStamp)

        if (date != null && fallAsleep != null && sleepOut != null && fallAsleep in 0..1439 && sleepOut in 0..1439) {
            val fallHour = fallAsleep / 60
            val fallMinute = fallAsleep % 60
            val outHour = sleepOut / 60
            val outMinute = sleepOut % 60

            val endDate = date
            val startDate = if (sleepOut <= fallAsleep) date.minusDays(1) else date
            val start = startDate.atTime(fallHour, fallMinute).atZone(zone).toInstant().toEpochMilli()
            val end = endDate.atTime(outHour, outMinute).atZone(zone).toInstant().toEpochMilli()
            if (validSleepWindow(start, end)) return SleepWindow(start, end, score)
        }

        val rawStart = normalizeTimestamp(point.startTimeStamp)
        val rawEnd = normalizeTimestamp(point.timeStamp)
        if (validSleepWindow(rawStart, rawEnd)) return SleepWindow(rawStart, rawEnd, score)

        return null
    }

    private fun validSleepWindow(start: Long, end: Long): Boolean {
        val duration = end - start
        return start > 0L && end > start && duration in 5L * 60_000L..24L * 60L * 60_000L
    }

    private fun safeInt(point: DataPoint, element: Element): Int? =
        runCatching { point.getValue(element)?.asInt() }.getOrNull()

    private fun pointTime(point: DataPoint): Long {
        val start = normalizeTimestamp(point.startTimeStamp)
        if (start > 0L) return start
        return normalizeTimestamp(point.timeStamp)
    }

    private fun localDateFromRaw(raw: Long): LocalDate? {
        if (raw in 19_000_000L..29_991_231L) {
            return runCatching {
                LocalDate.of(
                    (raw / 10_000L).toInt(),
                    ((raw / 100L) % 100L).toInt(),
                    (raw % 100L).toInt()
                )
            }.getOrNull()
        }
        val normalized = normalizeTimestamp(raw)
        return if (normalized > 0L) {
            Instant.ofEpochMilli(normalized).atZone(zone).toLocalDate()
        } else null
    }

    private fun dayStartFromRaw(raw: Long): Long? =
        localDateFromRaw(raw)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

    private fun normalizeTimestamp(raw: Long): Long {
        return when {
            raw <= 0L -> 0L
            raw in 19_000_000L..29_991_231L -> {
                localDateFromRaw(raw)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
            }
            raw <= 9_999_999_999L -> raw * 1000L
            else -> raw
        }
    }
}

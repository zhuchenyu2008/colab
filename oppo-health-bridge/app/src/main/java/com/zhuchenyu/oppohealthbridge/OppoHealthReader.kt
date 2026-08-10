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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

class OppoSdkException(val errorCode: Int, message: String = "OPPO 健康 SDK 错误：$errorCode") : Exception(message)

data class HeartRatePoint(val timeMs: Long, val bpm: Int)
data class Spo2Point(val timeMs: Long, val percentage: Int)
data class DailyActivityPoint(
    val dayStartMs: Long,
    val steps: Long,
    val caloriesKcal: Double,
    val distanceMeters: Long
)
data class SleepStageWindow(
    val startMs: Long,
    val endMs: Long,
    val stage: Int
)
data class SleepWindow(
    val startMs: Long,
    val endMs: Long,
    val score: Int?,
    val stages: List<SleepStageWindow> = emptyList()
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
        private const val AUTH_FAILURE = 100012
        private const val AUTH_TIMEOUT_MS = 20_000L
        private const val AUTH_VALID_DELAY_MS = 800L
        private const val MAX_QUERY_RANGE_MS = 29L * 24L * 60L * 60L * 1000L
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
        var callbackFailure: Throwable? = null

        val callbackCompleted = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            try {
                requestAuthorizationOnce(activity)
                true
            } catch (t: Throwable) {
                callbackFailure = t
                false
            }
        }

        // Some OPPO Health/ColorOS versions return to our Activity before the SDK callback is reliable.
        // Treat authorityApi().valid() as the final source of truth after a short settle delay.
        delay(AUTH_VALID_DELAY_MS)
        val validResult = runCatching { authorizedScopes() }
        val scopes = validResult.getOrNull().orEmpty()
        if (scopes.isNotEmpty()) return

        callbackFailure?.let { throw it }
        validResult.exceptionOrNull()?.let { throw it }
        if (callbackCompleted == null) {
            throw OppoSdkException(AUTH_FAILURE, "OPPO 健康授权超时，且未返回有效 scope")
        }
        throw OppoSdkException(AUTH_FAILURE, "OPPO 健康未返回任何已授权 scope")
    }

    private suspend fun requestAuthorizationOnce(activity: Activity) {
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

    /**
     * Reads complete local calendar days rather than a rolling N*24h window.
     * This prevents a partial first day from overwriting a previously complete Health Connect record.
     */
    suspend fun readSnapshot(days: Int = 7): OppoSnapshot {
        val safeDays = days.coerceIn(1, 29)
        val end = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        val start = today.minusDays((safeDays - 1).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        return readSnapshot(start, end)
    }

    suspend fun readSnapshot(startMs: Long, endMs: Long): OppoSnapshot {
        initialize()
        require(startMs in 1..endMs) { "Invalid OPPO Health time range" }

        val warnings = mutableListOf<String>()

        suspend fun readOrEmpty(type: DataType, label: String, start: Long = startMs, end: Long = endMs): List<DataPoint> {
            return try {
                readDataChunked(type, start, end)
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

        // TYPE_SLEEP is stage/detail data. Read one extra day before the summary range so a sleep
        // ending this morning can still include its pre-midnight stages.
        val sleepStageStart = (startMs - 24L * 60L * 60L * 1000L).coerceAtLeast(1L)
        val sleepStages = readOrEmpty(DataType.TYPE_SLEEP, "睡眠阶段", sleepStageStart, endMs)
            .mapNotNull(::sleepStageFromPoint)
            .distinctBy { Triple(it.startMs, it.endMs, it.stage) }
            .sortedBy { it.startMs }

        // TYPE_SLEEP_COUNT is the canonical whole-night summary/session source.
        val sleepSessions = readOrEmpty(DataType.TYPE_SLEEP_COUNT, "睡眠统计")
            .mapNotNull { point -> sleepWindowFromSummary(point, sleepStages) }
            .distinctBy { it.startMs to it.endMs }
            .sortedBy { it.startMs }

        return OppoSnapshot(
            generatedAtMs = endMs,
            heartRates = heartPoints,
            restingHeartRates = restingPoints,
            spo2 = spo2Points,
            dailyActivity = activityPoints,
            sleep = sleepSessions,
            warnings = warnings
        )
    }

    private suspend fun readDataChunked(type: DataType, startMs: Long, endMs: Long): List<DataPoint> {
        if (startMs > endMs) return emptyList()
        val points = mutableListOf<DataPoint>()
        var chunkStart = startMs
        while (chunkStart <= endMs) {
            val chunkEnd = min(chunkStart + MAX_QUERY_RANGE_MS - 1L, endMs)
            points += readData(type, chunkStart, chunkEnd)
            chunkStart = chunkEnd + 1L
        }
        return points
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

    private fun sleepStageFromPoint(point: DataPoint): SleepStageWindow? {
        val stage = safeInt(point, Element.ELEMENT_SLEEP) ?: return null
        val start = normalizeTimestamp(point.startTimeStamp)
        val end = normalizeTimestamp(point.timeStamp)
        if (start <= 0L || end <= start || end - start > 24L * 60L * 60L * 1000L) return null
        return SleepStageWindow(start, end, stage)
    }

    private fun sleepWindowFromSummary(point: DataPoint, allStages: List<SleepStageWindow>): SleepWindow? {
        val score = safeInt(point, Element.ELEMENT_SLEEP_SCORE)
        val fallAsleep = safeInt(point, Element.ELEMENT_FALL_ASLEEP)
        val sleepOut = safeInt(point, Element.ELEMENT_SLEEP_OUT)
        val date = localDateFromRaw(point.timeStamp) ?: return null

        if (fallAsleep == null || sleepOut == null || fallAsleep !in 0..1439 || sleepOut !in 0..1439) {
            return null
        }

        val fallHour = fallAsleep / 60
        val fallMinute = fallAsleep % 60
        val outHour = sleepOut / 60
        val outMinute = sleepOut % 60

        val endDate = date
        val startDate = if (sleepOut <= fallAsleep) date.minusDays(1) else date
        val start = startDate.atTime(fallHour, fallMinute).atZone(zone).toInstant().toEpochMilli()
        val end = endDate.atTime(outHour, outMinute).atZone(zone).toInstant().toEpochMilli()
        if (!validSleepWindow(start, end)) return null

        val stages = allStages
            .asSequence()
            .filter { it.endMs > start && it.startMs < end }
            .mapNotNull { stage ->
                val clippedStart = maxOf(stage.startMs, start)
                val clippedEnd = minOf(stage.endMs, end)
                if (clippedEnd <= clippedStart) null else stage.copy(startMs = clippedStart, endMs = clippedEnd)
            }
            .distinctBy { Triple(it.startMs, it.endMs, it.stage) }
            .sortedBy { it.startMs }
            .toList()

        return SleepWindow(start, end, score, stages)
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

package com.mgbus

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BusInfo(
    val number: String,
    val direction: String,
    val arrival: String,
    val timeLeft: String,
    val arrivalDate: String,
    val hit: ScheduleHit
) {
    val announcement: String
        get() = "Автобус номер $number. У напрямку $direction. $arrivalDate. Прибуття о $arrival. Час очікування: $timeLeft."
}

data class StopOptions(val filter: ScheduleFilter, val directions: List<String>)
data class DailySchedule(val entries: List<BusInfo>, val label: String)
data class TripStopInfo(
    val key: String,
    val stopName: String,
    val arrival: String,
    val arrivalDate: String,
    val direction: String,
    val selected: Boolean
)
data class TripScheduleInfo(val number: String, val date: String, val entries: List<TripStopInfo>)

class BusRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("mgbus", Context.MODE_PRIVATE)
    private val cacheFile = File(appContext.filesDir, CACHE_FILE_NAME)
    @Volatile private var schedule: LocalSchedule = loadInitialSchedule()

    fun lastStop(): String? = preferences.getString("last_stop", null)
    fun saveStop(stop: String) { preferences.edit().putString("last_stop", stop).apply() }

    /** Returns true only when a newer, valid schedule was downloaded and activated. */
    suspend fun updateSchedule(): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = downloadSchedule()
            val remoteData = ScheduleParser.parse(text)
            synchronized(updateLock) {
                if (remoteData.version <= schedule.data.version) return@synchronized false
                writeCache(text)
                schedule = LocalSchedule(remoteData)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun stops(): List<String> = withContext(Dispatchers.IO) {
        val current = schedule
        current.data.stops.map { it.name }
    }

    suspend fun routeNumbers(): List<String> = withContext(Dispatchers.IO) {
        val current = schedule
        current.routeNumbers()
    }

    suspend fun options(stop: String, filter: ScheduleFilter): StopOptions = withContext(Dispatchers.Default) {
        val current = schedule
        val id = current.data.stops.first { it.name == stop }.id
        val normalized = current.normalizeFilter(id, filter)
        StopOptions(normalized, current.directions(id, normalized.routeNumber))
    }

    suspend fun bus(stop: String, filter: ScheduleFilter, offset: Int, anchor: BusInfo? = null): BusInfo? =
        withContext(Dispatchers.Default) {
            val current = schedule
            val id = current.data.stops.first { it.name == stop }.id
            val now = System.currentTimeMillis()
            current.select(id, now, offset, anchor?.hit, filter)?.let { info(current, it, now) }
        }

    suspend fun today(stop: String, filter: ScheduleFilter): DailySchedule = withContext(Dispatchers.Default) {
        val current = schedule
        val now = System.currentTimeMillis()
        val id = current.data.stops.first { it.name == stop }.id
        DailySchedule(current.daySchedule(id, now, filter).map { info(current, it, now) },
            "${current.dateText(now, now)}, ${current.profileLabel(now).lowercase()}")
    }

    suspend fun calculationTime(): String = withContext(Dispatchers.Default) {
        val current = schedule
        current.clockText(System.currentTimeMillis())
    }

    suspend fun trip(bus: BusInfo): TripScheduleInfo = withContext(Dispatchers.Default) {
        val current = schedule
        val entries = current.tripSchedule(bus.hit)
        val serviceDay = bus.hit.trip?.serviceDayMillis ?: bus.hit.arrivalMillis
        val serviceDate = current.clockText(serviceDay).substringBefore(' ')
        TripScheduleInfo(bus.number, serviceDate,
            entries.map {
                TripStopInfo(it.key, it.stopName, current.arrivalText(it.arrivalMillis),
                    current.clockText(it.arrivalMillis).substringBefore(' ').takeUnless { date -> date == serviceDate }.orEmpty(),
                    it.direction, it.selected)
            })
    }

    private fun info(current: LocalSchedule, hit: ScheduleHit, now: Long): BusInfo = BusInfo(
        hit.routeNumber, hit.direction, current.arrivalText(hit.arrivalMillis),
        current.waitingText(hit.arrivalMillis, now), current.dateText(hit.arrivalMillis, now), hit
    )

    private fun loadInitialSchedule(): LocalSchedule {
        val bundledText = appContext.assets.open(ASSET_FILE_NAME)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val bundled = ScheduleParser.parse(bundledText)
        val cached = runCatching {
            if (!cacheFile.isFile) null
            else ScheduleParser.parse(cacheFile.readText(Charsets.UTF_8))
        }.getOrNull()
        return LocalSchedule(cached?.takeIf { it.version > bundled.version } ?: bundled)
    }

    private fun downloadSchedule(): String {
        val connection = URL(SCHEDULE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "MGBus-Android")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Schedule request failed: HTTP ${connection.responseCode}")
            }
            val declaredSize = connection.contentLengthLong
            if (declaredSize > MAX_SCHEDULE_BYTES) throw IOException("Schedule file is too large")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_SCHEDULE_BYTES) throw IOException("Schedule file is too large")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeCache(text: String) {
        val parent = cacheFile.parentFile ?: throw IOException("Cache directory is unavailable")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create cache directory")
        val temporary = File(parent, "$CACHE_FILE_NAME.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(cacheFile)) {
                if (cacheFile.exists() && !cacheFile.delete()) throw IOException("Cannot replace schedule cache")
                if (!temporary.renameTo(cacheFile)) throw IOException("Cannot save schedule cache")
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private companion object {
        const val ASSET_FILE_NAME = "bus_schedule.json"
        const val CACHE_FILE_NAME = "bus_schedule.json"
        const val SCHEDULE_URL =
            "https://raw.githubusercontent.com/Halimon-Alexandr/MGBus/main/app/src/main/assets/bus_schedule.json"
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 3_000
        const val MAX_SCHEDULE_BYTES = 512 * 1024
        val updateLock = Any()
    }
}

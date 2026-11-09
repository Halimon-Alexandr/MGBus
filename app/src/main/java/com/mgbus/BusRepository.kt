package com.mgbus

import android.content.Context
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
    private val schedule: LocalSchedule by lazy {
        val text = appContext.assets.open("bus_schedule.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        LocalSchedule(ScheduleParser.parse(text))
    }

    fun lastStop(): String? = preferences.getString("last_stop", null)
    fun saveStop(stop: String) { preferences.edit().putString("last_stop", stop).apply() }
    suspend fun stops(): List<String> = withContext(Dispatchers.IO) { schedule.data.stops.map { it.name } }
    suspend fun routeNumbers(): List<String> = withContext(Dispatchers.IO) { schedule.routeNumbers() }

    suspend fun options(stop: String, filter: ScheduleFilter): StopOptions = withContext(Dispatchers.Default) {
        val id = schedule.data.stops.first { it.name == stop }.id
        val normalized = schedule.normalizeFilter(id, filter)
        StopOptions(normalized, schedule.directions(id, normalized.routeNumber))
    }

    suspend fun bus(stop: String, filter: ScheduleFilter, offset: Int, anchor: BusInfo? = null): BusInfo? =
        withContext(Dispatchers.Default) {
            val id = schedule.data.stops.first { it.name == stop }.id
            val now = System.currentTimeMillis()
            schedule.select(id, now, offset, anchor?.hit, filter)?.let { info(it, now) }
        }

    suspend fun today(stop: String, filter: ScheduleFilter): DailySchedule = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val id = schedule.data.stops.first { it.name == stop }.id
        DailySchedule(schedule.daySchedule(id, now, filter).map { info(it, now) },
            "${schedule.dateText(now, now)}, ${schedule.profileLabel(now).lowercase()}")
    }

    suspend fun calculationTime(): String = withContext(Dispatchers.Default) { schedule.clockText(System.currentTimeMillis()) }

    suspend fun trip(bus: BusInfo): TripScheduleInfo = withContext(Dispatchers.Default) {
        val entries = schedule.tripSchedule(bus.hit)
        val serviceDay = bus.hit.trip?.serviceDayMillis ?: bus.hit.arrivalMillis
        val serviceDate = schedule.clockText(serviceDay).substringBefore(' ')
        TripScheduleInfo(bus.number, serviceDate,
            entries.map {
                TripStopInfo(it.key, it.stopName, schedule.arrivalText(it.arrivalMillis),
                    schedule.clockText(it.arrivalMillis).substringBefore(' ').takeUnless { date -> date == serviceDate }.orEmpty(),
                    it.direction, it.selected)
            })
    }

    private fun info(hit: ScheduleHit, now: Long): BusInfo = BusInfo(
        hit.routeNumber, hit.direction, schedule.arrivalText(hit.arrivalMillis),
        schedule.waitingText(hit.arrivalMillis, now), schedule.dateText(hit.arrivalMillis, now), hit
    )
}

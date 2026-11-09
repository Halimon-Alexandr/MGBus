package com.mgbus

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ScheduleStop(val id: String, val name: String)
data class ScheduleEvent(val id: String, val stopId: String, val direction: String, val offsetMinutes: Int)
data class ScheduleRoute(val id: String, val number: String, val events: List<ScheduleEvent>)
data class ScheduleProfile(val label: String, val departures: Map<String, List<Int>>)
data class ScheduleData(
    val version: Int,
    val updatedAt: String,
    val timezone: String,
    val stops: List<ScheduleStop>,
    val routes: List<ScheduleRoute>,
    val profiles: Map<String, ScheduleProfile>,
    val weekdays: String,
    val weekends: String,
    val exceptions: Map<String, String>
)

data class ScheduleFilter(val routeNumber: String? = null, val direction: String? = null) {
    fun accepts(hit: ScheduleHit): Boolean =
        (routeNumber == null || routeNumber == hit.routeNumber) &&
            (direction == null || direction == hit.direction)
}

fun initialStop(stops: List<String>, saved: String?): String? =
    saved?.takeIf { it in stops } ?: stops.firstOrNull()

data class TripReference(val serviceDayMillis: Long, val routeId: String, val departureIndex: Int)

data class TripStopHit(
    val key: String,
    val stopId: String,
    val stopName: String,
    val direction: String,
    val arrivalMillis: Long,
    val selected: Boolean
)

data class ScheduleHit(
    val key: String,
    val routeNumber: String,
    val direction: String,
    val arrivalMillis: Long,
    val trip: TripReference? = null
)

/** Чиста логіка розрахунку. Не потребує Android або мережі. */
class LocalSchedule(val data: ScheduleData) {
    private val locale = Locale.forLanguageTag("uk-UA")
    // Старі версії Android можуть знати лише історичний ідентифікатор.
    private val zone = TimeZone.getTimeZone(data.timezone).let {
        if (it.id == "GMT" && data.timezone == "Europe/Kyiv") TimeZone.getTimeZone("Europe/Kiev") else it
    }

    private fun day(time: Long): Calendar = Calendar.getInstance(zone).apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun format(pattern: String, time: Long): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(time))

    private fun profile(date: Calendar): ScheduleProfile {
        val dateKey = format("yyyy-MM-dd", date.timeInMillis)
        val weekday = date.get(Calendar.DAY_OF_WEEK)
        val fallback = if (weekday == Calendar.SATURDAY || weekday == Calendar.SUNDAY) data.weekends else data.weekdays
        return data.profiles.getValue(data.exceptions[dateKey] ?: fallback)
    }

    fun routeNumbers(): List<String> = data.routes.map { it.number }.distinct()

    fun directions(stopId: String, routeNumber: String?): List<String> = data.routes
        .filter { routeNumber == null || it.number == routeNumber }
        .flatMap { it.events }.filter { it.stopId == stopId }.map { it.direction }.distinct()

    fun normalizeFilter(stopId: String, filter: ScheduleFilter): ScheduleFilter {
        val route = filter.routeNumber?.takeIf { it in routeNumbers() }
        val direction = filter.direction?.takeIf { it in directions(stopId, route) }
        return ScheduleFilter(route, direction)
    }

    fun profileLabel(time: Long): String = profile(day(time)).label
    fun clockText(time: Long): String = format("dd.MM.yyyy HH:mm:ss", time)
    fun arrivalText(time: Long): String = format("HH:mm", time)
    fun dateText(time: Long, now: Long): String {
        val today = day(now)
        val target = day(time).timeInMillis
        if (target == today.timeInMillis) return "Сьогодні"
        val tomorrow = today.clone() as Calendar
        tomorrow.add(Calendar.DATE, 1)
        if (target == tomorrow.timeInMillis) return "Завтра"
        today.add(Calendar.DATE, -1)
        if (target == today.timeInMillis) return "Вчора"
        return format("dd.MM.yyyy", time)
    }

    fun waitingText(time: Long, now: Long): String {
        val delta = time - now
        if (delta in -59_999L..59_999L) return "Зараз"
        val minutes = if (delta > 0) (delta + 59_999L) / 60_000L else -delta / 60_000L
        val days = minutes / 1440
        val hours = minutes % 1440 / 60
        val rest = minutes % 60
        val text = buildList {
            if (days > 0) add("$days дн.")
            if (hours > 0) add("$hours год.")
            if (rest > 0 || isEmpty()) add("$rest хв.")
        }.joinToString(" ")
        return if (delta < 0) "Рейс минув $text тому" else text
    }

    private fun hits(stopId: String, serviceDay: Calendar): List<ScheduleHit> {
        val serviceDate = format("yyyy-MM-dd", serviceDay.timeInMillis)
        val profile = profile(serviceDay)
        return data.routes.flatMap { route ->
            val events = route.events.filter { it.stopId == stopId }
            profile.departures[route.id].orEmpty().flatMapIndexed { index, departure ->
                events.map { event ->
                    val minute = departure + event.offsetMinutes
                    ScheduleHit("$serviceDate/${route.id}/$index/${event.id}",
                        route.number, event.direction, arrivalMillis(serviceDay, minute),
                        TripReference(serviceDay.timeInMillis, route.id, index))
                }
            }
        }
    }

    private fun arrivalMillis(serviceDay: Calendar, minute: Int): Long {
        val arrival = serviceDay.clone() as Calendar
        // Встановлюємо місцевий час, зберігаючи перехід через опівніч і зміну часу.
        arrival.add(Calendar.DATE, minute / 1440)
        arrival.set(Calendar.HOUR_OF_DAY, minute % 1440 / 60)
        arrival.set(Calendar.MINUTE, minute % 60)
        return arrival.timeInMillis
    }

    /** Усі проходження саме вибраного рейсу. Фільтр напрямку сюди не застосовується. */
    fun tripSchedule(anchor: ScheduleHit): List<TripStopHit> {
        val reference = anchor.trip ?: return emptyList()
        val route = data.routes.find { it.id == reference.routeId } ?: return emptyList()
        val date = day(reference.serviceDayMillis)
        val departure = profile(date).departures[route.id]?.getOrNull(reference.departureIndex)
            ?: return emptyList()
        val serviceDate = format("yyyy-MM-dd", date.timeInMillis)
        val stops = data.stops.associateBy { it.id }
        return route.events.map { event ->
            val key = "$serviceDate/${route.id}/${reference.departureIndex}/${event.id}"
            TripStopHit(key, event.stopId, stops.getValue(event.stopId).name, event.direction,
                arrivalMillis(date, departure + event.offsetMinutes), key == anchor.key)
        }
    }

    private fun sorted(values: List<ScheduleHit>): List<ScheduleHit> =
        values.sortedWith(compareBy<ScheduleHit> { it.arrivalMillis }.thenBy { it.key })

    /** Список на календарний день, включаючи рейси попереднього дня після опівночі. */
    fun daySchedule(stopId: String, now: Long, filter: ScheduleFilter = ScheduleFilter()): List<ScheduleHit> {
        val start = day(now)
        val end = start.clone() as Calendar
        end.add(Calendar.DATE, 1)
        val all = (-3..0).flatMap { shift ->
            val date = start.clone() as Calendar
            date.add(Calendar.DATE, shift)
            hits(stopId, date)
        }
        return sorted(all.filter { filter.accepts(it) && it.arrivalMillis >= start.timeInMillis && it.arrivalMillis < end.timeInMillis })
    }

    /** anchor зберігає вибраний рейс під час перегляду, навіть коли поточний час змінився. */
    fun select(stopId: String, now: Long, offset: Int, anchor: ScheduleHit? = null,
        filter: ScheduleFilter = ScheduleFilter()): ScheduleHit? {
        val center = day(anchor?.arrivalMillis ?: now)
        val all = sorted((-7..7).flatMap { shift ->
            val date = center.clone() as Calendar
            date.add(Calendar.DATE, shift)
            hits(stopId, date)
        }.filter { filter.accepts(it) })
        val base = if (anchor != null) all.indexOfFirst { it.key == anchor.key }
        else all.indexOfFirst { it.arrivalMillis >= now - now % 60_000L }
        if (base < 0) return null
        val target = base.toLong() + offset
        return if (target in 0L until all.size.toLong()) all[target.toInt()] else null
    }
}

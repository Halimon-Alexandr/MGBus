package com.mgbus

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

private fun instant(text: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("Europe/Kiev")
}.parse(text)!!.time

fun main(args: Array<String>) {
    val text = File(args[0]).readText()
    val parsed = ScheduleParser.parse(text)
    val schedule = LocalSchedule(parsed)
    val bron = parsed.stops.first { it.name == "Бронниця" }.id
    val center = parsed.stops.first { it.name == "Центр, Укрпошта" }.id
    val hospital = parsed.stops.first { it.name == "Лікарня" }.id
    var checks = 0
    fun verify(condition: Boolean, label: String) {
        check(condition) { label }
        checks++
    }
    fun next(stop: String, date: String, offset: Int = 0): ScheduleHit =
        schedule.select(stop, instant(date), offset) ?: error("No hit: $date")

    verify(parsed.stops.size == 23 && parsed.routes.size == 2, "Data counts")
    verify(schedule.daySchedule(bron, instant("2026-11-09 12:00:00")).size == 45, "Weekday departures")
    verify(schedule.daySchedule(bron, instant("2026-11-07 12:00:00")).size == 40, "Weekend departures")
    verify(schedule.daySchedule(bron, instant("2025-04-20 12:00:00")).size == 19, "Historical holiday")
    verify(schedule.daySchedule(center, instant("2026-11-09 12:00:00")).size == 90, "Both directions at center")
    val weekday = next(bron, "2026-11-09 06:35:00")
    verify(weekday.routeNumber == "1" && schedule.arrivalText(weekday.arrivalMillis) == "06:45", "Weekday time")
    val weekend = next(bron, "2026-11-07 06:35:00")
    verify(weekend.routeNumber == "1" && schedule.arrivalText(weekend.arrivalMillis) == "06:50", "Weekend time")
    val evening = next(bron, "2026-11-09 18:42:00")
    verify(evening.routeNumber == "2" && schedule.arrivalText(evening.arrivalMillis) == "19:10", "Explicit route number late in day")
    val consecutive = schedule.select(bron, instant("2026-11-09 18:42:00"), 1, evening)!!
    verify(consecutive.routeNumber == "1" && schedule.arrivalText(consecutive.arrivalMillis) == "19:45", "Last weekday route")
    val tomorrow = next(bron, "2026-11-06 23:00:00")
    verify(tomorrow.arrivalMillis == instant("2026-11-07 05:20:00"), "Friday night to Saturday")
    verify(schedule.dateText(tomorrow.arrivalMillis, instant("2026-11-06 23:00:00")) == "Завтра", "Tomorrow label")
    val first = next(bron, "2026-11-07 05:00:00")
    val previous = schedule.select(bron, instant("2026-11-07 05:00:00"), -1, first)!!
    verify(previous.arrivalMillis == instant("2026-11-06 19:45:00"), "Previous day without circular wrap")
    verify(schedule.waitingText(previous.arrivalMillis, instant("2026-11-07 05:00:00")).startsWith("Рейс минув"), "Past time not wrapped to 24h")
    val stable = schedule.select(bron, instant("2026-11-07 09:00:00"), 1, first)!!
    verify(stable.arrivalMillis == instant("2026-11-07 05:40:00"), "Stable selection despite clock advancing")
    verify(next(bron, "2026-11-07 05:20:59").arrivalMillis == instant("2026-11-07 05:20:00"), "Current minute")
    verify(schedule.waitingText(instant("2026-11-07 05:20:00"), instant("2026-11-07 05:20:59")) == "Зараз", "Current-minute announcement")
    val h1 = next(hospital, "2026-11-09 05:00:00")
    val h2 = schedule.select(hospital, instant("2026-11-09 05:00:00"), 1, h1)!!
    verify(h1.routeNumber == "1" && schedule.arrivalText(h1.arrivalMillis) == "05:53", "Hospital route 1")
    verify(h2.routeNumber == "2" && schedule.arrivalText(h2.arrivalMillis) == "06:18", "Hospital route 2 reverse direction")
    TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    verify(next(bron, "2026-10-25 04:00:00").arrivalMillis == instant("2026-10-25 05:20:00"), "DST and foreign device timezone")
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val custom = parsed.copy(exceptions = parsed.exceptions + ("2026-11-09" to "holiday"))
    verify(LocalSchedule(custom).daySchedule(bron, instant("2026-11-09 12:00:00")).size == 19, "Date exception overrides Monday")
    val closed = parsed.profiles + ("closed" to ScheduleProfile("Рух відсутній", emptyMap()))
    val noService = LocalSchedule(parsed.copy(profiles = closed, exceptions = mapOf("2026-11-09" to "closed")))
    verify(noService.daySchedule(bron, instant("2026-11-09 12:00:00")).isEmpty(), "Closed day")
    verify(noService.select(bron, instant("2026-11-09 12:00:00"), 0)!!.arrivalMillis == instant("2026-11-10 05:20:00"), "Search beyond closed day")
    val crossingProfile = ScheduleProfile("Нічний", mapOf("1" to listOf(23 * 60 + 50)))
    val night = LocalSchedule(parsed.copy(profiles = mapOf("night" to crossingProfile),
        weekdays = "night", weekends = "night", exceptions = emptyMap()))
    val factory = parsed.stops.first { it.name == "Консервний завод" }.id
    verify(night.select(factory, instant("2026-11-07 00:10:00"), 0)!!.arrivalMillis == instant("2026-11-07 00:52:00"), "Yesterday's trip after midnight")
    verify(night.daySchedule(factory, instant("2026-11-07 12:00:00")).map { night.arrivalText(it.arrivalMillis) } == listOf("00:02", "00:52"), "Calendar day includes night arrivals")
    val badTime = text.replace("05:20", "25:99")
    verify(runCatching { ScheduleParser.parse(badTime) }.isFailure, "Reject corrupt time")
    val badSchema = JSONObject(text).put("schemaVersion", 99).toString()
    verify(runCatching { ScheduleParser.parse(badSchema) }.isFailure, "Reject unsupported schema")
    val badRef = text.replace("\"stop_01\"", "\"missing\"", ignoreCase = false)
        .replaceFirst("\"id\": \"missing\"", "\"id\": \"stop_01\"")
    verify(runCatching { ScheduleParser.parse(badRef) }.isFailure, "Reject unknown stop reference")
    val badDate = JSONObject(text).apply {
        getJSONObject("calendar").getJSONObject("exceptions").put("2026-02-30", "holiday")
    }.toString()
    verify(runCatching { ScheduleParser.parse(badDate) }.isFailure, "Reject impossible calendar date")
    verify(schedule.select("unknown", instant("2026-11-07 12:00:00"), 0) == null, "Unknown stop")
    verify(initialStop(listOf("Бронниця", "Ринок"), null) == "Бронниця", "First stop by default")
    verify(initialStop(listOf("Бронниця", "Ринок"), "Ринок") == "Ринок", "Saved stop wins")
    verify(initialStop(listOf("Бронниця", "Ринок"), "Missing") == "Бронниця", "Missing saved stop fallback")
    verify(initialStop(emptyList(), "Ринок") == null, "Empty stops fallback")
    val upper = parsed.stops.first { it.name == "Верхня Немія" }.id
    verify(schedule.directions(upper, "1") == listOf("Залізничного вокзалу"), "Route 1 direction at Nemiia")
    verify(schedule.directions(upper, "2") == listOf("Лікарні"), "Route 2 direction at Nemiia")
    verify(schedule.normalizeFilter(upper, ScheduleFilter("1", "Лікарні")) == ScheduleFilter("1", null), "Reset unavailable direction on route change")
    verify(schedule.normalizeFilter(bron, ScheduleFilter("2", "Лікарні")) == ScheduleFilter("2", null), "Reset unavailable direction on stop change")
    verify(schedule.normalizeFilter(center, ScheduleFilter("1", "Немії")) == ScheduleFilter("1", "Немії"), "Preserve valid direction")
    verify(schedule.normalizeFilter(center, ScheduleFilter("missing", "Немії")) == ScheduleFilter(null, "Немії"), "Unknown route normalized")
    val dayTime = instant("2026-11-09 12:00:00")
    val routeOne = ScheduleFilter("1")
    val directionOnly = ScheduleFilter(direction = "Немії")
    val combined = ScheduleFilter("2", "Бронниці")
    verify(schedule.daySchedule(center, dayTime, routeOne).size == 46, "Full day route filter")
    verify(schedule.daySchedule(center, dayTime, directionOnly).size == 45, "Full day direction filter")
    verify(schedule.daySchedule(center, dayTime, combined).size == 22, "Full day combined filter")
    val filtered = schedule.select(center, dayTime, 0, filter = combined)!!
    verify(filtered.routeNumber == "2" && filtered.direction == "Бронниці", "Nearest filtered hit")
    val afterFiltered = schedule.select(center, dayTime + 60_000, 1, filtered, combined)!!
    verify(afterFiltered.routeNumber == "2" && afterFiltered.direction == "Бронниці", "Next respects both filters")
    verify(afterFiltered.arrivalMillis > filtered.arrivalMillis, "Next advances filtered timeline")
    val beforeFiltered = schedule.select(center, dayTime + 600_000, -1, afterFiltered, combined)!!
    verify(beforeFiltered.key == filtered.key, "Previous reverses next even after clock advances")
    verify(schedule.select(bron, dayTime, 0, filter = ScheduleFilter("1", "Бронниці")) == null, "Impossible filter returns no result")
    verify(schedule.daySchedule(bron, dayTime, ScheduleFilter("1", "Бронниці")).isEmpty(), "Impossible filter full day empty")
    for (stop in parsed.stops) {
        for (route in listOf<String?>(null, "1", "2")) {
            val filter = ScheduleFilter(route)
            val hits = schedule.daySchedule(stop.id, dayTime, filter)
            verify(hits.all { filter.accepts(it) }, "All stops: route filter ${stop.id}/$route")
            verify(hits.zipWithNext().all { (a,b) -> a.arrivalMillis <= b.arrivalMillis }, "All stops sorted ${stop.id}/$route")
        }
    }
    val tripAnchor = schedule.select(center, dayTime, 0, filter = ScheduleFilter("1", "Немії"))!!
    val tripRows = schedule.tripSchedule(tripAnchor)
    verify(tripRows.first().stopName == "Бронниця", "Trip begins at initial stop")
    verify(tripRows.map { it.key }.distinct().size == tripRows.size, "Repeated stops have distinct keys")
    verify(tripRows.count { it.selected } == 1 && tripRows.first { it.selected }.key == tripAnchor.key, "Selected passage identified exactly")
    verify(tripRows.first { it.selected }.arrivalMillis == tripAnchor.arrivalMillis, "Anchor time unchanged")
    val prospect = tripRows.filter { it.stopName == "Проспект Незалежності" }
    verify(prospect.size == 2, "Prospect included twice")
    verify(prospect[0].direction == "Немії" && prospect[1].direction == "Бронниці", "Both prospect directions")
    verify(prospect[1].arrivalMillis - prospect[0].arrivalMillis == 34L * 60000, "Different times for repeated stop")
    verify(tripRows.zipWithNext().all { (a,b) -> a.arrivalMillis <= b.arrivalMillis }, "Trip in travel order")
    verify(tripRows.any { it.direction == "Бронниці" }, "Trip not truncated by direction filter")
    verify(tripRows.indexOfFirst { it.stopName == "Лікарня" } < tripRows.indexOfFirst { it.stopName == "Залізничний вокзал" }, "Route 1 hospital before station")
    val trip2Anchor = schedule.select(center, dayTime, 0, filter = ScheduleFilter("2"))!!
    val trip2 = schedule.tripSchedule(trip2Anchor)
    verify(trip2.indexOfFirst { it.stopName == "Залізничний вокзал" } < trip2.indexOfFirst { it.stopName == "Лікарня" }, "Route 2 station before hospital")
    val nextAnchor = schedule.select(center, dayTime, 1, tripAnchor, ScheduleFilter("1", "Немії"))!!
    verify(schedule.tripSchedule(nextAnchor).first().arrivalMillis != tripRows.first().arrivalMillis, "Adjacent trip has its own departure")
    val previousAnchor = schedule.select(center, dayTime, -1, tripAnchor, ScheduleFilter("1", "Немії"))!!
    verify(schedule.tripSchedule(previousAnchor).first().arrivalMillis < tripRows.first().arrivalMillis, "Previous trip keeps own departure")
    val returnAnchor = tripRows.first { it.stopName == "Проспект Незалежності" && it.direction == "Бронниці" }
    val returnHit = schedule.daySchedule(returnAnchor.stopId, dayTime).first { it.key == returnAnchor.key }
    verify(schedule.tripSchedule(returnHit).map { it.key } == tripRows.map { it.key }, "Return passage refers to same trip")
    val nightAnchor = night.select(factory, instant("2026-11-07 00:10:00"), 0)!!
    val nightRows = night.tripSchedule(nightAnchor)
    verify(nightRows.first().arrivalMillis == instant("2026-11-06 23:50:00"), "Night trip keeps previous service date")
    verify(nightRows.last().arrivalMillis == instant("2026-11-07 00:52:00"), "Night trip crosses midnight")
    verify(nightRows.first { it.selected }.arrivalMillis == nightAnchor.arrivalMillis, "Night selected passage exact")
    verify(schedule.tripSchedule(tripAnchor.copy(trip = null)).isEmpty(), "Missing trip identity handled")
    for (route in listOf("1", "2")) {
        for (date in listOf("2026-11-09 09:00:00", "2026-11-07 09:00:00", "2025-04-20 09:00:00")) {
            val anchor = schedule.select(center, instant(date), 0, filter = ScheduleFilter(route))!!
            val rows = schedule.tripSchedule(anchor)
            verify(rows.count { it.selected } == 1, "Identity on work/weekend/holiday $route/$date")
            verify(rows.first { it.selected }.arrivalMillis == anchor.arrivalMillis, "Time on work/weekend/holiday $route/$date")
        }
    }
    println("PASS: $checks checks. LocalSchedule.kt and ScheduleParser.kt tested on JVM.")
}

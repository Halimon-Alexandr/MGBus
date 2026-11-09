package com.mgbus

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

object ScheduleParser {
    private fun minutes(value: String): Int {
        require(Regex("[0-9]{2}:[0-5][0-9]").matches(value)) { "Некоректний час: $value" }
        val hour = value.substring(0, 2).toInt()
        require(hour in 0..47) { "Некоректна година: $value" }
        return hour * 60 + value.substring(3).toInt()
    }

    private fun validDate(value: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return false
        return runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
            parser.parse(value) != null
        }.getOrDefault(false)
    }

    fun parse(text: String): ScheduleData {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == 1) { "Непідтримуваний формат розкладу" }
        require(root.getString("timezone") == "Europe/Kyiv") { "Непідтримуваний часовий пояс" }
        val stopArray = root.getJSONArray("stops")
        val stops = List(stopArray.length()) { index ->
            val stop = stopArray.getJSONObject(index)
            ScheduleStop(stop.getString("id"), stop.getString("name"))
        }
        require(stops.isNotEmpty() && stops.all { it.id.isNotBlank() && it.name.isNotBlank() })
        require(stops.map { it.id }.distinct().size == stops.size)
        require(stops.map { it.name }.distinct().size == stops.size)
        val stopIds = stops.map { it.id }.toSet()
        val routeArray = root.getJSONArray("routes")
        val routes = List(routeArray.length()) { index ->
            val route = routeArray.getJSONObject(index)
            val array = route.getJSONArray("events")
            val events = List(array.length()) { eventIndex ->
                val event = array.getJSONObject(eventIndex)
                ScheduleEvent(event.getString("id"), event.getString("stopId"),
                    event.getString("direction"), event.getInt("offsetMinutes"))
            }
            require(events.isNotEmpty())
            require(events.map { it.id }.distinct().size == events.size)
            require(events.all { it.id.isNotBlank() && it.stopId in stopIds &&
                it.direction.isNotBlank() && it.offsetMinutes in 0..2880 })
            require(events.zipWithNext().all { (a, b) -> a.offsetMinutes <= b.offsetMinutes })
            ScheduleRoute(route.getString("id"), route.getString("number"), events)
        }
        require(routes.isNotEmpty() && routes.all { it.id.isNotBlank() && it.number.isNotBlank() })
        require(routes.map { it.id }.distinct().size == routes.size)
        val routeIds = routes.map { it.id }.toSet()
        val profileJson = root.getJSONObject("profiles")
        val profiles = profileJson.keys().asSequence().associateWith { key ->
            val profile = profileJson.getJSONObject(key)
            val departuresJson = profile.getJSONObject("departures")
            val departures = departuresJson.keys().asSequence().associateWith { routeId ->
                require(routeId in routeIds)
                val array = departuresJson.getJSONArray(routeId)
                val times = List(array.length()) { minutes(array.getString(it)) }
                require(times.distinct().size == times.size)
                require(times == times.sorted())
                times
            }
            ScheduleProfile(profile.getString("label"), departures)
        }
        val calendar = root.getJSONObject("calendar")
        val exceptionJson = calendar.getJSONObject("exceptions")
        val exceptions = exceptionJson.keys().asSequence().associateWith { date ->
            require(validDate(date))
            exceptionJson.getString(date).also { require(it in profiles) }
        }
        val weekdays = calendar.getString("weekdays")
        val weekends = calendar.getString("weekends")
        require(weekdays in profiles && weekends in profiles)
        val version = root.getInt("version")
        val updatedAt = root.getString("updatedAt")
        require(version > 0 && validDate(updatedAt))
        return ScheduleData(version, updatedAt, root.getString("timezone"), stops,
            routes, profiles, weekdays, weekends, exceptions)
    }
}

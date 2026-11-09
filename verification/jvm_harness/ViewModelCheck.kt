package com.mgbus
import android.app.Application
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

@OptIn(ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking {
    val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    Dispatchers.setMain(main)
    val app = Application(File(args[0]))
    val model = withContext(main) { BusViewModel(app) }
    var checks = 0
    fun verify(value: Boolean, label: String) { check(value) { label };checks++ }
    suspend fun settled() {
        withTimeout(10000) {
            while (model.state.value.loadingStops || model.state.value.loadingBus || model.state.value.selectedStop == null) delay(10)
        }
        // Завершуємо поточний прохід головного потоку, включно з публікацією оголошення.
        withContext(main) { }
    }
    suspend fun action(block: () -> Unit) { withContext(main) { block() };settled() }
    try {
        settled()
        verify(model.state.value.selectedStop == "Бронниця", "Default first stop")
        verify(model.state.value.announcement!!.text.contains("Зупинка: Бронниця."), "Startup speaks stop")
        verify(model.state.value.announcement!!.text.contains("Автобус номер"), "Startup speaks full result")
        val firstId = model.state.value.announcement!!.id
        action { model.refresh() }
        verify(model.state.value.announcement!!.id > firstId, "Refresh creates new event even for same result")
        action { model.selectRoute("2") }
        verify(model.state.value.bus!!.number == "2", "Selected route applied")
        action { model.selectStop("Верхня Немія") }
        verify(model.state.value.directions == listOf("Лікарні"), "Directions narrowed by route")
        action { model.selectDirection("Лікарні") }
        verify(model.state.value.bus!!.direction == "Лікарні", "Direction applied")
        action { model.selectRoute("1") }
        verify(model.state.value.filter == ScheduleFilter("1", null), "Unavailable direction reset")
        verify(model.state.value.announcement!!.text.startsWith("Напрямок скинуто"), "Reset announced")
        verify(model.state.value.bus!!.direction == "Залізничного вокзалу", "Correct opposite route")
        action { model.selectDirection("Залізничного вокзалу") }
        val key = model.state.value.bus!!.hit.key
        val displayedBus = model.state.value.bus!!
        withContext(main) { model.loadTripSchedule(displayedBus) }
        withTimeout(10000) { while (model.state.value.loadingTrip) delay(10) }
        val openedTrip = model.state.value.tripSchedule!!
        verify(openedTrip.number == displayedBus.number, "Trip screen captures displayed route")
        verify(openedTrip.entries.count { it.selected } == 1 && openedTrip.entries.first { it.selected }.key == key,
            "Trip screen captures displayed passage")
        verify(openedTrip.entries.count { it.stopName == "Проспект Незалежності" } == 2, "Trip screen keeps repeated stops")
        verify(openedTrip.entries.any { it.direction == "Бронниці" }, "Trip screen includes unfiltered return direction")
        action { model.next() }
        verify(model.state.value.tripSchedule == openedTrip, "Trip screen remains pinned after main card changes")
        withContext(main) { model.loadTripSchedule() }
        withTimeout(10000) { while (model.state.value.loadingTrip) delay(10) }
        verify(model.state.value.tripSchedule == openedTrip, "Retry preserves captured trip")
        verify(model.state.value.bus!!.hit.key != key && model.state.value.bus!!.number == "1", "Next filters")
        action { model.previous() }
        verify(model.state.value.bus!!.hit.key == key, "Previous returns selected hit")
        val beforeTick = model.state.value.announcement!!.id
        action { model.tick() }
        verify(model.state.value.announcement!!.id == beforeTick, "Timer emits no announcement")
        verify(model.state.value.bus!!.hit.key == key, "Timer retains browsed hit")
        withContext(main) { model.loadDaySchedule() }
        withTimeout(10000) { while (model.state.value.loadingDay) delay(10) }
        verify(model.state.value.dayEntries.isNotEmpty() && model.state.value.dayEntries.all {
            it.number == "1" && it.direction == "Залізничного вокзалу"
        }, "Full day filters")
        action { model.selectStop("Центр, Укрпошта") }
        verify(model.state.value.filter.direction == null, "Changing stop resets incompatible direction")
        action { model.selectDirection("Немії") }
        action { model.selectStop("Нижній магазин") }
        verify(model.state.value.filter.direction == "Немії", "Changing stop preserves compatible direction")
        withContext(main) {
            model.selectRoute("1")
            model.selectRoute("2")
            model.selectStop("Верхня Немія")
        }
        settled()
        verify(model.state.value.selectedStop == "Верхня Немія" && model.state.value.bus!!.number == "2", "Latest selection wins")
        verify(model.state.value.announcement!!.text.contains("Зупинка: Верхня Немія."), "No outdated result announced")
        val restored = withContext(main) { BusViewModel(app) }
        withTimeout(10000) { while (restored.state.value.bus == null) delay(10) }
        withContext(main) { }
        verify(restored.state.value.selectedStop == "Верхня Немія", "Saved stop restored")
        verify(restored.state.value.announcement!!.key != model.state.value.announcement!!.key, "New session cannot suppress startup after process recreation")
        withContext(main) { restored.scope.cancel() }
        println("PASS: $checks ViewModel checks with JVM platform doubles; Android UI not exercised.")
    } finally {
        withContext(main) { model.scope.cancel() }
        Dispatchers.resetMain()
        main.close()
    }
}

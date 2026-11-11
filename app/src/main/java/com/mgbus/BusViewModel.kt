package com.mgbus

import android.app.Application
import java.util.UUID
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BusAnnouncement(val id: Long, val text: String, val session: String) {
    val key: String get() = "$session/$id"
}

data class BusUiState(
    val stops: List<String> = emptyList(),
    val selectedStop: String? = null,
    val routes: List<String> = emptyList(),
    val directions: List<String> = emptyList(),
    val filter: ScheduleFilter = ScheduleFilter(),
    val bus: BusInfo? = null,
    val loadingStops: Boolean = false,
    val loadingBus: Boolean = false,
    val stopsError: String? = null,
    val message: String = "",
    val updatedAt: String? = null,
    val stale: Boolean = false,
    val dayEntries: List<BusInfo> = emptyList(),
    val dayLabel: String = "",
    val loadingDay: Boolean = false,
    val dayError: String? = null,
    val announcement: BusAnnouncement? = null,
    val tripSchedule: TripScheduleInfo? = null,
    val loadingTrip: Boolean = false,
    val tripError: String? = null
)

class BusViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BusRepository(application)
    private val mutableState = MutableStateFlow(BusUiState())
    val state = mutableState.asStateFlow()
    private var busJob: Job? = null
    private var stopsJob: Job? = null
    private var dayJob: Job? = null
    private var tripJob: Job? = null
    private var tripAnchor: BusInfo? = null
    private var followNearest = true
    private var foregroundObserved = false
    private var announcementId = 0L
    private val announcementSession = UUID.randomUUID().toString()

    init {
        loadStops()
        val initialLoad = stopsJob
        viewModelScope.launch {
            if (repository.updateSchedule()) {
                initialLoad?.join()
                applyUpdatedSchedule()
            }
        }
    }

    fun clearAnnouncement() { mutableState.update { it.copy(announcement = null) } }

    private fun announce(text: String) {
        val event = BusAnnouncement(++announcementId, text, announcementSession)
        mutableState.update { it.copy(announcement = event) }
    }

    fun loadStops() {
        if (stopsJob?.isActive == true) return
        stopsJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingStops = true, stopsError = null) }
            try {
                val stops = repository.stops()
                val routes = repository.routeNumbers()
                ensureActive()
                mutableState.update { it.copy(stops = stops, routes = routes, loadingStops = false) }
                val chosen = initialStop(stops, mutableState.value.selectedStop ?: repository.lastStop())
                if (chosen == null) {
                    val error = "Зупинки недоступні: список розкладу порожній."
                    mutableState.update { it.copy(stopsError = error) }
                    announce(error)
                } else {
                    selectStop(chosen)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val error = errorText(e)
                mutableState.update { it.copy(loadingStops = false, stopsError = error) }
                announce(error)
            }
        }
    }

    fun selectStop(stop: String) {
        if (stop !in mutableState.value.stops) return
        repository.saveStop(stop)
        changeSelection(stop, mutableState.value.filter)
    }

    fun selectRoute(route: String?) {
        val current = mutableState.value
        val stop = current.selectedStop ?: return
        if (route != null && route !in current.routes) return
        changeSelection(stop, current.filter.copy(routeNumber = route))
    }

    fun selectDirection(direction: String?) {
        val current = mutableState.value
        val stop = current.selectedStop ?: return
        if (direction != null && direction !in current.directions) return
        changeSelection(stop, current.filter.copy(direction = direction))
    }

    private suspend fun applyUpdatedSchedule() {
        val stops = repository.stops()
        val routes = repository.routeNumbers()
        val previous = mutableState.value
        val chosen = initialStop(stops, previous.selectedStop ?: repository.lastStop())
        mutableState.update {
            it.copy(stops = stops, routes = routes, loadingStops = false, stopsError = null)
        }
        if (chosen != null) changeSelection(chosen, previous.filter, announceResult = false)
    }

    private fun changeSelection(
        stop: String,
        requested: ScheduleFilter,
        announceResult: Boolean = true,
        preserveCurrentBus: Boolean = true
    ) {
        busJob?.cancel()
        dayJob?.cancel()
        followNearest = true
        val sameStop = mutableState.value.selectedStop == stop
        mutableState.update {
            it.copy(selectedStop = stop, filter = requested,
                bus = it.bus.takeIf { sameStop && preserveCurrentBus }, loadingBus = true,
                message = "", stale = false,
                updatedAt = it.updatedAt.takeIf { sameStop && preserveCurrentBus },
                announcement = if (announceResult) null else it.announcement,
                directions = if (sameStop) it.directions else emptyList(),
                dayEntries = emptyList(), dayLabel = "", dayError = null, loadingDay = false)
        }
        busJob = viewModelScope.launch {
            try {
                val options = repository.options(stop, requested)
                val bus = repository.bus(stop, options.filter, 0)
                val time = repository.calculationTime()
                ensureActive()
                mutableState.update {
                    it.copy(filter = options.filter, directions = options.directions, bus = bus,
                        loadingBus = false, updatedAt = time,
                        message = if (bus == null) "Для вибраних умов рейсів не знайдено." else "")
                }
                val reset = if (requested.direction != null && options.filter.direction == null)
                    "Напрямок скинуто: усі напрямки. " else ""
                if (announceResult) announce(reset + resultText(stop, bus))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showBusError(e, silent = !announceResult) }
        }
    }

    fun refresh() {
        followNearest = true
        requestBus(0)
    }

    /**
     * A retained ViewModel can still contain a route filter or a manually browsed trip when the
     * activity returns from the background. Keep the saved stop, but start a new foreground
     * session from the nearest bus across every route and direction.
     */
    fun onAppForegrounded() {
        if (!foregroundObserved) {
            foregroundObserved = true
            return
        }
        val stop = mutableState.value.selectedStop ?: return
        clearAnnouncement()
        changeSelection(stop, ScheduleFilter(), announceResult = false, preserveCurrentBus = false)
    }

    fun previous() = adjacent(-1)
    fun next() = adjacent(1)
    private fun adjacent(step: Int) {
        val current = mutableState.value
        val anchor = current.bus ?: return
        if (current.loadingBus) return
        followNearest = false
        requestBus(step, anchor)
    }

    fun tick() {
        val current = mutableState.value
        if (current.selectedStop == null || current.loadingBus || current.stale) return
        requestBus(0, if (followNearest) null else current.bus, silent = true)
    }

    fun loadDaySchedule(silent: Boolean = false) {
        val current = mutableState.value
        val stop = current.selectedStop ?: return
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingDay = !silent, dayError = null) }
            try {
                val day = repository.today(stop, current.filter)
                ensureActive()
                mutableState.update { it.copy(loadingDay = false, dayEntries = day.entries, dayLabel = day.label) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val error = errorText(e)
                mutableState.update { it.copy(loadingDay = false, dayError = error) }
                if (!silent) announce(error)
            }
        }
    }

    /** Фіксуємо рейс із картки в момент натискання, а не шукаємо новий найближчий. */
    fun loadTripSchedule(bus: BusInfo? = null) {
        val anchor = bus ?: tripAnchor
        if (anchor == null) {
            mutableState.update { it.copy(loadingTrip = false,
                tripError = "Рейс недоступний. Поверніться до головного екрана й відкрийте його знову.") }
            return
        }
        tripAnchor = anchor
        tripJob?.cancel()
        clearAnnouncement()
        mutableState.update { it.copy(tripSchedule = null, loadingTrip = true, tripError = null) }
        tripJob = viewModelScope.launch {
            try {
                val trip = repository.trip(anchor)
                ensureActive()
                mutableState.update { it.copy(loadingTrip = false, tripSchedule = trip,
                    tripError = if (trip.entries.isEmpty()) "Не вдалося знайти зупинки вибраного рейсу." else null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val error = "Не вдалося прочитати розклад вибраного рейсу."
                mutableState.update { it.copy(loadingTrip = false, tripError = error) }
                announce(error)
            }
        }
    }

    private fun requestBus(step: Int, anchor: BusInfo? = null, silent: Boolean = false) {
        val current = mutableState.value
        val stop = current.selectedStop ?: return
        busJob?.cancel()
        busJob = viewModelScope.launch {
            mutableState.update { it.copy(loadingBus = true,
                announcement = if (silent) it.announcement else null,
                message = if (silent) it.message else "") }
            try {
                val bus = repository.bus(stop, current.filter, step, anchor)
                val time = repository.calculationTime()
                ensureActive()
                if (bus == null && anchor != null) {
                    val message = "Інших рейсів у доступному діапазоні немає. Залишено вибраний рейс."
                    mutableState.update { it.copy(loadingBus = false, message = message) }
                    if (!silent) announce(message)
                } else {
                    mutableState.update { it.copy(bus = bus, loadingBus = false, stale = false,
                        updatedAt = time, message = if (bus == null) "Для вибраних умов рейсів не знайдено." else "") }
                    if (!silent) announce(resultText(stop, bus))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showBusError(e, silent) }
        }
    }

    private fun resultText(stop: String, bus: BusInfo?): String =
        "Зупинка: $stop. " + (bus?.announcement ?: "Для вибраних умов рейсів не знайдено.")

    private fun showBusError(error: Exception, silent: Boolean) {
        val message = errorText(error)
        mutableState.update { it.copy(loadingBus = false, stale = it.bus != null, message = message) }
        if (!silent) announce(message)
    }

    private fun errorText(error: Exception): String =
        if (error is java.io.FileNotFoundException) "Не знайдено файл локального розкладу."
        else "Не вдалося прочитати локальний розклад."
}

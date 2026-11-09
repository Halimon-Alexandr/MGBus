package com.mgbus

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BusTheme {
                val model: BusViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                BusApp(state, model)
            }
        }
    }
}

@Composable
private fun BusTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF9CCAFF), secondary = Color(0xFF80D5CC))
    } else {
        lightColorScheme(primary = Color(0xFF155DA8), secondary = Color(0xFF006B61))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Один запит на оголошення: окремо від текстових рядків і без зміни фокусу. */
@Suppress("DEPRECATION")
private fun speakResult(view: View, text: String) {
    val manager = view.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (manager?.isEnabled == true && view.isShown) {
        // Дозволяє повторити ідентичний результат після явного «Оновити».
        // Не створюємо невидимих фокусованих елементів або дубльованих описів картки.
        view.announceForAccessibility(text)
    }
}

@Composable
private fun ResultAnnouncements(event: BusAnnouncement?, ready: Boolean) {
    val view = LocalView.current
    var deliveredKey by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(event?.key, ready, view) {
        if (event == null || !ready || event.key == deliveredKey) return@LaunchedEffect
        // Чекаємо закриття діалогу й відновлення його початкової кнопки.
        withFrameNanos { }
        delay(450)
        speakResult(view, event.text)
        deliveredKey = event.key
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusApp(state: BusUiState, model: BusViewModel) {
    // Діалоги залишають головний екран у дереві, щоб зберігати місце фокусу.
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var showingDay by rememberSaveable { mutableStateOf(false) }
    var showingTrip by rememberSaveable { mutableStateOf(false) }
    val dayScreenOpen by rememberUpdatedState(showingDay)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()

    ResultAnnouncements(state.announcement,
        picker == null && lifecycleState.isAtLeast(Lifecycle.State.STARTED))

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.tick()
            if (dayScreenOpen) model.loadDaySchedule(silent = true)
            while (isActive) {
                delay(30_000)
                model.tick()
                if (dayScreenOpen) model.loadDaySchedule(silent = true)
            }
        }
    }
    BackHandler(enabled = (showingDay || showingTrip) && picker == null) {
        showingDay = false
        showingTrip = false
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            Text(if (showingTrip) "Розклад поточного рейсу" else if (showingDay) "Розклад на сьогодні" else "MGBus",
                modifier = Modifier.semantics { heading() })
        })
    }) { padding ->
        if (showingTrip) {
            TripScheduleScreen(state, Modifier.padding(padding),
                onBack = { showingTrip = false }, onRetry = { model.loadTripSchedule() })
        } else if (showingDay) {
            DayScheduleScreen(state, Modifier.padding(padding),
                onBack = { showingDay = false }, onRetry = { model.loadDaySchedule() })
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Могилів-Подільський", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(
                    onClick = { picker = "stop" }, enabled = state.stops.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text(state.selectedStop?.let { "Зупинка: $it" } ?: "Обрати зупинку") }

                if (state.loadingStops) Text("Завантаження розкладу…")
                state.stopsError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = model::loadStops) { Text("Повторити") }
                }

                if (state.selectedStop != null) {
                    // Рядки фільтрів залишаються навіть без знайденого рейсу.
                    // Положення елементів стабільне під час зміни фільтра й завантаження.
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val routeText = (state.bus?.number ?: state.filter.routeNumber)
                                ?.let { "Автобус № $it" } ?: "Автобус"
                            val directionText = (state.bus?.direction ?: state.filter.direction)
                                ?.let { "У напрямку $it" } ?: "Напрямок"
                            FilterResultRow(routeText, MaterialTheme.typography.headlineMedium,
                                "Змінити маршрут", onClick = { picker = "route" })
                            FilterResultRow(directionText, MaterialTheme.typography.titleLarge,
                                "Змінити напрямок", onClick = { picker = "direction" })
                            state.bus?.let { bus ->
                                Text("Прибуття: ${bus.arrivalDate.lowercase()}, о ${bus.arrival}",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                                Text("Час очікування: ${bus.timeLeft}",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                                if (state.stale) Text("Показано попередні дані",
                                    modifier = Modifier.padding(horizontal = 8.dp))
                            }
                            val filterSummary = if (state.filter.routeNumber == null && state.filter.direction == null) {
                                "Усі маршрути й напрямки"
                            } else {
                                val route = state.filter.routeNumber?.let { "Лише № $it" } ?: "Усі маршрути"
                                val direction = state.filter.direction?.let { "лише до $it" } ?: "усі напрямки"
                                "$route · $direction"
                            }
                            Text(filterSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }

                if (state.message.isNotBlank()) Text(state.message)

                if (state.selectedStop != null) {
                    OutlinedButton(
                        onClick = { model.clearAnnouncement(); showingDay = true; model.loadDaySchedule() },
                        enabled = !state.loadingBus,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Повний розклад на сьогодні") }
                    OutlinedButton(
                        onClick = {
                            state.bus?.let { bus ->
                                model.loadTripSchedule(bus)
                                showingTrip = true
                            }
                        },
                        enabled = state.bus != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Розклад поточного рейсу") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = model::previous,
                            enabled = state.bus != null,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(painterResource(R.drawable.ic_previous_trip),
                                contentDescription = "Попередній рейс", modifier = Modifier.size(28.dp))
                        }
                        FilledIconButton(onClick = model::refresh, modifier = Modifier.size(56.dp)) {
                            Icon(painterResource(R.drawable.ic_refresh_schedule),
                                contentDescription = "Оновити", modifier = Modifier.size(28.dp))
                        }
                        FilledTonalIconButton(
                            onClick = model::next,
                            enabled = state.bus != null,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(painterResource(R.drawable.ic_next_trip),
                                contentDescription = "Наступний рейс", modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }

    when (picker) {
        "stop" -> StopPicker(state.stops, state.selectedStop,
            onDismiss = { picker = null }, onSelect = { picker = null; model.selectStop(it) })
        "route" -> ChoicePicker(
            title = "Оберіть маршрут",
            choices = listOf(null to "Усі маршрути") + state.routes.map { it to "Маршрут № $it" },
            selected = state.filter.routeNumber,
            onDismiss = { picker = null }, onSelect = { picker = null; model.selectRoute(it) }
        )
        "direction" -> ChoicePicker(
            title = "Оберіть напрямок",
            choices = listOf(null to "Усі напрямки") + state.directions.map { it to "У напрямку $it" },
            selected = state.filter.direction,
            onDismiss = { picker = null }, onSelect = { picker = null; model.selectDirection(it) }
        )
    }
}

/** Увесь рядок — одна кнопка доступності; стрілка декоративна. */
@Composable
private fun FilterResultRow(
    text: String,
    style: TextStyle,
    actionLabel: String,
    onClick: () -> Unit
) {
    val arrowColor = MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = text, style = style, modifier = Modifier.weight(1f))
        Canvas(Modifier.size(20.dp).clearAndSetSemantics { }) {
            val stroke = 2.dp.toPx()
            drawLine(arrowColor, Offset(size.width * 0.2f, size.height * 0.35f),
                Offset(size.width * 0.5f, size.height * 0.65f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(arrowColor, Offset(size.width * 0.5f, size.height * 0.65f),
                Offset(size.width * 0.8f, size.height * 0.35f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun TripScheduleScreen(
    state: BusUiState,
    modifier: Modifier,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextButton(onClick = onBack) { Text("Назад до розкладу") }
        val trip = state.tripSchedule
        if (trip != null) {
            Text("Автобус № ${trip.number}", style = MaterialTheme.typography.titleLarge)
            Text("Дата рейсу: ${trip.date}", modifier = Modifier.padding(vertical = 8.dp))
        }
        if (state.loadingTrip) Text("Завантаження розкладу…")
        state.tripError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Повторити") }
        }
        if (!state.loadingTrip && state.tripError == null && trip == null) {
            Text("Рейс недоступний. Поверніться до головного екрана й відкрийте його знову.")
        }
        LazyColumn(Modifier.weight(1f)) {
            // Ключ — проходження рейсу, а не назва зупинки: повтори не об'єднуються.
            items(trip?.entries.orEmpty(), key = { it.key }) { stop ->
                val spoken = buildString {
                    if (stop.selected) append("Вибране проходження зупинки. ")
                    append("${stop.stopName}. ")
                    if (stop.arrivalDate.isNotBlank()) append("${stop.arrivalDate}. ")
                    append("Прибуття о ${stop.arrival}. У напрямку ${stop.direction}.")
                }
                ListItem(
                    headlineContent = { Text(stop.stopName) },
                    supportingContent = {
                        Column {
                            Text(if (stop.arrivalDate.isBlank()) "Прибуття о ${stop.arrival}"
                                else "${stop.arrivalDate}, о ${stop.arrival}")
                            Text("У напрямку ${stop.direction}")
                            if (stop.selected) Text("Вибране проходження")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = if (stop.selected)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DayScheduleScreen(state: BusUiState, modifier: Modifier, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextButton(onClick = onBack) { Text("Назад до розкладу") }
        Text(state.selectedStop.orEmpty(), style = MaterialTheme.typography.titleMedium)
        Text("Маршрут: ${state.filter.routeNumber ?: "усі"}. Напрямок: ${state.filter.direction ?: "усі напрямки"}.")
        Text(state.dayLabel, modifier = Modifier.padding(vertical = 8.dp))
        if (state.loadingDay) Text("Завантаження розкладу…")
        state.dayError?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("Повторити") }
        }
        if (!state.loadingDay && state.dayError == null && state.dayEntries.isEmpty()) {
            Text("На сьогодні для вибраних умов рейсів немає.")
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.dayEntries, key = { it.hit.key }) { bus ->
                ListItem(
                    headlineContent = { Text("${bus.arrival} — автобус № ${bus.number}") },
                    supportingContent = { Text("У напрямку ${bus.direction}") },
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                        contentDescription = "Прибуття о ${bus.arrival}. Автобус номер ${bus.number}. У напрямку ${bus.direction}."
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
    }
}

@Composable
private fun ChoicePicker(title: String, choices: List<Pair<String?, String>>, selected: String?,
    onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()).selectableGroup()) {
                choices.forEach { (value, label) -> ChoiceRow(label, value == selected) { onSelect(value) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@Composable
private fun StopPicker(stops: List<String>, selected: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(stops, query) { stops.filter { it.contains(query.trim(), ignoreCase = true) } }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Оберіть зупинку") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    label = { Text("Пошук зупинки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                if (filtered.isEmpty()) Text("Зупинок за цим запитом не знайдено.")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).selectableGroup()) {
                    items(filtered, key = { it }) { stop -> ChoiceRow(stop, stop == selected) { onSelect(stop) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

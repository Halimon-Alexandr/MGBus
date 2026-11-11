package com.mgbus

import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9CCAFF), secondary = Color(0xFF80D5CC))
        else -> lightColorScheme(
            primary = Color(0xFF155DA8),
            secondary = Color(0xFF006B61),
            background = Color(0xFFF7F9FC),
            surface = Color(0xFFF7F9FC)
        )
    }
    val shapes = Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp)
    )
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            model.onAppForegrounded()
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
        CenterAlignedTopAppBar(title = {
            if (!showingTrip && !showingDay) {
                Column(
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                        contentDescription = "Могилів-Подільський. Графік руху автобусів"
                        heading()
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Могилів-Подільський", style = MaterialTheme.typography.titleLarge)
                    Text("Графік руху автобусів", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    if (showingTrip) "Розклад поточного рейсу" else "Розклад на сьогодні",
                    modifier = Modifier.semantics { heading() }
                )
            }
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
                StopSelectionCard(
                    stop = state.selectedStop,
                    enabled = state.stops.isNotEmpty(),
                    onClick = { picker = "stop" }
                )

                state.stopsError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = model::loadStops) { Text("Повторити") }
                }

                if (state.selectedStop != null) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.filter.routeNumber != null,
                            onClick = { picker = "route" },
                            label = { Text("Маршрут: ${state.filter.routeNumber ?: "усі"}") },
                            colors = scheduleFilterColors()
                        )
                        FilterChip(
                            selected = state.filter.direction != null,
                            onClick = { picker = "direction" },
                            label = { Text("Напрямок: ${state.filter.direction ?: "усі"}") },
                            colors = scheduleFilterColors()
                        )
                    }

                    state.bus?.let { bus -> BusResultCard(bus, state.stale) }
                }

                if (state.message.isNotBlank()) Text(state.message)

                if (state.selectedStop != null) {
                    NavigationActionCard(
                        title = "Повний розклад на сьогодні",
                        subtitle = "Усі рейси вибраної зупинки",
                        onClick = { model.clearAnnouncement(); showingDay = true; model.loadDaySchedule() },
                        enabled = true
                    )
                    NavigationActionCard(
                        title = "Розклад поточного рейсу",
                        subtitle = "Усі зупинки вибраного автобуса",
                        onClick = {
                            state.bus?.let { bus ->
                                model.loadTripSchedule(bus)
                                showingTrip = true
                            }
                        },
                        enabled = state.bus != null
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TripNavigationButton(
                            label = "Попередній",
                            iconRes = R.drawable.ic_previous_trip,
                            onClick = model::previous,
                            enabled = state.bus != null,
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
                        TripNavigationButton(
                            label = "Оновити",
                            iconRes = R.drawable.ic_refresh_schedule,
                            onClick = model::refresh,
                            enabled = true,
                            primary = true,
                            modifier = Modifier.weight(1f)
                        )
                        TripNavigationButton(
                            label = "Наступний",
                            iconRes = R.drawable.ic_next_trip,
                            onClick = model::next,
                            enabled = state.bus != null,
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
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

@Composable
private fun StopSelectionCard(stop: String?, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Змінити зупинку", onClick = onClick)
            .semantics(mergeDescendants = true) { },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Зупинка", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(stop ?: "Оберіть зупинку", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            RightChevron(MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BusResultCard(bus: BusInfo, stale: Boolean) {
    val spoken = buildString {
        append(bus.announcement)
        if (stale) append(" Показано попередні дані.")
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Прибуття", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                bus.arrival,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "${bus.arrivalDate} · Автобус № ${bus.number}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "У напрямку ${bus.direction}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Час очікування", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(bus.timeLeft, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (stale) {
                Text("Показано попередні дані", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun scheduleFilterColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
    disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
)

@Composable
private fun NavigationActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = contentColor,
        tonalElevation = 1.dp
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = if (enabled) 0.72f else 1f))
            }
            RightChevron(contentColor)
        }
    }
}

@Composable
private fun TripNavigationButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    primary: Boolean,
    modifier: Modifier = Modifier
) {
    val tonalColors = if (isSystemInDarkTheme()) {
        IconButtonDefaults.filledTonalIconButtonColors()
    } else {
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (primary) {
            FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
                Icon(painterResource(iconRes), contentDescription = label, modifier = Modifier.size(28.dp))
            }
        } else {
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                colors = tonalColors,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(painterResource(iconRes), contentDescription = label, modifier = Modifier.size(28.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { })
    }
}

@Composable
private fun RightChevron(color: Color) {
    Canvas(Modifier.size(20.dp).clearAndSetSemantics { }) {
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(size.width * 0.35f, size.height * 0.2f),
            Offset(size.width * 0.65f, size.height * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.65f, size.height * 0.5f),
            Offset(size.width * 0.35f, size.height * 0.8f), strokeWidth = stroke, cap = StrokeCap.Round)
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

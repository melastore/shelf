package io.github.melastore.shelf.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.CalculatorOperation
import io.github.melastore.shelf.data.CalculatorState
import io.github.melastore.shelf.data.CalendarEvent
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.Habit
import io.github.melastore.shelf.data.currentStreak
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun DecoyScreen(state: AppUiState, viewModel: ShelfViewModel, onSecretEntry: () -> Unit) {
	when (state.decoy) {
		DecoyType.HABITS -> HabitDecoy(state, viewModel, onSecretEntry)
		DecoyType.CALENDAR -> CalendarDecoy(state, viewModel, onSecretEntry)
		DecoyType.CALCULATOR -> CalculatorDecoy(state.entryMethod, onSecretEntry)
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HabitDecoy(state: AppUiState, viewModel: ShelfViewModel, onSecretEntry: () -> Unit) {
	var entry by remember { mutableStateOf("") }
	var today by remember { mutableStateOf(LocalDate.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(60_000)
			today = LocalDate.now()
		}
	}
	val completedToday = state.habits.count { today.toString() in it.checkedDates }

	Box(Modifier.fillMaxSize()) {
		Scaffold(
			topBar = {
				TopAppBar(title = {
					SecretTitle("Momento", state.entryMethod, onSecretEntry)
				})
			},
		) { padding ->
			LazyColumn(
				modifier = Modifier.padding(padding).fillMaxSize(),
				contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				item {
					Text(
						today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.SemiBold,
					)
					Text(
						stringResource(R.string.habit_day_prompt),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				item {
					Card(
						modifier = Modifier.fillMaxWidth().secretHold(
							state.entryMethod == EntryMethod.NATURAL_HOLD,
							onSecretEntry,
						),
						colors = CardDefaults.cardColors(
							containerColor = MaterialTheme.colorScheme.primaryContainer,
						),
					) {
						Column(Modifier.padding(20.dp)) {
							Text(
								"$completedToday / ${state.habits.size}",
								style = MaterialTheme.typography.headlineMedium,
								fontWeight = FontWeight.Bold,
							)
							Text(stringResource(R.string.completed_today))
						}
					}
				}
				item {
					Row(verticalAlignment = Alignment.CenterVertically) {
						OutlinedTextField(
							value = entry,
							onValueChange = { entry = it },
							modifier = Modifier.weight(1f),
							singleLine = true,
							label = { Text(stringResource(R.string.add_habit)) },
							keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
							keyboardActions = KeyboardActions(onDone = {
								viewModel.submitHabit(entry)
								entry = ""
							}),
						)
						IconButton(
							onClick = {
								viewModel.submitHabit(entry)
								entry = ""
							},
							enabled = entry.isNotBlank(),
						) { Icon(Icons.Filled.Add, stringResource(R.string.add_habit)) }
					}
				}
				if (state.habits.isEmpty()) {
					item { EmptyState(stringResource(R.string.start_small), stringResource(R.string.habits_empty)) }
				} else {
					items(state.habits, key = { it.id }) { habit ->
						HabitCard(habit, today, viewModel)
					}
				}
			}
		}
		CornerKnockTarget(
			state.entryMethod,
			onSecretEntry,
			Modifier.align(Alignment.TopEnd).statusBarsPadding(),
		)
	}
}

@Composable
private fun HabitCard(habit: Habit, today: LocalDate, viewModel: ShelfViewModel) {
	val week = remember(today) { (6L downTo 0L).map(today::minusDays) }
	Card(Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text(habit.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
					val streak = currentStreak(habit.checkedDates, today)
					Text(
						pluralStringResource(R.plurals.streak_format, streak, streak),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				IconButton(onClick = { viewModel.removeHabit(habit) }) {
					Icon(Icons.Filled.Delete, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.error)
				}
			}
			Spacer(Modifier.height(12.dp))
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				week.forEach { date ->
					DayBubble(
						date,
						date.toString() in habit.checkedDates,
					) { viewModel.toggleHabit(habit, date.toString()) }
				}
			}
		}
	}
}

@Composable
private fun DayBubble(date: LocalDate, checked: Boolean, onClick: () -> Unit) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Text(
			date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
			style = MaterialTheme.typography.labelSmall,
		)
		Spacer(Modifier.height(4.dp))
		Box(
			Modifier.size(38.dp).clip(CircleShape).background(
				if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
			).combinedClickable(onClick = onClick),
			contentAlignment = Alignment.Center,
		) {
			Text(
				date.dayOfMonth.toString(),
				color = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CalendarDecoy(state: AppUiState, viewModel: ShelfViewModel, onSecretEntry: () -> Unit) {
	var month by remember { mutableStateOf(YearMonth.now()) }
	var selected by remember { mutableStateOf(LocalDate.now()) }
	var showAdd by remember { mutableStateOf(false) }
	val selectedEvents = state.calendarEvents.filter { it.date == selected.toString() }

	Box(Modifier.fillMaxSize()) {
		Scaffold(
			topBar = { TopAppBar(title = { SecretTitle("Calendar", state.entryMethod, onSecretEntry) }) },
			floatingActionButton = {
				FloatingActionButton(onClick = { showAdd = true }) {
					Icon(Icons.Filled.Add, stringResource(R.string.add_event))
				}
			},
		) { padding ->
			LazyColumn(
				Modifier.padding(padding).fillMaxSize(),
				contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				item {
					Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
						IconButton(onClick = {
							month = month.minusMonths(1)
							selected = month.atDay(1)
						}) {
							Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_month))
						}
						Text(
							month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
							modifier = Modifier.weight(1f),
							style = MaterialTheme.typography.titleLarge,
							fontWeight = FontWeight.SemiBold,
						)
						IconButton(onClick = {
							month = month.plusMonths(1)
							selected = month.atDay(1)
						}) {
							Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_month))
						}
					}
					MonthGrid(month, selected) {
						selected = it
						month = YearMonth.from(it)
					}
				}
				item {
					Column(Modifier.secretHold(state.entryMethod == EntryMethod.NATURAL_HOLD, onSecretEntry)) {
						Text(
							selected.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.SemiBold,
						)
						Text(
							stringResource(R.string.schedule),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
				if (selectedEvents.isEmpty()) {
					item { Text(stringResource(R.string.no_events), color = MaterialTheme.colorScheme.onSurfaceVariant) }
				} else {
					items(selectedEvents, key = { it.id }) { event ->
						CalendarEventCard(event) { viewModel.removeCalendarEvent(event) }
					}
				}
			}
		}
		CornerKnockTarget(
			state.entryMethod,
			onSecretEntry,
			Modifier.align(Alignment.TopEnd).statusBarsPadding(),
		)
	}

	if (showAdd) {
		AddEventDialog(
			date = selected,
			onAdd = { viewModel.addCalendarEvent(selected.toString(), it); showAdd = false },
			onDismiss = { showAdd = false },
		)
	}
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, onSelected: (LocalDate) -> Unit) {
	val locale = Locale.getDefault()
	val firstWeekday = WeekFields.of(locale).firstDayOfWeek
	val weekdays = remember(locale) { (0L..6L).map { firstWeekday.plus(it) } }
	val first = month.atDay(1)
	val offset = (first.dayOfWeek.value - firstWeekday.value + 7) % 7
	val cells = remember(month, firstWeekday) {
		List<LocalDate?>(offset) { null } + (1..month.lengthOfMonth()).map(month::atDay)
	}
	Row(Modifier.fillMaxWidth()) {
		weekdays.forEach {
			Text(
				it.getDisplayName(TextStyle.SHORT, locale),
				modifier = Modifier.weight(1f),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
	Spacer(Modifier.height(8.dp))
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		cells.chunked(7).forEach { week ->
			Row(Modifier.fillMaxWidth()) {
				(week + List(7 - week.size) { null }).forEach { date ->
					Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
						if (date != null) {
							val chosen = date == selected
							val today = date == LocalDate.now()
							Box(
								Modifier.size(42.dp).clip(CircleShape).background(
									when {
										chosen -> MaterialTheme.colorScheme.primary
										today -> MaterialTheme.colorScheme.primaryContainer
										else -> Color.Transparent
									},
								).combinedClickable(onClick = { onSelected(date) }),
								contentAlignment = Alignment.Center,
							) {
								Text(
									date.dayOfMonth.toString(),
									color = if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
								)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun CalendarEventCard(event: CalendarEvent, onDelete: () -> Unit) {
	Card(Modifier.fillMaxWidth()) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
			Text(event.title, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyLarge)
			IconButton(onClick = onDelete) {
				Icon(Icons.Filled.Delete, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.error)
			}
		}
	}
}

@Composable
private fun AddEventDialog(date: LocalDate, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
	var title by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.new_event)) },
		text = {
			Column {
				Text(date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")))
				Spacer(Modifier.height(12.dp))
				OutlinedTextField(
					value = title,
					onValueChange = { title = it },
					label = { Text(stringResource(R.string.event_title)) },
					singleLine = true,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onAdd(title) }, enabled = title.isNotBlank()) {
				Text(stringResource(R.string.add))
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun CalculatorDecoy(entryMethod: EntryMethod, onSecretEntry: () -> Unit) {
	var calculator by remember { mutableStateOf(CalculatorState()) }
	val rows = listOf(
		listOf("C", "±", "%", "÷"),
		listOf("7", "8", "9", "×"),
		listOf("4", "5", "6", "−"),
		listOf("1", "2", "3", "+"),
		listOf("0", ".", "="),
	)
	Box(Modifier.fillMaxSize()) {
		Scaffold(topBar = { TopAppBar(title = { SecretTitle("Calculator", entryMethod, onSecretEntry) }) }) { padding ->
			Column(
				Modifier.padding(padding).fillMaxSize().padding(16.dp),
				verticalArrangement = Arrangement.Bottom,
			) {
				Text(
					calculator.display,
					modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
					style = MaterialTheme.typography.displayLarge,
					fontWeight = FontWeight.Light,
					textAlign = TextAlign.End,
					maxLines = 1,
				)
				rows.forEach { row ->
					Row(
						Modifier.fillMaxWidth().padding(vertical = 5.dp),
						horizontalArrangement = Arrangement.spacedBy(10.dp),
					) {
						row.forEach { key ->
							CalculatorKey(
								label = key,
								modifier = Modifier.weight(if (key == "0" && row.size == 3) 2f else 1f),
								accent = key in setOf("÷", "×", "−", "+", "="),
								onClick = { calculator = calculator.press(key) },
								onLongClick = onSecretEntry.takeIf {
									key == "=" && entryMethod == EntryMethod.NATURAL_HOLD
								},
							)
						}
					}
				}
			}
		}
		CornerKnockTarget(entryMethod, onSecretEntry, Modifier.align(Alignment.TopEnd).statusBarsPadding())
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalculatorKey(
	label: String,
	accent: Boolean,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.height(72.dp).combinedClickable(
			onClick = onClick,
			onLongClick = onLongClick,
		),
		shape = RoundedCornerShape(24.dp),
		color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
		contentColor = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
	) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text(label, style = MaterialTheme.typography.headlineSmall)
		}
	}
}

private fun CalculatorState.press(key: String): CalculatorState = when (key) {
	"C" -> clear()
	"±" -> toggleSign()
	"%" -> percent()
	"." -> decimal()
	"+" -> operator(CalculatorOperation.ADD)
	"−" -> operator(CalculatorOperation.SUBTRACT)
	"×" -> operator(CalculatorOperation.MULTIPLY)
	"÷" -> operator(CalculatorOperation.DIVIDE)
	"=" -> equals()
	else -> digit(key.toInt())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SecretTitle(title: String, entryMethod: EntryMethod, onSecretEntry: () -> Unit) {
	Text(
		title,
		modifier = Modifier.secretHold(entryMethod == EntryMethod.TITLE_HOLD, onSecretEntry),
		fontWeight = FontWeight.SemiBold,
	)
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.secretHold(enabled: Boolean, onLongClick: () -> Unit): Modifier =
	if (enabled) combinedClickable(onClick = {}, onLongClick = onLongClick) else this

package io.github.melastore.shelf.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.CalculatorOperation
import io.github.melastore.shelf.data.CalculatorState
import io.github.melastore.shelf.data.CalendarEvent
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.Habit
import io.github.melastore.shelf.data.currentStreak
import io.github.melastore.shelf.security.CredentialRules
import io.github.melastore.shelf.security.KnockCode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun DecoyScreen(state: AppUiState, viewModel: ShelfViewModel, onSecretEntry: () -> Unit) {
	Box(Modifier.fillMaxSize()) {
		when (state.decoy) {
			DecoyType.NONE -> LockScreen(onSecretEntry)
			DecoyType.HABITS -> HabitDecoy(state, viewModel, onSecretEntry)
			DecoyType.CALENDAR -> CalendarDecoy(state, viewModel, onSecretEntry)
			DecoyType.CALCULATOR -> CalculatorDecoy(state.entryMethod, onSecretEntry, viewModel::tryStealthUnlock)
		}
		if (state.decoy != DecoyType.NONE) {
			// Always live, whatever gesture is configured: the knock is the way back in when the chosen
			// long press lands on a control that is not on screen. Being locked out of your own private
			// space is worse than a gesture that is a little easier to stumble on.
			//
			// Top right only. No decoy puts a control there, whereas the left of the bar is the title
			// and a target over it would swallow the long press that is the default way in.
			KnockTarget(onSecretEntry, Modifier.align(Alignment.TopEnd).statusBarsPadding())
		}
	}
}

/**
 * What the app shows wearing no disguise, which is the default.
 *
 * Nothing to conceal and so nothing to find: one button, doing the one thing the app is for. The
 * hidden gestures still work for anyone who later picks a disguise, but making an undisguised app
 * hide its own front door would only lock out the owner.
 */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
	Column(
		Modifier.fillMaxSize().padding(horizontal = 32.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(
			Modifier.size(96.dp).clip(CircleShape)
				.background(MaterialTheme.colorScheme.primaryContainer),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				Icons.Filled.Lock,
				contentDescription = null,
				modifier = Modifier.size(42.dp),
				tint = MaterialTheme.colorScheme.onPrimaryContainer,
			)
		}
		Spacer(Modifier.height(28.dp))
		Text(
			stringResource(R.string.launcher_shelf),
			style = MaterialTheme.typography.headlineMedium,
		)
		Spacer(Modifier.height(8.dp))
		Text(
			stringResource(R.string.lock_screen_subtitle),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
		Spacer(Modifier.height(36.dp))
		Button(onClick = onUnlock, shape = CircleShape) {
			Text(
				stringResource(R.string.unlock),
				modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
				style = MaterialTheme.typography.titleMedium,
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitDecoy(state: AppUiState, viewModel: ShelfViewModel, onSecretEntry: () -> Unit) {
	var adding by remember { mutableStateOf(false) }
	var today by remember { mutableStateOf(LocalDate.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(60_000)
			today = LocalDate.now()
		}
	}
	val done = state.habits.count { today.toString() in it.checkedDates }

	Scaffold(
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			TopAppBar(
				title = { SecretTitle(stringResource(R.string.launcher_habits), state.entryMethod, onSecretEntry) },
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
		floatingActionButton = {
			FloatingActionButton(onClick = { adding = true }) {
				Icon(Icons.Filled.Add, stringResource(R.string.add_habit))
			}
		},
	) { padding ->
		LazyColumn(
			modifier = Modifier.padding(padding).fillMaxSize(),
			contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			item {
				val weekday = dateFormat("EEEE")
				val fullDate = dateFormat("dMMMMy")
				Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
					Text(today.format(weekday), style = MaterialTheme.typography.headlineLarge)
					Text(
						today.format(fullDate),
						style = MaterialTheme.typography.bodyLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			item {
				ProgressCard(
					done = done,
					total = state.habits.size,
					modifier = Modifier.secretHold(
						state.entryMethod == EntryMethod.NATURAL_HOLD ||
							state.entryMethod == EntryMethod.DIRECT_KEYPAD,
						onSecretEntry,
					),
				)
			}
			if (state.habits.isEmpty()) {
				item {
					EmptyState(
						stringResource(R.string.start_small),
						stringResource(R.string.habits_empty),
					)
				}
			} else {
				items(state.habits, key = { it.id }) { habit ->
					HabitCard(habit, today, viewModel)
				}
			}
		}
	}

	if (adding) {
		TextEntryDialog(
			title = stringResource(R.string.add_habit),
			label = stringResource(R.string.habit_name),
			onAdd = {
				viewModel.submitHabit(it)
				adding = false
			},
			onDismiss = { adding = false },
		)
	}
}

@Composable
private fun ProgressCard(done: Int, total: Int, modifier: Modifier = Modifier) {
	val fraction = if (total == 0) 0f else done.toFloat() / total
	val animated by animateFloatAsState(fraction, tween(600), label = "progress")
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.extraLarge,
		color = MaterialTheme.colorScheme.primaryContainer,
	) {
		Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
				val track = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
				val arc = MaterialTheme.colorScheme.primary
				Canvas(Modifier.fillMaxSize()) {
					val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
					val inset = stroke.width / 2
					val corner = Offset(inset, inset)
					val ring = Size(size.width - stroke.width, size.height - stroke.width)
					drawArc(track, -90f, 360f, false, corner, ring, style = stroke)
					drawArc(arc, -90f, 360f * animated, false, corner, ring, style = stroke)
				}
				// Follows the arc rather than the target, so the number and the ring never disagree.
				Text(
					percentText(animated),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
				)
			}
			Column(Modifier.padding(start = 20.dp)) {
				Text(
					"$done / $total",
					style = MaterialTheme.typography.headlineMedium,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
				)
				Text(
					stringResource(R.string.completed_today),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
				)
				Spacer(Modifier.height(6.dp))
				Text(
					stringResource(R.string.habit_day_prompt),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
				)
			}
		}
	}
}

/** Locale-aware, because not every language writes a percentage as "50%". */
@Composable
private fun percentText(fraction: Float): String {
	val locale = currentLocale()
	val format = remember(locale) { NumberFormat.getPercentInstance(locale) }
	return format.format(fraction)
}

@Composable
private fun HabitCard(habit: Habit, today: LocalDate, viewModel: ShelfViewModel) {
	val week = remember(today) { (6L downTo 0L).map(today::minusDays) }
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text(
						habit.name,
						style = MaterialTheme.typography.titleMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					val streak = currentStreak(habit.checkedDates, today)
					Text(
						pluralStringResource(R.plurals.streak_format, streak, streak),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}
				IconButton(onClick = { viewModel.removeHabit(habit) }) {
					Icon(
						Icons.Filled.Delete,
						stringResource(R.string.remove),
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			Spacer(Modifier.height(14.dp))
			Row(
				Modifier.fillMaxWidth().padding(end = 12.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				week.forEach { date ->
					DayBubble(date, date.toString() in habit.checkedDates) {
						viewModel.toggleHabit(habit, date.toString())
					}
				}
			}
		}
	}
}

/**
 * The device locale, read through the configuration so Compose notices when it changes.
 *
 * `Locale.getDefault()` is a plain static read, so a calendar laid out for one locale keeps its
 * weekday order and names after the user picks another, until something unrelated recomposes it.
 */
@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * A date formatter for [skeleton], a field list rather than a layout. Only the platform knows that
 * en-GB writes "3 March" and en-US "March 3"; a hardcoded "d MMMM" gets one of them wrong.
 */
@Composable
private fun dateFormat(skeleton: String): DateTimeFormatter {
	val locale = currentLocale()
	return remember(locale, skeleton) {
		DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayBubble(date: LocalDate, checked: Boolean, onClick: () -> Unit) {
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Text(
			date.dayOfWeek.getDisplayName(TextStyle.NARROW, currentLocale()),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(Modifier.height(6.dp))
		Box(
			Modifier.size(36.dp).clip(CircleShape).background(
				if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
			).combinedClickable(onClick = onClick),
			contentAlignment = Alignment.Center,
		) {
			Text(
				date.dayOfMonth.toString(),
				style = MaterialTheme.typography.labelLarge,
				color = if (checked) {
					MaterialTheme.colorScheme.onPrimary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDecoy(state: AppUiState, viewModel: ShelfViewModel, onSecretEntry: () -> Unit) {
	var month by remember { mutableStateOf(YearMonth.now()) }
	var selected by remember { mutableStateOf(LocalDate.now()) }
	var showAdd by remember { mutableStateOf(false) }
	val selectedEvents = state.calendarEvents.filter { it.date == selected.toString() }
	val busyDays = remember(state.calendarEvents) { state.calendarEvents.mapTo(mutableSetOf()) { it.date } }

	Scaffold(
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			TopAppBar(
				title = {
					SecretTitle(stringResource(R.string.launcher_calendar), state.entryMethod, onSecretEntry)
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
		floatingActionButton = {
			FloatingActionButton(onClick = { showAdd = true }) {
				Icon(Icons.Filled.Add, stringResource(R.string.add_event))
			}
		},
	) { padding ->
		LazyColumn(
			Modifier.padding(padding).fillMaxSize(),
			contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			item {
				Surface(
					Modifier.fillMaxWidth(),
					shape = MaterialTheme.shapes.extraLarge,
					color = MaterialTheme.colorScheme.surfaceContainer,
				) {
					Column(Modifier.padding(16.dp)) {
						Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							IconButton(onClick = { month = month.minusMonths(1) }) {
								Icon(
									Icons.AutoMirrored.Filled.KeyboardArrowLeft,
									stringResource(R.string.previous_month),
								)
							}
							Text(
								month.format(dateFormat("MMMMy")),
								modifier = Modifier.weight(1f),
								style = MaterialTheme.typography.titleLarge,
								textAlign = TextAlign.Center,
							)
							IconButton(onClick = { month = month.plusMonths(1) }) {
								Icon(
									Icons.AutoMirrored.Filled.KeyboardArrowRight,
									stringResource(R.string.next_month),
								)
							}
						}
						Spacer(Modifier.height(8.dp))
						MonthGrid(month, selected, busyDays) {
							selected = it
							month = YearMonth.from(it)
						}
					}
				}
			}
			item {
				Column(
					Modifier.padding(top = 4.dp).secretHold(
						state.entryMethod == EntryMethod.NATURAL_HOLD ||
							state.entryMethod == EntryMethod.DIRECT_KEYPAD,
						onSecretEntry,
					),
				) {
					Text(
						selected.format(dateFormat("EEEEdMMMM")),
						style = MaterialTheme.typography.titleLarge,
					)
					Text(
						stringResource(R.string.schedule),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			if (selectedEvents.isEmpty()) {
				item {
					Text(
						stringResource(R.string.no_events),
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			} else {
				items(selectedEvents, key = { it.id }) { event ->
					CalendarEventCard(event) { viewModel.removeCalendarEvent(event) }
				}
			}
		}
	}

	if (showAdd) {
		TextEntryDialog(
			title = stringResource(R.string.new_event),
			label = stringResource(R.string.event_title),
			subtitle = selected.format(dateFormat("EEEEdMMMM")),
			onAdd = {
				viewModel.addCalendarEvent(selected.toString(), it)
				showAdd = false
			},
			onDismiss = { showAdd = false },
		)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, busyDays: Set<String>, onSelected: (LocalDate) -> Unit,) {
	val locale = currentLocale()
	val firstWeekday = WeekFields.of(locale).firstDayOfWeek
	val weekdays = remember(locale) { (0L..6L).map { firstWeekday.plus(it) } }
	val first = month.atDay(1)
	val offset = (first.dayOfWeek.value - firstWeekday.value + 7) % 7
	val cells = remember(month, firstWeekday) {
		List<LocalDate?>(offset) { null } + (1..month.lengthOfMonth()).map(month::atDay)
	}
	val today = LocalDate.now()

	Row(Modifier.fillMaxWidth()) {
		weekdays.forEach {
			Text(
				it.getDisplayName(TextStyle.NARROW, locale),
				modifier = Modifier.weight(1f),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
		}
	}
	Spacer(Modifier.height(6.dp))
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		cells.chunked(7).forEach { week ->
			Row(Modifier.fillMaxWidth()) {
				(week + List(7 - week.size) { null }).forEach { date ->
					Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
						if (date != null) {
							val chosen = date == selected
							Box(
								Modifier.fillMaxSize().padding(3.dp).clip(CircleShape).background(
									when {
										chosen -> MaterialTheme.colorScheme.primary
										date == today -> MaterialTheme.colorScheme.primaryContainer
										else -> Color.Transparent
									},
								).combinedClickable(onClick = { onSelected(date) }),
								contentAlignment = Alignment.Center,
							) {
								Column(horizontalAlignment = Alignment.CenterHorizontally) {
									Text(
										date.dayOfMonth.toString(),
										style = MaterialTheme.typography.bodyMedium,
										color = if (chosen) {
											MaterialTheme.colorScheme.onPrimary
										} else {
											MaterialTheme.colorScheme.onSurface
										},
									)
									Box(
										Modifier.padding(top = 2.dp).size(4.dp).clip(CircleShape)
											.background(
												if (date.toString() in busyDays) {
													if (chosen) {
														MaterialTheme.colorScheme.onPrimary
													} else {
														MaterialTheme.colorScheme.primary
													}
												} else {
													Color.Transparent
												},
											),
									)
								}
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
	Surface(
		Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Row(
			Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(
				Modifier.size(width = 4.dp, height = 28.dp).clip(CircleShape)
					.background(MaterialTheme.colorScheme.primary),
			)
			Text(
				event.title,
				Modifier.padding(start = 16.dp).weight(1f),
				style = MaterialTheme.typography.bodyLarge,
			)
			IconButton(onClick = onDelete) {
				Icon(
					Icons.Filled.Delete,
					stringResource(R.string.remove),
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalculatorDecoy(
	entryMethod: EntryMethod,
	onSecretEntry: () -> Unit,
	onStealthUnlock: (CharArray) -> Unit = {},
) {
	var calculator by remember { mutableStateOf(CalculatorState()) }
	val knockTaps = remember { mutableStateListOf<Int>() }
	// The keys pressed, not the display. A PIN starting with a zero never reaches the display, which
	// drops a leading zero the way a calculator has to.
	var typed by remember { mutableStateOf("") }
	val haptics = LocalHapticFeedback.current
	val rows = listOf(
		listOf("C", "±", "%", "÷"),
		listOf("7", "8", "9", "×"),
		listOf("4", "5", "6", "−"),
		listOf("1", "2", "3", "+"),
		listOf("0", ".", "="),
	)
	Scaffold(
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			TopAppBar(
				title = {
					SecretTitle(stringResource(R.string.launcher_calculator), entryMethod, onSecretEntry)
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
	) { padding ->
		Column(
			Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
			verticalArrangement = Arrangement.Bottom,
		) {
			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.pointerInput(Unit) {
						detectTapGestures(
							onTap = { position ->
								val col = if (position.x < size.width / 2f) 0 else 1
								val row = if (position.y < size.height / 2f) 0 else 1
								if (knockTaps.size < KnockCode.MAX_TAPS) {
									knockTaps += row * KnockCode.SIDE + col
									haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
								}
							},
							onLongPress = {
								if (knockTaps.size in KnockCode.MIN_TAPS..KnockCode.MAX_TAPS) {
									haptics.performHapticFeedback(HapticFeedbackType.LongPress)
									onStealthUnlock(KnockCode.encode(knockTaps.toList()))
									knockTaps.clear()
								}
							},
						)
					},
				contentAlignment = Alignment.BottomEnd,
			) {
				Text(
					calculator.display,
					modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
					style = MaterialTheme.typography.displayLarge,
					fontSize = 64.sp,
					fontWeight = FontWeight.Light,
					textAlign = TextAlign.End,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			rows.forEach { row ->
				Row(
					Modifier.fillMaxWidth().padding(vertical = 5.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
				) {
					row.forEach { key ->
						CalculatorKey(
							label = key,
							modifier = Modifier.weight(if (key == "0" && row.size == 3) 2f else 1f),
							kind = keyKind(key),
							onClick = {
								when (key) {
									"C" -> {
										knockTaps.clear()
										typed = ""
									}

									"÷", "×", "−", "+", ".", "±", "%" -> typed = ""

									"=" -> {
										stealthUnlock(knockTaps, typed, calculator)?.let(onStealthUnlock)
										knockTaps.clear()
										typed = ""
									}

									// A calculator that stopped taking digits after twelve would be a strange
									// one, so the entry keeps growing and simply stops being a candidate.
									else -> if (key.first().isDigit()) typed += key
								}
								calculator = calculator.press(key)
							},
							onLongClick = onSecretEntry.takeIf {
								key == "=" && (entryMethod == EntryMethod.NATURAL_HOLD || entryMethod == EntryMethod.DIRECT_KEYPAD)
							},
						)
					}
				}
			}
			Spacer(Modifier.height(16.dp))
		}
	}
}

/**
 * What pressing equals should be read as, or null if it is only arithmetic.
 *
 * Three ways to offer the same secret: a knock code tapped on the display, the digits actually
 * pressed, and the number now showing, which is how a PIN arrives when it was worked out rather
 * than typed. Whether any of them is right is decided elsewhere.
 */
private fun stealthUnlock(knockTaps: List<Int>, typed: String, calculator: CalculatorState,): CharArray? {
	val pinLength = CredentialRules.MIN_PIN..CredentialRules.MAX_PIN
	return when {
		knockTaps.size in KnockCode.MIN_TAPS..KnockCode.MAX_TAPS -> KnockCode.encode(knockTaps)

		typed.length in pinLength -> typed.toCharArray()

		calculator.operation == null && calculator.display.length in pinLength &&
			calculator.display.all { it.isDigit() } -> calculator.display.toCharArray()

		else -> null
	}
}

private enum class KeyKind { DIGIT, FUNCTION, OPERATOR }

private fun keyKind(key: String): KeyKind = when (key) {
	"C", "±", "%" -> KeyKind.FUNCTION
	"÷", "×", "−", "+", "=" -> KeyKind.OPERATOR
	else -> KeyKind.DIGIT
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalculatorKey(
	label: String,
	kind: KeyKind,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	val background = when (kind) {
		KeyKind.DIGIT -> MaterialTheme.colorScheme.surfaceContainerHigh
		KeyKind.FUNCTION -> MaterialTheme.colorScheme.secondaryContainer
		KeyKind.OPERATOR -> MaterialTheme.colorScheme.primary
	}
	val foreground = when (kind) {
		KeyKind.DIGIT -> MaterialTheme.colorScheme.onSurface
		KeyKind.FUNCTION -> MaterialTheme.colorScheme.onSecondaryContainer
		KeyKind.OPERATOR -> MaterialTheme.colorScheme.onPrimary
	}
	Surface(
		modifier = modifier.aspectRatio(if (label == "0") 2.15f else 1f).clip(RoundedCornerShape(28.dp))
			.combinedClickable(onClick = onClick, onLongClick = onLongClick),
		shape = RoundedCornerShape(28.dp),
		color = background,
		contentColor = foreground,
	) {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
			Text(label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
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
	"=" -> evaluate()
	else -> digit(key.toInt())
}

/**
 * The app name in the toolbar, and the way in for the two gestures that live on it.
 *
 * No haptic on either. A title that buzzes under a finger is a title worth pressing again, which is
 * the one thing a hidden gesture cannot afford.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SecretTitle(title: String, entryMethod: EntryMethod, onSecretEntry: () -> Unit) {
	val interactionSource = remember { MutableInteractionSource() }
	val modifier = when (entryMethod) {
		EntryMethod.TITLE_HOLD -> Modifier.combinedClickable(
			interactionSource = interactionSource,
			indication = null,
			onClick = {},
			onLongClick = onSecretEntry,
		)

		EntryMethod.DOUBLE_TAP_TITLE -> Modifier.combinedClickable(
			interactionSource = interactionSource,
			indication = null,
			onClick = {},
			onDoubleClick = onSecretEntry,
		)

		else -> Modifier
	}
	Text(title, modifier = modifier, style = MaterialTheme.typography.titleLarge)
}

private fun Modifier.secretHold(enabled: Boolean, onLongClick: () -> Unit): Modifier = if (enabled) {
	pointerInput(Unit) {
		detectTapGestures(onLongPress = { onLongClick() })
	}
} else {
	this
}

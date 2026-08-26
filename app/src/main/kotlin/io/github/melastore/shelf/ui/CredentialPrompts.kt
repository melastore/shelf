package io.github.melastore.shelf.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.melastore.shelf.R
import io.github.melastore.shelf.security.CredentialKind
import io.github.melastore.shelf.security.CredentialRules
import io.github.melastore.shelf.security.KnockCode
import io.github.melastore.shelf.security.PatternCode
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Asks for a credential in whichever form this vault uses.
 *
 * One entry point rather than a branch at every call site: every screen that asks for a credential —
 * unlocking, changing one, confirming before a change — has to ask for the same one, and a screen that
 * offered a keypad to a vault opened by pattern would simply be a screen the owner cannot get past.
 */
@Composable
fun CredentialPrompt(
	kind: CredentialKind,
	title: String,
	subtitle: String,
	confirmLabel: String,
	confirmEntry: Boolean = false,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	when (kind) {
		CredentialKind.PIN -> PinPrompt(
			title = title,
			subtitle = subtitle,
			confirmLabel = confirmLabel,
			confirmEntry = confirmEntry,
			onConfirm = onConfirm,
			onDismiss = onDismiss,
		)

		CredentialKind.PATTERN -> PatternPrompt(
			title = title,
			subtitle = subtitle,
			confirmLabel = confirmLabel,
			confirmEntry = confirmEntry,
			onConfirm = onConfirm,
			onDismiss = onDismiss,
		)

		CredentialKind.KNOCK -> KnockPrompt(
			title = title,
			subtitle = subtitle,
			confirmLabel = confirmLabel,
			confirmEntry = confirmEntry,
			onConfirm = onConfirm,
			onDismiss = onDismiss,
		)

		CredentialKind.PASSWORD -> PasswordPrompt(
			title = title,
			subtitle = subtitle,
			confirmLabel = confirmLabel,
			confirmEntry = confirmEntry,
			onConfirm = onConfirm,
			onDismiss = onDismiss,
		)
	}
}

/**
 * A 3×3 pattern, drawn the way every Android lock screen draws one.
 *
 * Dots are picked up by proximity rather than by hit-testing a circle, because a finger dragged across
 * a grid does not land on centres, and a pattern that needs precision is one the owner fails to enter
 * in the dark. A line that passes over an unused dot collects it, which is what the platform does and
 * therefore what the owner's muscle memory expects.
 */
@Composable
fun PatternPrompt(
	title: String,
	subtitle: String,
	confirmLabel: String,
	confirmEntry: Boolean = false,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	val drawn = remember { mutableStateListOf<Int>() }
	var firstPass by remember { mutableStateOf<CharArray?>(null) }
	var mismatch by remember { mutableStateOf(false) }
	val shake = remember { Animatable(0f) }
	val haptics = LocalHapticFeedback.current
	val confirming = confirmEntry && firstPass != null

	DisposableEffect(Unit) {
		onDispose { firstPass?.fill(' ') }
	}

	LaunchedEffect(mismatch) {
		if (!mismatch) return@LaunchedEffect
		haptics.performHapticFeedback(HapticFeedbackType.LongPress)
		shake.animateTo(
			0f,
			keyframes {
				durationMillis = 300
				(-1f) at 60
				1f at 120
				(-0.6f) at 180
				0.6f at 240
			},
		)
	}

	fun finish() {
		val entered = PatternCode.encode(drawn.toList())
		val stored = firstPass
		when {
			!confirmEntry -> onConfirm(entered)

			stored == null -> {
				firstPass = entered
				drawn.clear()
				mismatch = false
			}

			stored.contentEquals(entered) -> onConfirm(entered)

			else -> {
				stored.fill(' ')
				firstPass = null
				entered.fill(' ')
				drawn.clear()
				mismatch = true
			}
		}
	}

	CredentialScaffold(
		title = if (confirming) stringResource(R.string.confirm_pattern) else title,
		subtitle = if (mismatch) stringResource(R.string.pattern_mismatch) else subtitle,
		error = mismatch,
		onDismiss = onDismiss,
	) {
		PatternGrid(
			drawn = drawn,
			modifier = Modifier.widthIn(max = 320.dp)
				.offset { IntOffset((shake.value * 10.dp.toPx()).roundToInt(), 0) },
			onDotAdded = {
				mismatch = false
				haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
			},
		)
		Spacer(Modifier.height(20.dp))
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
			Spacer(Modifier.width(8.dp))
			TextButton(onClick = ::finish, enabled = PatternCode.isValid(drawn.toList())) {
				Text(
					if (confirmEntry && firstPass == null) {
						stringResource(R.string.continue_action)
					} else {
						confirmLabel
					},
				)
			}
		}
	}
}

/**
 * Four unmarked quarters, tapped in order.
 *
 * Nothing on the pad says where the divisions fall or how long the code is, so it can be entered
 * without looking and a watcher sees taps landing on a blank square. Only the count is echoed back,
 * never the quarter: a row of filled dots tells the owner how far along they are and tells anyone
 * over their shoulder nothing they could repeat.
 */
@Composable
fun KnockPrompt(
	title: String,
	subtitle: String,
	confirmLabel: String,
	confirmEntry: Boolean = false,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	val tapped = remember { mutableStateListOf<Int>() }
	var firstPass by remember { mutableStateOf<CharArray?>(null) }
	var mismatch by remember { mutableStateOf(false) }
	val shake = remember { Animatable(0f) }
	val haptics = LocalHapticFeedback.current
	val confirming = confirmEntry && firstPass != null

	DisposableEffect(Unit) {
		onDispose { firstPass?.fill(' ') }
	}

	LaunchedEffect(mismatch) {
		if (!mismatch) return@LaunchedEffect
		haptics.performHapticFeedback(HapticFeedbackType.LongPress)
		shake.animateTo(
			0f,
			keyframes {
				durationMillis = 300
				(-1f) at 60
				1f at 120
				(-0.6f) at 180
				0.6f at 240
			},
		)
	}

	fun finish() {
		val entered = KnockCode.encode(tapped.toList())
		val stored = firstPass
		when {
			!confirmEntry -> onConfirm(entered)

			stored == null -> {
				firstPass = entered
				tapped.clear()
				mismatch = false
			}

			stored.contentEquals(entered) -> onConfirm(entered)

			else -> {
				stored.fill(' ')
				firstPass = null
				entered.fill(' ')
				tapped.clear()
				mismatch = true
			}
		}
	}

	CredentialScaffold(
		title = if (confirming) stringResource(R.string.confirm_knock) else title,
		subtitle = if (mismatch) stringResource(R.string.knock_mismatch) else subtitle,
		error = mismatch,
		onDismiss = onDismiss,
	) {
		KnockCount(tapped.size)
		Spacer(Modifier.height(16.dp))
		KnockPad(
			modifier = Modifier.widthIn(max = 320.dp)
				.offset { IntOffset((shake.value * 10.dp.toPx()).roundToInt(), 0) },
			onQuarter = { quarter ->
				if (tapped.size < KnockCode.MAX_TAPS) {
					mismatch = false
					tapped += quarter
					haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
				}
			},
		)
		Spacer(Modifier.height(20.dp))
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
			Spacer(Modifier.width(8.dp))
			TextButton(onClick = { tapped.clear() }, enabled = tapped.isNotEmpty()) {
				Text(stringResource(R.string.clear))
			}
			Spacer(Modifier.width(8.dp))
			TextButton(onClick = ::finish, enabled = KnockCode.isValid(tapped.toList())) {
				Text(
					if (confirmEntry && firstPass == null) {
						stringResource(R.string.continue_action)
					} else {
						confirmLabel
					},
				)
			}
		}
	}
}

/** How many taps are in, and nothing about which ones. */
@Composable
private fun KnockCount(taps: Int) {
	Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
		repeat(KnockCode.MIN_TAPS.coerceAtLeast(taps)) { index ->
			Box(
				Modifier.padding(horizontal = 5.dp).size(if (index < taps) 11.dp else 8.dp)
					.background(
						if (index < taps) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.outlineVariant
						},
						CircleShape,
					),
			)
		}
	}
}

@Composable
private fun KnockPad(onQuarter: (Int) -> Unit, modifier: Modifier = Modifier) {
	val description = stringResource(R.string.knock_area)
	val line = MaterialTheme.colorScheme.outlineVariant
	Box(
		modifier.fillMaxWidth().aspectRatio(1f)
			.clip(RoundedCornerShape(28.dp))
			.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
			.semantics { contentDescription = description }
			.pointerInput(Unit) {
				detectTapGestures { position ->
					val column = if (position.x < size.width / 2f) 0 else 1
					val row = if (position.y < size.height / 2f) 0 else 1
					onQuarter(row * KnockCode.SIDE + column)
				}
			},
	) {
		// Faint enough to be a shape rather than a keypad, and to leave no wear marks worth reading.
		Canvas(Modifier.matchParentSize()) {
			drawLine(line, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
			drawLine(line, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
		}
	}
}

@Composable
private fun PatternGrid(drawn: SnapshotStateList<Int>, onDotAdded: () -> Unit, modifier: Modifier = Modifier) {
	// The grid is square, so one number describes it. It is recorded at layout rather than at draw:
	// writing state while drawing is what makes a canvas redraw itself forever.
	var side by remember { mutableStateOf(0f) }
	val spacing = side / PatternCode.SIDE
	val origin = spacing / 2f
	val active = MaterialTheme.colorScheme.primary
	val idle = MaterialTheme.colorScheme.outlineVariant
	val description = stringResource(R.string.pattern_area)

	fun centre(dot: Int) = Offset(
		origin + dot % PatternCode.SIDE * spacing,
		origin + dot / PatternCode.SIDE * spacing,
	)

	fun dotAt(position: Offset): Int? {
		if (spacing <= 0f) return null
		val column = ((position.x - origin) / spacing).roundToInt()
		val row = ((position.y - origin) / spacing).roundToInt()
		if (column !in 0 until PatternCode.SIDE || row !in 0 until PatternCode.SIDE) return null
		val dot = row * PatternCode.SIDE + column
		// A finger dragged across a grid does not land on centres. Anything within a third of the gap
		// counts as that dot; a pattern that needs precision is one its owner fails to enter in the dark.
		val reach = centre(dot)
		if (hypot(position.x - reach.x, position.y - reach.y) > spacing / 3f) return null
		return dot
	}

	fun extendTo(position: Offset) {
		val dot = dotAt(position) ?: return
		if (dot in drawn) return
		drawn.lastOrNull()?.let { previous ->
			drawn += PatternCode.crossed(previous, dot).filterNot { it in drawn }
		}
		drawn += dot
		onDotAdded()
	}

	Canvas(
		modifier = modifier.fillMaxWidth().aspectRatio(1f).testTag("pattern")
			.semantics { contentDescription = description }
			.onSizeChanged { side = minOf(it.width, it.height).toFloat() }
			// Not detectDragGestures: it reports the position after touch slop has been consumed, which
			// on a quick swipe is already past the dot the finger went down on. That dot is then missing
			// from the pattern, and the owner draws the shape they always draw and is refused.
			.pointerInput(spacing) {
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					drawn.clear()
					extendTo(down.position)
					do {
						val event = awaitPointerEvent()
						event.changes.forEach { change ->
							if (change.pressed) extendTo(change.position)
						}
					} while (event.changes.any { it.pressed })
				}
			},
	) {
		val stroke = spacing / 12f
		drawn.zipWithNext { from, to ->
			drawLine(active, centre(from), centre(to), strokeWidth = stroke, cap = StrokeCap.Round)
		}
		repeat(PatternCode.DOTS) { dot ->
			val on = dot in drawn
			drawCircle(if (on) active else idle, radius = spacing / (if (on) 7f else 10f), center = centre(dot))
		}
	}
}

/** A typed credential. Also the way in for vaults created before Shelf offered anything else. */
@Composable
fun PasswordPrompt(
	title: String,
	subtitle: String,
	confirmLabel: String,
	confirmEntry: Boolean = false,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	var text by remember { mutableStateOf("") }
	var firstPass by remember { mutableStateOf<CharArray?>(null) }
	var mismatch by remember { mutableStateOf(false) }
	val confirming = confirmEntry && firstPass != null

	DisposableEffect(Unit) {
		onDispose { firstPass?.fill(' ') }
	}

	fun finish() {
		val entered = text.toCharArray()
		val stored = firstPass
		when {
			!confirmEntry -> onConfirm(entered)

			stored == null -> {
				firstPass = entered
				text = ""
				mismatch = false
			}

			stored.contentEquals(entered) -> onConfirm(entered)

			else -> {
				stored.fill(' ')
				firstPass = null
				entered.fill(' ')
				text = ""
				mismatch = true
			}
		}
	}

	CredentialScaffold(
		title = if (confirming) stringResource(R.string.confirm_password) else title,
		subtitle = if (mismatch) stringResource(R.string.password_mismatch) else subtitle,
		error = mismatch,
		onDismiss = onDismiss,
	) {
		OutlinedTextField(
			value = text,
			onValueChange = {
				text = it
				mismatch = false
			},
			modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).testTag("password"),
			singleLine = true,
			shape = MaterialTheme.shapes.medium,
			label = { Text(stringResource(R.string.password)) },
			visualTransformation = PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
		)
		Spacer(Modifier.height(20.dp))
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
			Spacer(Modifier.width(8.dp))
			TextButton(onClick = ::finish, enabled = text.length >= CredentialRules.MIN_PASSWORD) {
				Text(
					if (confirmEntry && firstPass == null) {
						stringResource(R.string.continue_action)
					} else {
						confirmLabel
					},
				)
			}
		}
	}
}

/**
 * The full-screen surface every credential prompt sits on, kept identical between them.
 *
 * [SecureFlagPolicy.SecureOn] is the point of the dialog rather than a detail of it: this is the one
 * window that is on screen while a credential is being entered, and it must never reach a screenshot
 * or the recents thumbnail.
 */
@Composable
private fun CredentialScaffold(
	title: String,
	subtitle: String,
	error: Boolean,
	onDismiss: () -> Unit,
	content: @Composable () -> Unit,
) {
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			securePolicy = SecureFlagPolicy.SecureOn,
		),
	) {
		Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
			Column(
				Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
				verticalArrangement = Arrangement.Center,
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
				Spacer(Modifier.height(4.dp))
				Text(
					subtitle,
					style = MaterialTheme.typography.bodyMedium,
					color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)
				Spacer(Modifier.height(28.dp))
				content()
			}
		}
	}
}

/** The words that go with a credential kind, so no screen has to spell out all three for itself. */
object CredentialWords {

	@Composable
	fun enterTitle(kind: CredentialKind): String = stringResource(
		when (kind) {
			CredentialKind.PIN -> R.string.enter_pin
			CredentialKind.PATTERN -> R.string.draw_pattern
			CredentialKind.KNOCK -> R.string.enter_knock
			CredentialKind.PASSWORD -> R.string.enter_password
		},
	)

	@Composable
	fun enterSubtitle(kind: CredentialKind): String = stringResource(
		when (kind) {
			CredentialKind.PIN -> R.string.enter_pin_subtitle
			CredentialKind.PATTERN -> R.string.draw_pattern_subtitle
			CredentialKind.KNOCK -> R.string.enter_knock_subtitle
			CredentialKind.PASSWORD -> R.string.enter_password_subtitle
		},
	)

	@Composable
	fun currentTitle(kind: CredentialKind): String = stringResource(
		when (kind) {
			CredentialKind.PIN -> R.string.current_pin
			CredentialKind.PATTERN -> R.string.current_pattern
			CredentialKind.KNOCK -> R.string.current_knock
			CredentialKind.PASSWORD -> R.string.current_password
		},
	)

	@Composable
	fun newTitle(kind: CredentialKind): String = stringResource(
		when (kind) {
			CredentialKind.PIN -> R.string.new_pin
			CredentialKind.PATTERN -> R.string.new_pattern
			CredentialKind.KNOCK -> R.string.new_knock
			CredentialKind.PASSWORD -> R.string.new_password
		},
	)

	/** What makes a good one. Shown while the owner is choosing, never while they are proving. */
	@Composable
	fun hint(kind: CredentialKind): String = stringResource(
		when (kind) {
			CredentialKind.PIN -> R.string.pin_hint
			CredentialKind.PATTERN -> R.string.pattern_hint
			CredentialKind.KNOCK -> R.string.knock_hint
			CredentialKind.PASSWORD -> R.string.password_hint
		},
	)

	@Composable
	fun label(kind: CredentialKind): String = stringResource(
		when (kind) {
			CredentialKind.PIN -> R.string.credential_kind_pin
			CredentialKind.PATTERN -> R.string.credential_kind_pattern
			CredentialKind.KNOCK -> R.string.credential_kind_knock
			CredentialKind.PASSWORD -> R.string.credential_kind_password
		},
	)
}

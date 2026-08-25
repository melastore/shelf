package io.github.melastore.shelf.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.security.CredentialKind

private enum class SetupStep { WELCOME, DISGUISE, ENTRY, METHOD, STORAGE, NOTIFICATIONS, CREDENTIAL }

/**
 * What a first-time owner sees instead of a decoy.
 *
 * Setting the app up in the disguise would mean explaining the disguise from inside it, and picking a
 * PIN through a gesture nobody has been told about yet. So the first launch is honest and the app
 * only puts its face on once there is something to hide behind it. From then on this never appears
 * again: the presence of a credential is what retires it, so an install that already has one — an
 * upgrade — goes straight to the decoy as before.
 */
@Composable
fun FirstRunSetup(
	state: AppUiState,
	onDecoy: (DecoyType) -> Unit,
	onEntryMethod: (EntryMethod) -> Unit,
	onHidingPreference: (HidingPreference) -> Unit,
	onCheckMethods: () -> Unit,
	onRequestAllFiles: () -> Unit,
	onRequestNotifications: () -> Unit,
	onCreateCredential: (CredentialKind, CharArray) -> Unit,
) {
	var index by rememberSaveable { mutableIntStateOf(0) }
	val steps = SetupStep.entries
	val step = steps[index.coerceIn(0, steps.lastIndex)]

	Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
		Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
			SetupHeader(
				step = index + 1,
				total = steps.size,
				onBack = { if (index > 0) index-- },
			)

			AnimatedContent(
				targetState = step,
				modifier = Modifier.weight(1f),
				transitionSpec = {
					val forward = targetState.ordinal > initialState.ordinal
					val width = if (forward) 1 else -1
					(slideInHorizontally(tween(260)) { it / 6 * width } + fadeIn(tween(260)))
						.togetherWith(
							slideOutHorizontally(tween(200)) { -it / 6 * width } + fadeOut(tween(200)),
						)
				},
				label = "setup",
			) { current ->
				Column(
					Modifier.fillMaxSize().verticalScroll(rememberScrollState())
						.padding(horizontal = 24.dp),
				) {
					when (current) {
						SetupStep.WELCOME -> WelcomeStep()
						SetupStep.DISGUISE -> DisguiseStep(state.decoy, onDecoy)
						SetupStep.ENTRY -> EntryStep(state.entryMethod, onEntryMethod)
						SetupStep.METHOD -> MethodStep(state, onHidingPreference, onCheckMethods)
						SetupStep.STORAGE -> StorageStep(state, onRequestAllFiles)
						SetupStep.NOTIFICATIONS -> NotificationStep(state.quickLockNotification, onRequestNotifications)
						SetupStep.CREDENTIAL -> CredentialStep(onCreateCredential)
					}
					Spacer(Modifier.height(24.dp))
				}
			}

			// The PIN step carries its own confirm button, because "Continue" would be wrong for a
			// keypad that has to be entered twice before it means anything.
			if (step != SetupStep.CREDENTIAL) {
				Row(
					Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Button(onClick = { index++ }, shape = CircleShape) {
						Text(
							stringResource(
								if (step == SetupStep.WELCOME) R.string.setup_start else R.string.continue_action,
							),
							modifier = Modifier.padding(horizontal = 8.dp),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun SetupHeader(step: Int, total: Int, onBack: () -> Unit) {
	Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			if (step > 1) {
				IconButton(onClick = onBack) {
					Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
				}
			} else {
				Spacer(Modifier.size(48.dp))
			}
			Text(
				stringResource(R.string.setup_step_counter, step, total),
				modifier = Modifier.weight(1f),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
			Spacer(Modifier.size(48.dp))
		}
		LinearProgressIndicator(
			progress = { step.toFloat() / total },
			modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
		)
	}
}

@Composable
private fun StepTitle(title: String, body: String) {
	Spacer(Modifier.height(12.dp))
	Text(title, style = MaterialTheme.typography.headlineMedium)
	Spacer(Modifier.height(10.dp))
	Text(
		body,
		style = MaterialTheme.typography.bodyLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
	Spacer(Modifier.height(24.dp))
}

@Composable
private fun WelcomeStep() {
	Spacer(Modifier.height(32.dp))
	Box(
		Modifier.size(84.dp).clip(MaterialTheme.shapes.extraLarge)
			.background(MaterialTheme.colorScheme.primaryContainer),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			Icons.Filled.Lock,
			contentDescription = null,
			modifier = Modifier.size(38.dp),
			tint = MaterialTheme.colorScheme.onPrimaryContainer,
		)
	}
	StepTitle(
		stringResource(R.string.setup_welcome_title),
		stringResource(R.string.setup_welcome_body),
	)
	// Said once, plainly, at the only moment the app has the owner's full attention. An app that
	// hides folders is easily mistaken for one that encrypts them, and that mistake is the kind
	// people act on.
	NoticeCard(stringResource(R.string.setup_welcome_caveat))
}

@Composable
private fun NoticeCard(text: String) {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.tertiaryContainer,
	) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
			Icon(
				Icons.Filled.Warning,
				contentDescription = null,
				modifier = Modifier.size(20.dp),
				tint = MaterialTheme.colorScheme.onTertiaryContainer,
			)
			Text(
				text,
				modifier = Modifier.padding(start = 12.dp),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onTertiaryContainer,
			)
		}
	}
}

@Composable
private fun DisguiseStep(selected: DecoyType, onDecoy: (DecoyType) -> Unit) {
	StepTitle(
		stringResource(R.string.setup_disguise_title),
		stringResource(R.string.setup_disguise_body),
	)
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
		DecoyType.entries.forEach { decoy ->
			ChoiceTile(
				label = decoy.setupLabel(),
				selected = selected == decoy,
				modifier = Modifier.weight(1f),
				icon = { SetupDecoyBadge(decoy) },
				onClick = { onDecoy(decoy) },
			)
		}
	}
}

@Composable
private fun SetupDecoyBadge(decoy: DecoyType) {
	val (background, foreground) = when (decoy) {
		DecoyType.NONE -> R.drawable.ic_bg_shelf to R.drawable.ic_launcher_shelf_foreground
		DecoyType.HABITS -> R.drawable.ic_bg_habits to R.drawable.ic_launcher_foreground
		DecoyType.CALENDAR -> R.drawable.ic_bg_calendar to R.drawable.ic_launcher_calendar_foreground
		DecoyType.CALCULATOR -> R.drawable.ic_bg_calculator to R.drawable.ic_launcher_calculator_foreground
	}
	Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.medium)) {
		Image(painterResource(background), null, Modifier.fillMaxSize())
		Image(painterResource(foreground), null, Modifier.fillMaxSize())
	}
}

@Composable
private fun EntryStep(selected: EntryMethod, onEntryMethod: (EntryMethod) -> Unit) {
	StepTitle(
		stringResource(R.string.setup_entry_title),
		stringResource(R.string.setup_entry_body),
	)
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(Modifier.padding(vertical = 4.dp)) {
			EntryMethod.entries.forEach { method ->
				SettingsRow(
					title = method.setupTitle(),
					summary = method.setupSummary(),
					trailing = { SelectionMark(selected == method) },
					onClick = { onEntryMethod(method) },
				)
			}
		}
	}
}

@Composable
private fun MethodStep(state: AppUiState, onHidingPreference: (HidingPreference) -> Unit, onCheckMethods: () -> Unit,) {
	StepTitle(
		stringResource(R.string.setup_method_title),
		stringResource(R.string.setup_method_body),
	)
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(Modifier.padding(vertical = 4.dp)) {
			HidingPreference.entries.forEach { preference ->
				SettingsRow(
					title = preference.setupTitle(),
					summary = preference.setupSummary(state.availableMethods),
					trailing = { SelectionMark(state.hidingPreference == preference) },
					onClick = { onHidingPreference(preference) },
				)
			}
		}
	}
	Spacer(Modifier.height(12.dp))
	// Root is only probed when asked for: an unannounced su prompt during setup is alarming, and on a
	// phone without root it is a dialog about something the owner never mentioned wanting.
	OutlinedButton(onClick = onCheckMethods, modifier = Modifier.fillMaxWidth()) {
		Text(stringResource(R.string.setup_check_root))
	}
	if (state.availableMethods.isNotEmpty()) {
		Spacer(Modifier.height(12.dp))
		// Resolved before joining: joinToString's transform is not an inline composable context.
		val names = state.availableMethods.map { it.shortLabel() }
		Text(
			stringResource(R.string.setup_methods_found, names.joinToString(", ")),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.primary,
		)
	}
}

@Composable
private fun StorageStep(state: AppUiState, onRequestAllFiles: () -> Unit) {
	val granted = HideMethod.PRIVATE_MOVE in state.availableMethods
	StepTitle(
		stringResource(R.string.setup_storage_title),
		stringResource(R.string.setup_storage_body),
	)
	if (granted) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				Icons.Filled.Check,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(20.dp),
			)
			Text(
				stringResource(R.string.setup_storage_on),
				modifier = Modifier.padding(start = 10.dp),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.primary,
			)
		}
	} else {
		OutlinedButton(onClick = onRequestAllFiles, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.setup_grant_all_files))
		}
	}
	Spacer(Modifier.height(20.dp))
	// Said here rather than buried in Settings: this is the one permission that shows up in Android's
	// own permission list, so an owner relying on the disguise should know before granting it.
	NoticeCard(stringResource(R.string.setup_storage_caveat))
}

@Composable
private fun NotificationStep(enabled: Boolean, onRequestNotifications: () -> Unit) {
	StepTitle(
		stringResource(R.string.setup_notifications_title),
		stringResource(R.string.setup_notifications_body),
	)
	if (enabled) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				Icons.Filled.Check,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.size(20.dp),
			)
			Text(
				stringResource(R.string.setup_notifications_on),
				modifier = Modifier.padding(start = 10.dp),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.primary,
			)
		}
	} else {
		OutlinedButton(onClick = onRequestNotifications, modifier = Modifier.fillMaxWidth()) {
			Text(stringResource(R.string.setup_notifications_enable))
		}
	}
	Spacer(Modifier.height(16.dp))
	Text(
		stringResource(R.string.setup_notifications_optional),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun CredentialStep(onCreateCredential: (CredentialKind, CharArray) -> Unit) {
	var kind by remember { mutableStateOf(CredentialKind.PIN) }
	var setting by remember { mutableStateOf(false) }

	StepTitle(
		stringResource(R.string.setup_credential_title),
		stringResource(R.string.setup_credential_body),
	)
	Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
		CredentialKind.entries.forEach { option ->
			SettingsRow(
				title = CredentialWords.label(option),
				summary = CredentialWords.hint(option),
				trailing = { SelectionMark(kind == option) },
				onClick = { kind = option },
			)
		}
		Spacer(Modifier.height(20.dp))
		Button(onClick = { setting = true }, shape = CircleShape) {
			Text(CredentialWords.newTitle(kind), modifier = Modifier.padding(horizontal = 12.dp))
		}
		Spacer(Modifier.height(8.dp))
		TextButton(onClick = {}, enabled = false) {
			Text(
				stringResource(R.string.setup_pin_reminder),
				style = MaterialTheme.typography.bodySmall,
				textAlign = TextAlign.Center,
			)
		}
	}

	if (setting) {
		CredentialPrompt(
			kind = kind,
			title = CredentialWords.newTitle(kind),
			subtitle = CredentialWords.hint(kind),
			confirmLabel = stringResource(R.string.setup_finish),
			confirmEntry = true,
			onConfirm = {
				setting = false
				onCreateCredential(kind, it)
			},
			onDismiss = { setting = false },
		)
	}
}

@Composable
private fun DecoyType.setupLabel(): String = when (this) {
	DecoyType.NONE -> stringResource(R.string.decoy_none)
	DecoyType.HABITS -> stringResource(R.string.decoy_habits)
	DecoyType.CALENDAR -> stringResource(R.string.decoy_calendar)
	DecoyType.CALCULATOR -> stringResource(R.string.decoy_calculator)
}

@Composable
private fun EntryMethod.setupTitle(): String = when (this) {
	EntryMethod.TITLE_HOLD -> stringResource(R.string.entry_title_hold)
	EntryMethod.CORNER_KNOCK -> stringResource(R.string.entry_corner_knock)
	EntryMethod.NATURAL_HOLD -> stringResource(R.string.entry_natural_hold)
}

@Composable
private fun EntryMethod.setupSummary(): String = when (this) {
	EntryMethod.TITLE_HOLD -> stringResource(R.string.entry_title_hold_summary)
	EntryMethod.CORNER_KNOCK -> stringResource(R.string.entry_corner_knock_summary)
	EntryMethod.NATURAL_HOLD -> stringResource(R.string.entry_natural_hold_summary)
}

@Composable
private fun HidingPreference.setupTitle(): String = when (this) {
	HidingPreference.AUTO -> stringResource(R.string.mode_auto)
	HidingPreference.ROOT -> stringResource(R.string.root_mode)
	HidingPreference.ALL_FILES -> stringResource(R.string.all_files_mode)
	HidingPreference.SAF -> stringResource(R.string.saf_mode)
}

@Composable
private fun HidingPreference.setupSummary(available: Set<HideMethod>): String = when (this) {
	HidingPreference.AUTO -> stringResource(R.string.mode_auto_summary)

	HidingPreference.ROOT -> stringResource(
		if (HideMethod.ROOT_CHMOD in available) R.string.root_mode_available else R.string.root_mode_unavailable,
	)

	HidingPreference.ALL_FILES -> stringResource(
		if (HideMethod.PRIVATE_MOVE in available) R.string.all_files_available else R.string.all_files_unavailable,
	)

	HidingPreference.SAF -> stringResource(R.string.saf_mode_summary)
}

@Composable
private fun HideMethod.shortLabel(): String = when (this) {
	HideMethod.ROOT_CHMOD -> stringResource(R.string.root_mode)
	HideMethod.PRIVATE_MOVE -> stringResource(R.string.all_files_mode)
	HideMethod.DOT_RENAME -> stringResource(R.string.saf_mode)
}

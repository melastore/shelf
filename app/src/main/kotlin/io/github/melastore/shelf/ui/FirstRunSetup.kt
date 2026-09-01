package io.github.melastore.shelf.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Setting up inside the disguise would mean explaining the disguise from within it, and picking a
 * credential through a gesture nobody has been told about yet. So the first launch is honest and the
 * app only puts its face on once there is something behind it. Having a credential is what retires
 * this, so an upgrade with one already goes straight to the decoy as before.
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
	onStep: (Int) -> Unit,
) {
	val steps = SetupStep.entries
	// Seeded from the stored step, so a fresh process picks up where the last one stopped.
	// rememberSaveable on its own only covers a configuration change.
	var index by rememberSaveable { mutableIntStateOf(state.setupStep.coerceIn(0, steps.lastIndex)) }
	val step = steps[index.coerceIn(0, steps.lastIndex)]
	val goTo: (Int) -> Unit = {
		index = it.coerceIn(0, steps.lastIndex)
		onStep(index)
	}

	Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
		Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
			SetupHeader(
				step = index + 1,
				total = steps.size,
				onBack = { if (index > 0) goTo(index - 1) },
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

			// The credential step carries its own confirm button. "Continue" would be wrong for a
			// keypad that has to be entered twice before it means anything.
			if (step != SetupStep.CREDENTIAL) {
				Row(
					Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
					horizontalArrangement = Arrangement.End,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Button(onClick = { goTo(index + 1) }, shape = CircleShape) {
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
	// hides folders is easily taken for one that encrypts them, and people act on that mistake.
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
				label = decoy.label(),
				selected = selected == decoy,
				modifier = Modifier.weight(1f),
				icon = { DecoyBadge(decoy) },
				onClick = { onDecoy(decoy) },
			)
		}
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
					title = method.title(),
					summary = method.summary(),
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
					title = preference.title(),
					summary = preference.summary(state.availableMethods),
					trailing = { SelectionMark(state.hidingPreference == preference) },
					onClick = { onHidingPreference(preference) },
				)
			}
		}
	}
	Spacer(Modifier.height(12.dp))
	// Root is only probed when asked for. An unannounced su prompt during setup is alarming, and on
	// an unrooted phone it is a dialog about something the owner never asked for.
	OutlinedButton(onClick = onCheckMethods, modifier = Modifier.fillMaxWidth()) {
		Text(stringResource(R.string.setup_check_root))
	}
	if (state.availableMethods.isNotEmpty()) {
		Spacer(Modifier.height(12.dp))
		// Resolved before joining: joinToString's transform is not a composable context.
		val names = state.availableMethods.map { it.label() }
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
	// Said here rather than buried in Settings. This is the one permission that shows up in Android's
	// own list, so anyone relying on the disguise should know before granting it.
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
		Spacer(Modifier.height(12.dp))
		Text(
			stringResource(R.string.setup_pin_reminder),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
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

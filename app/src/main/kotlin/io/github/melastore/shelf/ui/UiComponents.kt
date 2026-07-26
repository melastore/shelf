package io.github.melastore.shelf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.EntryMethod

@Composable
fun PinDialog(
	title: String,
	confirmLabel: String,
	confirmEntry: Boolean = false,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	var pin by remember { mutableStateOf("") }
	var confirmation by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				SecretField(
					value = pin,
					onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
					label = stringResource(R.string.pin),
					keyboardType = KeyboardType.NumberPassword,
				)
				if (confirmEntry) {
					SecretField(
						value = confirmation,
						onValueChange = {
							confirmation = it.filter(Char::isDigit).take(MAX_PIN_LENGTH)
						},
						label = stringResource(R.string.confirm_pin),
						keyboardType = KeyboardType.NumberPassword,
					)
				}
				Text(
					stringResource(R.string.pin_hint),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(pin.toCharArray()) },
				enabled = pin.length >= MIN_PIN_LENGTH && (!confirmEntry || pin == confirmation),
			) { Text(confirmLabel) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

@Composable
fun PassphraseDialog(
	title: String,
	confirmLabel: String,
	confirmEntry: Boolean = false,
	minimumLength: Int = 1,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	var text by remember { mutableStateOf("") }
	var confirmation by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				SecretField(
					value = text,
					onValueChange = { text = it },
					label = stringResource(R.string.passphrase),
					keyboardType = KeyboardType.Password,
				)
				if (confirmEntry) {
					SecretField(
						value = confirmation,
						onValueChange = { confirmation = it },
						label = stringResource(R.string.confirm_passphrase),
						keyboardType = KeyboardType.Password,
					)
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(text.toCharArray()) },
				enabled = text.length >= minimumLength && (!confirmEntry || text == confirmation),
			) { Text(confirmLabel) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

@Composable
fun ChangePinDialog(
	legacyCurrent: Boolean,
	onConfirm: (CharArray, CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	var current by remember { mutableStateOf("") }
	var pin by remember { mutableStateOf("") }
	var confirmation by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.change_vault_pin)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				SecretField(
					value = current,
					onValueChange = {
						current = if (legacyCurrent) it else it.filter(Char::isDigit).take(MAX_PIN_LENGTH)
					},
					label = stringResource(
						if (legacyCurrent) R.string.current_passphrase else R.string.current_pin,
					),
					keyboardType = if (legacyCurrent) KeyboardType.Password else KeyboardType.NumberPassword,
				)
				SecretField(
					value = pin,
					onValueChange = { pin = it.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
					label = stringResource(R.string.new_pin),
					keyboardType = KeyboardType.NumberPassword,
				)
				SecretField(
					value = confirmation,
					onValueChange = { confirmation = it.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
					label = stringResource(R.string.confirm_pin),
					keyboardType = KeyboardType.NumberPassword,
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(current.toCharArray(), pin.toCharArray()) },
				enabled = current.isNotEmpty() && pin.length >= MIN_PIN_LENGTH && pin == confirmation,
			) { Text(stringResource(R.string.save)) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

@Composable
private fun SecretField(
	value: String,
	onValueChange: (String) -> Unit,
	label: String,
	keyboardType: KeyboardType,
) {
	OutlinedTextField(
		value = value,
		onValueChange = onValueChange,
		modifier = Modifier.fillMaxWidth(),
		singleLine = true,
		label = { Text(label) },
		visualTransformation = PasswordVisualTransformation(),
		keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
	)
}

/** An invisible five-tap target in the top-right corner. */
@Composable
fun CornerKnockTarget(entryMethod: EntryMethod, onTriggered: () -> Unit, modifier: Modifier = Modifier) {
	if (entryMethod != EntryMethod.CORNER_KNOCK) return
	var taps by remember { mutableIntStateOf(0) }
	var lastTap by remember { mutableLongStateOf(0L) }
	val interaction = remember { MutableInteractionSource() }
	Box(
		modifier = modifier
			.size(64.dp)
			.clip(CircleShape)
			.clickable(interactionSource = interaction, indication = null) {
				val now = System.currentTimeMillis()
				taps = if (now - lastTap <= KNOCK_GAP_MILLIS) taps + 1 else 1
				lastTap = now
				if (taps >= REQUIRED_KNOCKS) {
					taps = 0
					onTriggered()
				}
			},
	)
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
	Column(
		modifier = modifier.padding(32.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Box(
			modifier = Modifier.size(48.dp).clip(CircleShape)
				.background(MaterialTheme.colorScheme.primaryContainer),
		)
		Spacer(Modifier.height(16.dp))
		Text(title, style = MaterialTheme.typography.titleLarge)
		Spacer(Modifier.height(6.dp))
		Text(
			body,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 12
private const val REQUIRED_KNOCKS = 5
private const val KNOCK_GAP_MILLIS = 650L

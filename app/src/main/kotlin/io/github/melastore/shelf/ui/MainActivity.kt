package io.github.melastore.shelf.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.melastore.shelf.R

class MainActivity : ComponentActivity() {

	private val viewModel: ShelfViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			ShelfTheme {
				val state by viewModel.state.collectAsStateWithLifecycle()
				val snackbar = remember { SnackbarHostState() }
				var showCredential by remember { mutableStateOf(false) }
				val privateScreen = state.screen != Screen.DECOY

				LaunchedEffect(privateScreen) {
					if (privateScreen) {
						showCredential = false
						window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
					} else {
						window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
					}
				}
				LaunchedEffect(state.message) {
					state.message?.let {
						snackbar.showSnackbar(it)
						viewModel.consumeMessage()
					}
				}

				BackHandler(enabled = state.screen == Screen.VAULT) { viewModel.lockVault() }
				BackHandler(enabled = state.screen == Screen.SETTINGS) { viewModel.closeSettings() }

				Box(Modifier.fillMaxSize()) {
					if (state.ready) {
						when (state.screen) {
							Screen.DECOY -> DecoyScreen(state, viewModel) { showCredential = true }
							Screen.VAULT, Screen.SETTINGS -> PrivateArea(state, viewModel)
						}
					}
					SnackbarHost(
						hostState = snackbar,
						modifier = Modifier.align(Alignment.BottomCenter)
							.navigationBarsPadding().padding(12.dp),
					)
				}

				if (showCredential) {
					when {
						!state.credentialSet -> PinDialog(
							title = stringResource(R.string.create_vault_pin),
							confirmLabel = stringResource(R.string.continue_action),
							confirmEntry = true,
							onConfirm = { viewModel.setVaultPin(it); showCredential = false },
							onDismiss = { showCredential = false },
						)
						state.vaultUsesPin -> PinDialog(
							title = stringResource(R.string.enter_pin),
							confirmLabel = stringResource(R.string.unlock),
							onConfirm = { viewModel.unlockVault(it); showCredential = false },
							onDismiss = { showCredential = false },
						)
						else -> PassphraseDialog(
							title = stringResource(R.string.enter_current_passphrase),
							confirmLabel = stringResource(R.string.unlock),
							onConfirm = { viewModel.unlockVault(it); showCredential = false },
							onDismiss = { showCredential = false },
						)
					}
				}
			}
		}
	}

	override fun onStop() {
		super.onStop()
		if (!isChangingConfigurations) viewModel.onMovedToBackground()
	}
}

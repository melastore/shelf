package io.github.melastore.shelf.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.security.BiometricAuth

class MainActivity : ComponentActivity() {

	private val viewModel: ShelfViewModel by viewModels()
	private var biometricPrompt: CancellationSignal? = null
	private var showCredential by mutableStateOf(false)
	private val screenReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			when (intent.action) {
				Intent.ACTION_SCREEN_OFF -> viewModel.onScreenTurnedOff()
				Intent.ACTION_SCREEN_ON -> viewModel.onScreenTurnedOn()
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		ContextCompat.registerReceiver(
			this,
			screenReceiver,
			IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) },
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		enableEdgeToEdge()
		setContent {
			val state by viewModel.state.collectAsStateWithLifecycle()
			// Setup runs before there is a credential, and therefore before there is anything to
			// disguise. It gets the private palette for the same reason it gets FLAG_SECURE.
			val firstRun = state.ready && !state.credentialSet
			val privateScreen = state.screen != Screen.DECOY || firstRun

			// A null decoy selects the private palette, so the two sides never look alike.
			ShelfTheme(decoy = state.decoy.takeUnless { privateScreen }) {
				val snackbar = remember { SnackbarHostState() }
				val biometricTitle = stringResource(R.string.biometric_prompt_title)
				val biometricSubtitle = stringResource(R.string.biometric_prompt_subtitle)
				val usePin = stringResource(R.string.use_pin)
				val enableBiometricTitle = stringResource(R.string.enable_biometric_prompt_title)
				val enableBiometricSubtitle = stringResource(R.string.enable_biometric_prompt_subtitle)
				val notificationPermission = rememberLauncherForActivityResult(
					ActivityResultContracts.RequestPermission(),
				) { granted -> viewModel.setQuickLockNotification(granted) }
				val allFilesAccess = rememberLauncherForActivityResult(
					ActivityResultContracts.StartActivityForResult(),
				) {
					viewModel.onPickerResult()
					viewModel.refreshCapabilities()
				}

				fun requestPrivateEntry() {
					if (!state.biometricEnabled || !BiometricAuth.isAvailable(this@MainActivity)) {
						showCredential = true
						return
					}
					biometricPrompt?.cancel()
					biometricPrompt = BiometricAuth.authenticate(
						activity = this@MainActivity,
						title = biometricTitle,
						subtitle = biometricSubtitle,
						negativeButton = usePin,
					) { outcome, credential ->
						when (outcome) {
							BiometricAuth.Outcome.SUCCEEDED -> if (credential != null) {
								viewModel.unlockWithBiometric(credential) { showCredential = true }
							} else {
								showCredential = true
							}

							// A changed enrolment invalidates the key, so the PIN takes over and the
							// setting is turned off rather than silently doing nothing next time.
							BiometricAuth.Outcome.ENROLMENT_CHANGED -> {
								viewModel.onBiometricEnrolmentChanged()
								showCredential = true
							}

							BiometricAuth.Outcome.CREDENTIAL_MISSING -> {
								viewModel.onBiometricCredentialMissing()
								showCredential = true
							}

							BiometricAuth.Outcome.FALLBACK -> showCredential = true
						}
					}
				}

				fun changeBiometric(enabled: Boolean) {
					if (!enabled) {
						viewModel.setBiometricEnabled(false)
						return
					}
					val credential = viewModel.biometricEnrollmentCredential() ?: return
					biometricPrompt?.cancel()
					biometricPrompt = BiometricAuth.enroll(
						activity = this@MainActivity,
						credential = credential,
						title = enableBiometricTitle,
						subtitle = enableBiometricSubtitle,
						negativeButton = getString(R.string.cancel),
					) { outcome ->
						if (outcome == BiometricAuth.Outcome.SUCCEEDED) viewModel.setBiometricEnabled(true)
					}
				}

				fun changeQuickLock(enabled: Boolean) {
					if (
						enabled && Build.VERSION.SDK_INT >= 33 &&
						ContextCompat.checkSelfPermission(
							this@MainActivity,
							Manifest.permission.POST_NOTIFICATIONS,
						) != PackageManager.PERMISSION_GRANTED
					) {
						notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
					} else {
						viewModel.setQuickLockNotification(enabled)
					}
				}

				// The credential prompt is never exempt. Allowing screenshots is a decision made inside
				// the private space, and the keypad is the one screen that is on show before anyone has
				// proved they are entitled to make it.
				LaunchedEffect(privateScreen, showCredential, state.allowScreenshots) {
					if (showCredential || (privateScreen && !state.allowScreenshots)) {
						window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
					} else {
						window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
					}
				}
				LaunchedEffect(state.message) {
					state.message?.let {
						snackbar.showSnackbar(it.resolve(resources))
						viewModel.consumeMessage()
					}
				}
				// Titled as the disguise, because this is on screen at exactly the moment someone else is
				// most likely to be looking at the phone.
				val notificationLabel = stringResource(
					when (state.decoy) {
						// Undisguised, the notification may as well carry the app's own name.
						DecoyType.NONE -> R.string.launcher_shelf

						DecoyType.HABITS -> R.string.launcher_habits

						DecoyType.CALENDAR -> R.string.launcher_calendar

						DecoyType.CALCULATOR -> R.string.launcher_calculator
					},
				)
				val notificationIcon = when (state.decoy) {
					DecoyType.NONE -> R.drawable.ic_launcher_shelf_foreground
					DecoyType.HABITS -> R.drawable.ic_launcher_foreground
					DecoyType.CALENDAR -> R.drawable.ic_mono_calendar
					DecoyType.CALCULATOR -> R.drawable.ic_mono_calculator
				}
				// Kept up while a folder is sitting in the open, not only while the private space is on
				// screen: unhiding something and then closing the app is precisely when a one-tap way to
				// put it back is worth having, and the app is no longer in front of you to offer it.
				val offerHide = state.quickLockNotification &&
					(privateScreen || state.exposedFolders > 0)
				LaunchedEffect(offerHide, notificationLabel, notificationIcon) {
					if (offerHide) {
						QuickLockNotification.show(
							this@MainActivity,
							notificationLabel,
							notificationIcon,
						)
					} else {
						QuickLockNotification.cancel(this@MainActivity)
					}
				}

				BackHandler(enabled = state.screen == Screen.VAULT) { viewModel.lockVault() }
				BackHandler(enabled = state.screen == Screen.SETTINGS) { viewModel.closeSettings() }

				Box(Modifier.fillMaxSize()) {
					if (state.ready) {
						when {
							firstRun -> FirstRunSetup(
								state = state,
								onDecoy = viewModel::setDecoy,
								onEntryMethod = viewModel::setEntryMethod,
								onHidingPreference = viewModel::setHidingPreference,
								onCheckMethods = viewModel::refreshCapabilities,
								onRequestAllFiles = {
									viewModel.expectExternalPicker()
									allFilesAccess.launch(
										Intent(
											Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
											Uri.fromParts("package", packageName, null),
										),
									)
								},
								onRequestNotifications = { changeQuickLock(true) },
								onCreatePin = viewModel::setVaultPin,
							)

							state.screen == Screen.DECOY ->
								DecoyScreen(state, viewModel, ::requestPrivateEntry)

							else -> PrivateArea(
								state,
								viewModel,
								::changeBiometric,
								::changeQuickLock,
								viewModel::setAllowScreenshots,
								::requestPrivateEntry,
							)
						}
					}
					SnackbarHost(
						hostState = snackbar,
						modifier = Modifier.align(Alignment.BottomCenter)
							.navigationBarsPadding().padding(12.dp),
					)
				}

				// Creating a credential is the setup flow's job now, so this only ever asks for one.
				if (showCredential && state.credentialSet) {
					when {
						state.vaultUsesPin -> PinPrompt(
							title = stringResource(R.string.enter_pin),
							subtitle = stringResource(R.string.enter_pin_subtitle),
							confirmLabel = stringResource(R.string.unlock),
							onConfirm = {
								viewModel.unlockVault(it)
								showCredential = false
							},
							onDismiss = { showCredential = false },
						)

						else -> PassphraseDialog(
							title = stringResource(R.string.enter_current_passphrase),
							confirmLabel = stringResource(R.string.unlock),
							onConfirm = {
								viewModel.unlockVault(it)
								showCredential = false
							},
							onDismiss = { showCredential = false },
						)
					}
				}
			}
		}
	}

	override fun onStop() {
		super.onStop()
		if (!isChangingConfigurations) {
			showCredential = false
			biometricPrompt?.cancel()
			biometricPrompt = null
			viewModel.onMovedToBackground()
		}
	}

	override fun onResume() {
		super.onResume()
		viewModel.onMovedToForeground()
		viewModel.refreshBiometricAvailability()
		// A hide may have happened in a receiver while this was in the background.
		viewModel.refreshExposure()
	}

	// The notification is deliberately not cancelled here. It reflects folders left in the open, which
	// outlasts this activity by design, and taking it away on exit would remove the way back exactly
	// when the app is no longer around to offer one.
	override fun onDestroy() {
		biometricPrompt?.cancel()
		biometricPrompt = null
		unregisterReceiver(screenReceiver)
		super.onDestroy()
	}
}

package io.github.melastore.shelf.ui

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.Settings
import android.view.HapticFeedbackConstants
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.melastore.shelf.R
import io.github.melastore.shelf.security.BiometricAuth

class MainActivity : ComponentActivity() {

	private val viewModel: ShelfViewModel by viewModels()
	private var biometricPrompt: CancellationSignal? = null
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
			var showCredential by rememberSaveable { mutableStateOf(false) }
			var showFakeCrash by rememberSaveable { mutableStateOf(false) }
			// A half-typed credential should survive a rotation, so the flag lives in the composition
			// rather than on the activity, which a rotation destroys. That puts it out of reach of
			// onStop, so the trip to the background is handled here instead: isChangingConfigurations
			// is true for the stop a rotation causes and false for the one leaving the app causes.
			// The crash dialog is left alone. It is part of the disguise and gives nothing away.
			LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
				if (!isChangingConfigurations) showCredential = false
			}
			// Setup runs before there is a credential, so there is nothing to disguise yet. It gets the
			// private palette for the same reason it gets FLAG_SECURE.
			val firstRun = state.ready && !state.credentialSet
			val privateScreen = state.screen != Screen.DECOY || firstRun

			// A null decoy selects the private palette, so the two sides never look alike.
			ShelfTheme(decoy = state.decoy.takeUnless { privateScreen }, mode = state.themeMode) {
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

				fun beginPrivateEntry() {
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

							// A changed enrolment invalidates the key, so the credential takes over and
							// the setting is turned off rather than quietly doing nothing next time.
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

				// The credential prompt is never exempt. Allowing screenshots is a decision taken inside
				// the private space, and the keypad is the one screen shown before anyone has proved
				// they are entitled to take it.
				LaunchedEffect(privateScreen, showCredential, state.allowScreenshots) {
					if (showCredential || (privateScreen && !state.allowScreenshots)) {
						window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
					} else {
						window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
					}
				}
				// Off the recents list, and no snapshot behind it. Both are per-task settings the system
				// forgets on relaunch, so they are reapplied here rather than set once in the manifest.
				LaunchedEffect(state.hideFromRecents) {
					applyRecentsVisibility(state.hideFromRecents)
				}
				LaunchedEffect(state.message) {
					state.message?.let {
						snackbar.showSnackbar(it.resolve(resources))
						viewModel.consumeMessage()
					}
				}
				// With the crash dialog on, the gesture opens that first and it decides whether the real
				// prompt ever appears.
				fun requestPrivateEntry() {
					if (state.fakeCrash) showFakeCrash = true else beginPrivateEntry()
				}

				// Undisguised, both fall back to the app's own name and icon.
				val (notificationLabelId, notificationIcon) = state.decoy.identity()
				val notificationLabel = stringResource(notificationLabelId)
				// Exposure is the whole condition, and it outlives the private space being on screen.
				// That is the point: unhiding something then closing the app is exactly when a one-tap
				// way to put it back is worth having. With everything already hidden the action has
				// nothing to do, and the notification would only announce the app for no reason.
				val offerHide = state.quickLockNotification && state.exposedFolders > 0
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

				val sensorManager = remember { getSystemService(SensorManager::class.java) }
				val accelerometer = remember { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
				val flipToHideActive = state.screen != Screen.DECOY && state.flipToHide
				val lifecycleOwner = LocalLifecycleOwner.current

				DisposableEffect(lifecycleOwner, flipToHideActive, accelerometer) {
					if (!flipToHideActive || accelerometer == null || sensorManager == null) {
						return@DisposableEffect onDispose {}
					}
					var registered = false
					var flipped = false
					val listener = object : SensorEventListener {
						override fun onSensorChanged(event: SensorEvent) {
							val z = event.values[2]
							if (z < -7.0f && !flipped) {
								flipped = true
								window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
								viewModel.autoHideAndClose()
							} else if (z > -3.0f) {
								flipped = false
							}
						}

						override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
					}
					val observer = LifecycleEventObserver { _, event ->
						when (event) {
							Lifecycle.Event.ON_RESUME -> if (!registered) {
								sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
								registered = true
							}

							Lifecycle.Event.ON_PAUSE -> if (registered) {
								sensorManager.unregisterListener(listener)
								registered = false
							}

							else -> Unit
						}
					}
					lifecycleOwner.lifecycle.addObserver(observer)
					if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !registered) {
						sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
						registered = true
					}
					onDispose {
						lifecycleOwner.lifecycle.removeObserver(observer)
						if (registered) sensorManager.unregisterListener(listener)
					}
				}

				BackHandler(enabled = state.screen == Screen.VAULT) { viewModel.lockVault() }
				BackHandler(enabled = state.screen == Screen.SETTINGS) { viewModel.closeSettings() }

				Box(Modifier.fillMaxSize()) {
					if (state.ready) {
						when {
							firstRun -> FirstRunSetup(
								state = state,
								onDecoy = viewModel::chooseDecoy,
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
								onCreateCredential = viewModel::setVaultCredential,
								onStep = viewModel::setSetupStep,
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

				if (showFakeCrash) {
					FakeCrashDialog(
						appName = notificationLabel,
						onUnlock = {
							showFakeCrash = false
							beginPrivateEntry()
						},
						onClose = {
							showFakeCrash = false
							finishAndRemoveTask()
						},
					)
				}

				// Creating a credential belongs to setup, so this only ever asks for one.
				if (showCredential && state.credentialSet) {
					CredentialPrompt(
						kind = state.credentialKind,
						title = CredentialWords.enterTitle(state.credentialKind),
						subtitle = CredentialWords.enterSubtitle(state.credentialKind),
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

	// The credential prompt is cleared by the ON_STOP effect in setContent, not here: it is
	// composition state now so that a rotation cannot drop it.
	override fun onStop() {
		super.onStop()
		if (!isChangingConfigurations) {
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

	/**
	 * Takes the task out of recents and stops the system keeping a picture of it.
	 *
	 * Excluding the task removes the entry. The screenshot call covers the moment between going to
	 * the background and the entry disappearing, and launchers that show a preview anyway. Neither
	 * replaces FLAG_SECURE, which is set separately.
	 */
	private fun applyRecentsVisibility(hidden: Boolean) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			runCatching { setRecentsScreenshotEnabled(!hidden) }
		}
		val manager = getSystemService(ActivityManager::class.java) ?: return
		runCatching { manager.appTasks.forEach { it.setExcludeFromRecents(hidden) } }
	}

	// The notification is not cancelled here on purpose. It tracks folders left in the open, which
	// outlasts this activity by design, and dropping it on exit would take the way back away exactly
	// when the app is no longer around to offer one.
	override fun onDestroy() {
		biometricPrompt?.cancel()
		biometricPrompt = null
		unregisterReceiver(screenReceiver)
		super.onDestroy()
	}
}

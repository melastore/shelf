package io.github.melastore.shelf.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.melastore.shelf.R
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.data.EphemeralMediaDataSource
import io.github.melastore.shelf.data.EphemeralMediaItem
import io.github.melastore.shelf.data.FileLocker
import javax.crypto.SecretKey
import kotlinx.coroutines.delay

@Composable
fun EphemeralVideoPlayerDialog(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, onDismiss: () -> Unit,) {
	var isPlaying by remember { mutableStateOf(false) }
	var isPrepared by remember { mutableStateOf(false) }
	var currentPositionMs by remember { mutableIntStateOf(0) }
	var durationMs by remember { mutableIntStateOf(0) }
	var videoAspectRatio by remember { mutableFloatStateOf(16f / 9f) }
	var showControls by remember { mutableStateOf(true) }
	var hasError by remember { mutableStateOf(false) }
	var isDraggingSlider by remember { mutableStateOf(false) }
	var sliderPositionMs by remember { mutableFloatStateOf(0f) }

	val mediaPlayer = remember { MediaPlayer() }

	val dataSource = remember(item) {
		val trailer = FileLocker.readTrailer(item.target)
		val (slice, totalSize) = if (trailer != null) {
			val key = keyFor(trailer.salt)
			val decryptedSlice = if (key != null) {
				runCatching {
					HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, key)
				}.getOrNull()
			} else {
				null
			}
			(decryptedSlice ?: byteArrayOf()) to trailer.originalSize
		} else {
			byteArrayOf() to item.target.size()
		}
		EphemeralMediaDataSource(item.target, slice, totalSize)
	}

	DisposableEffect(item) {
		try {
			mediaPlayer.setDataSource(dataSource)
			mediaPlayer.setOnPreparedListener { mp ->
				durationMs = mp.duration
				isPrepared = true
				mp.start()
				isPlaying = true
			}
			mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
				if (width > 0 && height > 0) {
					videoAspectRatio = width.toFloat() / height.toFloat()
				}
			}
			mediaPlayer.setOnCompletionListener {
				isPlaying = false
				currentPositionMs = durationMs
			}
			mediaPlayer.setOnErrorListener { _, _, _ ->
				hasError = true
				true
			}
			mediaPlayer.prepareAsync()
		} catch (_: Exception) {
			hasError = true
		}

		onDispose {
			try {
				if (mediaPlayer.isPlaying) mediaPlayer.stop()
				mediaPlayer.reset()
				mediaPlayer.release()
				dataSource.close()
			} catch (_: Exception) {}
		}
	}

	LaunchedEffect(isPlaying, isDraggingSlider) {
		while (isPlaying && !isDraggingSlider) {
			if (isPrepared) {
				currentPositionMs = mediaPlayer.currentPosition
			}
			delay(250)
		}
	}

	LaunchedEffect(showControls, isPlaying) {
		if (showControls && isPlaying) {
			delay(3500)
			showControls = false
		}
	}

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			securePolicy = SecureFlagPolicy.SecureOn,
		),
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(Color.Black)
				.clickable(
					interactionSource = remember { MutableInteractionSource() },
					indication = null,
					onClick = { showControls = !showControls },
				),
			contentAlignment = Alignment.Center,
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(videoAspectRatio),
				contentAlignment = Alignment.Center,
			) {
				AndroidView(
					factory = { ctx ->
						TextureView(ctx).apply {
							surfaceTextureListener = object : TextureView.SurfaceTextureListener {
								override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
									mediaPlayer.setSurface(Surface(st))
								}

								override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

								override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
									mediaPlayer.setSurface(null)
									return true
								}

								override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
							}
						}
					},
					modifier = Modifier.fillMaxSize(),
				)
			}

			if (!isPrepared && !hasError) {
				CircularProgressIndicator(color = Color.White)
			}

			if (hasError) {
				Surface(
					shape = MaterialTheme.shapes.medium,
					color = MaterialTheme.colorScheme.errorContainer,
					modifier = Modifier.padding(24.dp),
				) {
					Text(
						text = stringResource(R.string.video_playback_error),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onErrorContainer,
						modifier = Modifier.padding(16.dp),
					)
				}
			}

			AnimatedVisibility(
				visible = showControls,
				enter = fadeIn(),
				exit = fadeOut(),
				modifier = Modifier.fillMaxSize(),
			) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(Color.Black.copy(alpha = 0.45f)),
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 16.dp, start = 8.dp, end = 16.dp)
							.align(Alignment.TopStart),
						verticalAlignment = Alignment.CenterVertically,
					) {
						IconButton(onClick = onDismiss) {
							Icon(
								imageVector = Icons.Filled.Close,
								contentDescription = stringResource(R.string.close_vault),
								tint = Color.White,
							)
						}
						Spacer(Modifier.width(8.dp))
						Text(
							text = item.name,
							style = MaterialTheme.typography.titleMedium,
							color = Color.White,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}

					Row(
						modifier = Modifier.align(Alignment.Center),
						horizontalArrangement = Arrangement.spacedBy(28.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						IconButton(
							onClick = {
								if (isPrepared) {
									val target = (mediaPlayer.currentPosition - 10000).coerceAtLeast(0)
									mediaPlayer.seekTo(target)
									currentPositionMs = target
								}
							},
							modifier = Modifier.size(48.dp),
						) {
							Icon(
								painter = painterResource(R.drawable.ic_replay_10),
								contentDescription = stringResource(R.string.rewind_10),
								tint = Color.White,
								modifier = Modifier.size(36.dp),
							)
						}

						Surface(
							onClick = {
								if (isPrepared) {
									if (isPlaying) {
										mediaPlayer.pause()
										isPlaying = false
									} else {
										if (currentPositionMs >= durationMs && durationMs > 0) {
											mediaPlayer.seekTo(0)
											currentPositionMs = 0
										}
										mediaPlayer.start()
										isPlaying = true
									}
								}
							},
							shape = CircleShape,
							color = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(64.dp),
						) {
							Box(contentAlignment = Alignment.Center) {
								Icon(
									painter = painterResource(
										if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
									),
									contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
									tint = MaterialTheme.colorScheme.onPrimary,
									modifier = Modifier.size(32.dp),
								)
							}
						}

						IconButton(
							onClick = {
								if (isPrepared && durationMs > 0) {
									val target = (mediaPlayer.currentPosition + 10000).coerceAtMost(durationMs)
									mediaPlayer.seekTo(target)
									currentPositionMs = target
								}
							},
							modifier = Modifier.size(48.dp),
						) {
							Icon(
								painter = painterResource(R.drawable.ic_forward_10),
								contentDescription = stringResource(R.string.forward_10),
								tint = Color.White,
								modifier = Modifier.size(36.dp),
							)
						}
					}

					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
							.align(Alignment.BottomCenter),
					) {
						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							val displayedPos = if (isDraggingSlider) sliderPositionMs.toInt() else currentPositionMs
							Text(
								text = formatTimestamp(displayedPos),
								style = MaterialTheme.typography.bodySmall,
								color = Color.White,
							)
							Text(
								text = formatTimestamp(durationMs),
								style = MaterialTheme.typography.bodySmall,
								color = Color.White.copy(alpha = 0.7f),
							)
						}

						val maxSliderValue = durationMs.toFloat().coerceAtLeast(1f)
						val currentSliderValue = if (isDraggingSlider) {
							sliderPositionMs
						} else {
							currentPositionMs.toFloat()
						}.coerceIn(0f, maxSliderValue)

						Slider(
							value = currentSliderValue,
							valueRange = 0f..maxSliderValue,
							onValueChange = { pos ->
								isDraggingSlider = true
								sliderPositionMs = pos
							},
							onValueChangeFinished = {
								isDraggingSlider = false
								if (isPrepared) {
									mediaPlayer.seekTo(sliderPositionMs.toInt())
									currentPositionMs = sliderPositionMs.toInt()
								}
							},
							colors = SliderDefaults.colors(
								thumbColor = MaterialTheme.colorScheme.primary,
								activeTrackColor = MaterialTheme.colorScheme.primary,
								inactiveTrackColor = Color.White.copy(alpha = 0.3f),
							),
						)
					}
				}
			}
		}
	}
}

private fun formatTimestamp(ms: Int): String {
	val totalSeconds = (ms / 1000).coerceAtLeast(0)
	val minutes = totalSeconds / 60
	val seconds = totalSeconds % 60
	val hours = minutes / 60
	val secStr = if (seconds < 10) "0$seconds" else "$seconds"
	return if (hours > 0) {
		val minStr = if (minutes % 60 < 10) "0${minutes % 60}" else "${minutes % 60}"
		"$hours:$minStr:$secStr"
	} else {
		val minStr = if (minutes < 10) "0$minutes" else "$minutes"
		"$minStr:$secStr"
	}
}

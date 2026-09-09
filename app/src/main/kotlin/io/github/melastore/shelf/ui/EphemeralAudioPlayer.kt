package io.github.melastore.shelf.ui

import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.EphemeralMediaDataSource
import io.github.melastore.shelf.data.EphemeralMediaItem
import io.github.melastore.shelf.data.EphemeralMediaLoader
import io.github.melastore.shelf.data.EphemeralThumbnail
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun EphemeralAudioPlayerDialog(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, onDismiss: () -> Unit,) {
	var isPlaying by remember(item) { mutableStateOf(false) }
	var isPrepared by remember(item) { mutableStateOf(false) }
	var currentPositionMs by remember(item) { mutableIntStateOf(0) }
	var durationMs by remember(item) { mutableIntStateOf(0) }
	var hasError by remember(item) { mutableStateOf(false) }
	var isDraggingSlider by remember(item) { mutableStateOf(false) }
	var sliderPositionMs by remember(item) { mutableFloatStateOf(0f) }

	val mediaPlayer = remember(item) { MediaPlayer() }
	DisposableEffect(mediaPlayer) {
		onDispose {
			runCatching {
				if (mediaPlayer.isPlaying) mediaPlayer.stop()
			}
			runCatching {
				mediaPlayer.reset()
				mediaPlayer.release()
			}
		}
	}

	val thumbnail = produceState<EphemeralThumbnail?>(null, item) {
		value = withContext(Dispatchers.IO) {
			EphemeralMediaLoader.loadAudioThumbnail(item.target, keyFor, maxDimension = 512)
		}
	}.value

	val dataSource = produceState<EphemeralMediaDataSource?>(null, item) {
		value = withContext(Dispatchers.IO) {
			val head = EphemeralMediaLoader.plaintextHead(item.target, keyFor)
			if (head == null) {
				withContext(Dispatchers.Main) { hasError = true }
				null
			} else {
				EphemeralMediaDataSource(item.target, head.first, head.second)
			}
		}
	}.value

	DisposableEffect(dataSource) {
		if (dataSource == null) return@DisposableEffect onDispose {}
		try {
			mediaPlayer.setDataSource(dataSource)
			mediaPlayer.setOnPreparedListener { mp ->
				durationMs = mp.duration
				isPrepared = true
				runCatching { mp.start() }
				isPlaying = true
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
			runCatching {
				if (mediaPlayer.isPlaying) mediaPlayer.stop()
			}
			runCatching {
				mediaPlayer.reset()
			}
			dataSource.close()
		}
	}

	LaunchedEffect(isPlaying, isDraggingSlider) {
		while (isPlaying && !isDraggingSlider) {
			if (isPrepared) {
				runCatching { mediaPlayer.currentPosition }.getOrNull()?.let { pos ->
					currentPositionMs = pos
				}
			}
			delay(200)
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
				.padding(24.dp),
			contentAlignment = Alignment.Center,
		) {
			Card(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(24.dp)),
				shape = RoundedCornerShape(24.dp),
				colors = CardDefaults.cardColors(
					containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
				),
			) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(20.dp),
					horizontalAlignment = Alignment.CenterHorizontally,
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							text = stringResource(R.string.ephemeral_media_title),
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.primary,
						)
						IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
							Icon(
								Icons.Filled.Close,
								contentDescription = stringResource(R.string.close_vault),
								tint = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}

					Spacer(Modifier.height(16.dp))

					Surface(
						modifier = Modifier
							.size(160.dp)
							.clip(RoundedCornerShape(16.dp)),
						color = MaterialTheme.colorScheme.surfaceContainerLowest,
						tonalElevation = 2.dp,
					) {
						Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
							val art = thumbnail?.bitmap
							if (art != null) {
								Image(
									bitmap = art.asImageBitmap(),
									contentDescription = item.name,
									contentScale = ContentScale.Crop,
									modifier = Modifier.fillMaxSize(),
								)
							} else {
								Icon(
									painter = painterResource(R.drawable.ic_audio_badge),
									contentDescription = null,
									tint = MaterialTheme.colorScheme.primary,
									modifier = Modifier.size(56.dp),
								)
							}
						}
					}

					Spacer(Modifier.height(20.dp))

					Text(
						text = item.name,
						style = MaterialTheme.typography.titleMedium,
						maxLines = 1,
						softWrap = false,
						textAlign = TextAlign.Center,
						modifier = Modifier
							.fillMaxWidth()
							.basicMarquee(iterations = Int.MAX_VALUE),
					)

					Spacer(Modifier.height(16.dp))

					if (hasError) {
						Text(
							text = stringResource(R.string.audio_playback_error),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier.padding(vertical = 12.dp),
						)
					} else if (!isPrepared) {
						CircularProgressIndicator(
							modifier = Modifier.size(32.dp).padding(vertical = 4.dp),
							strokeWidth = 3.dp,
						)
					} else {
						val totalDuration = durationMs.toFloat().coerceAtLeast(1f)
						val displayedPos = if (isDraggingSlider) sliderPositionMs else currentPositionMs.toFloat()

						Slider(
							value = displayedPos.coerceIn(0f, totalDuration),
							onValueChange = {
								isDraggingSlider = true
								sliderPositionMs = it
							},
							onValueChangeFinished = {
								isDraggingSlider = false
								val target = sliderPositionMs.toInt()
								currentPositionMs = target
								runCatching { mediaPlayer.seekTo(target) }
							},
							valueRange = 0f..totalDuration,
							modifier = Modifier.fillMaxWidth(),
							colors = SliderDefaults.colors(
								thumbColor = MaterialTheme.colorScheme.primary,
								activeTrackColor = MaterialTheme.colorScheme.primary,
								inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
							),
						)

						Row(
							modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
							horizontalArrangement = Arrangement.SpaceBetween,
						) {
							Text(
								text = formatTimestamp(displayedPos.toLong()),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							Text(
								text = formatTimestamp(durationMs.toLong()),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}

						Spacer(Modifier.height(12.dp))

						Row(
							modifier = Modifier.fillMaxWidth(),
							horizontalArrangement = Arrangement.Center,
							verticalAlignment = Alignment.CenterVertically,
						) {
							IconButton(
								onClick = {
									val target = (currentPositionMs - 10_000).coerceAtLeast(0)
									currentPositionMs = target
									runCatching { mediaPlayer.seekTo(target) }
								},
								modifier = Modifier.size(48.dp),
							) {
								Icon(
									painter = painterResource(R.drawable.ic_replay_10),
									contentDescription = stringResource(R.string.rewind_10),
									modifier = Modifier.size(28.dp),
								)
							}

							Spacer(Modifier.width(16.dp))

							FilledIconButton(
								onClick = {
									if (isPlaying) {
										runCatching { mediaPlayer.pause() }
										isPlaying = false
									} else {
										runCatching { mediaPlayer.start() }
										isPlaying = true
									}
								},
								modifier = Modifier.size(56.dp),
								shape = CircleShape,
							) {
								Icon(
									painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
									contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
									modifier = Modifier.size(32.dp),
								)
							}

							Spacer(Modifier.width(16.dp))

							IconButton(
								onClick = {
									val target = (currentPositionMs + 10_000).coerceAtMost(durationMs)
									currentPositionMs = target
									runCatching { mediaPlayer.seekTo(target) }
								},
								modifier = Modifier.size(48.dp),
							) {
								Icon(
									painter = painterResource(R.drawable.ic_forward_10),
									contentDescription = stringResource(R.string.forward_10),
									modifier = Modifier.size(28.dp),
								)
							}
						}
					}
				}
			}
		}
	}
}

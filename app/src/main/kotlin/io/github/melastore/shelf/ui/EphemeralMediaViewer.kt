package io.github.melastore.shelf.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.melastore.shelf.R
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.data.ContentCredential
import io.github.melastore.shelf.data.EphemeralMediaItem
import io.github.melastore.shelf.data.EphemeralMediaLoader
import io.github.melastore.shelf.data.EphemeralMediaType
import io.github.melastore.shelf.data.EphemeralThumbnail
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.OpenFolders
import io.github.melastore.shelf.data.ShelfCore
import io.github.melastore.shelf.root.RootShell
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EphemeralMediaViewer(folder: VaultFolder, onDismiss: () -> Unit,) {
	val context = LocalContext.current
	var loading by remember { mutableStateOf(true) }
	var mediaItems by remember { mutableStateOf<List<EphemeralMediaItem>>(emptyList()) }
	var previewItem by remember { mutableStateOf<EphemeralMediaItem?>(null) }
	val keyCache = remember { mutableMapOf<String, SecretKey>() }

	// Thumbnails derive in parallel on the IO pool, so the cache is guarded. The lock also stops a
	// folder's worth of them paying for the same PBKDF2 derivation.
	val keyFor: (ByteArray) -> SecretKey? = remember {
		{ salt: ByteArray ->
			val saltKey = salt.contentToString()
			synchronized(keyCache) {
				val cached = keyCache[saltKey]
				if (cached != null) {
					cached
				} else {
					val cred = ContentCredential.copy()
					if (cred != null) {
						try {
							val derived = HeaderCipher.deriveKey(cred, salt)
							keyCache[saltKey] = derived
							derived
						} finally {
							cred.fill(' ')
						}
					} else {
						null
					}
				}
			}
		}
	}

	DisposableEffect(Unit) {
		onDispose {
			synchronized(keyCache) { keyCache.clear() }
		}
	}

	// A root-hidden folder sits at mode 000 and cannot be read even by this app, so browsing it means
	// opening it for as long as the viewer is up. Opening and closing happen in the one coroutine so
	// they cannot overtake each other, and the close runs uncancellable: a folder left readable
	// because the viewer went away mid-scan is the folder no longer hidden.
	val rootEntry = folder.entry?.takeIf { it.method == HideMethod.ROOT_CHMOD }
	LaunchedEffect(folder) {
		loading = true
		val entry = folder.entry
		val actualPath = entry?.hiddenPath?.takeIf { it.isNotEmpty() }
			?: rootEntry?.path?.let(ShelfCore.paths::toEmulated)
			?: folder.path
		try {
			// A folder that cannot be walked shows as empty. Thrown from here it reaches the main
			// dispatcher, which is a crash rather than a message.
			val scanned = withContext(Dispatchers.IO) {
				if (rootEntry != null) {
					OpenFolders.open(rootEntry.path) {
						RootShell.run("chmod 755 ${RootShell.quote(rootEntry.path)}")
					}
				}
				try {
					EphemeralMediaLoader.scanMediaItems(
						actualPath,
						context,
						ShelfCore.paths,
						keyFor,
						entry?.treeUri,
					)
				} catch (_: Exception) {
					emptyList()
				}
			}
			mediaItems = scanned
			loading = false
			awaitCancellation()
		} finally {
			if (rootEntry != null) {
				withContext(NonCancellable + Dispatchers.IO) {
					OpenFolders.close(rootEntry.path) {
						RootShell.run("chmod 000 ${RootShell.quote(rootEntry.path)}").ok
					}
				}
			}
		}
	}

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			securePolicy = SecureFlagPolicy.SecureOn,
		),
	) {
		Scaffold(
			topBar = {
				TopAppBar(
					title = {
						Column {
							Text(
								folder.displayName,
								style = MaterialTheme.typography.titleMedium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
							Text(
								stringResource(R.string.ephemeral_media_subtitle),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					},
					navigationIcon = {
						IconButton(onClick = onDismiss) {
							Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_vault))
						}
					},
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.surface,
					),
				)
			},
			containerColor = MaterialTheme.colorScheme.background,
		) { padding ->
			Box(
				modifier = Modifier.padding(padding).fillMaxSize(),
				contentAlignment = Alignment.Center,
			) {
				when {
					loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
						CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
						Spacer(Modifier.height(16.dp))
						Text(stringResource(R.string.media_loading), style = MaterialTheme.typography.bodyMedium)
					}

					mediaItems.isEmpty() -> Text(
						stringResource(R.string.no_media_found),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)

					else -> LazyColumn(
						modifier = Modifier.fillMaxSize(),
						contentPadding = PaddingValues(vertical = 8.dp),
					) {
						items(mediaItems, key = { EphemeralMediaLoader.targetId(it.target) }) { item ->
							MediaRow(item = item, keyFor = keyFor, onClick = { previewItem = item })
						}
					}
				}
			}
		}

		previewItem?.let { preview ->
			val dismiss = { previewItem = null }
			when (preview.type) {
				EphemeralMediaType.VIDEO ->
					EphemeralVideoPlayerDialog(item = preview, keyFor = keyFor, onDismiss = dismiss)

				EphemeralMediaType.AUDIO ->
					EphemeralAudioPlayerDialog(item = preview, keyFor = keyFor, onDismiss = dismiss)

				EphemeralMediaType.PDF ->
					EphemeralPdfViewerDialog(item = preview, keyFor = keyFor, onDismiss = dismiss)

				EphemeralMediaType.TEXT ->
					EphemeralTextViewerDialog(item = preview, keyFor = keyFor, onDismiss = dismiss)

				EphemeralMediaType.IMAGE ->
					MediaPreviewDialog(item = preview, keyFor = keyFor, onDismiss = dismiss)
			}
		}
	}
}

@Composable
private fun MediaRow(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, onClick: () -> Unit) {
	val thumbnail = produceState<EphemeralThumbnail?>(initialValue = null, item) {
		value = withContext(Dispatchers.IO) {
			EphemeralMediaLoader.loadThumbnail(item, keyFor, maxDimension = 300)
		}
	}.value
	val isVideo = item.type == EphemeralMediaType.VIDEO
	val bitmap = thumbnail?.bitmap
	val badge = when (item.type) {
		EphemeralMediaType.TEXT -> R.drawable.ic_text_badge
		EphemeralMediaType.PDF -> R.drawable.ic_pdf_badge
		EphemeralMediaType.IMAGE -> R.drawable.ic_image_badge
		EphemeralMediaType.VIDEO -> R.drawable.ic_video_badge
		EphemeralMediaType.AUDIO -> R.drawable.ic_audio_badge
	}
	// Not held back for the thumbnail: a large video or PDF can take seconds to render one, and the
	// file opens the same without it.
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Surface(
			modifier = Modifier
				.size(width = 104.dp, height = 64.dp)
				.clip(RoundedCornerShape(6.dp)),
			color = MaterialTheme.colorScheme.surfaceContainer,
		) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				when {
					bitmap != null -> Image(
						bitmap = bitmap.asImageBitmap(),
						contentDescription = item.name,
						contentScale = ContentScale.Crop,
						modifier = Modifier.fillMaxSize(),
					)

					thumbnail == null -> CircularProgressIndicator(
						modifier = Modifier.size(18.dp),
						strokeWidth = 2.dp,
					)

					// Nothing decoded, which is the normal case for text. The row still opens.
					else -> Icon(
						painter = painterResource(badge),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(24.dp),
					)
				}
				if (isVideo && bitmap != null) {
					Box(
						modifier = Modifier
							.padding(4.dp)
							.align(Alignment.BottomStart)
							.clip(RoundedCornerShape(4.dp))
							.background(Color.Black.copy(alpha = 0.6f))
							.padding(horizontal = 4.dp, vertical = 2.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_video_badge),
							contentDescription = null,
							tint = Color.White,
							modifier = Modifier.size(11.dp),
						)
					}
				}
			}
		}

		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			// One line that scrolls, not several that stop: these names run to a couple of hundred
			// characters, so any fixed number of lines still ends in an ellipsis.
			Text(
				text = item.name,
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				softWrap = false,
				modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
			)
			val duration = thumbnail?.durationMs ?: 0L
			if (duration > 0) {
				Text(
					text = formatTimestamp(duration),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun MediaPreviewDialog(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, onDismiss: () -> Unit,) {
	var settled by remember(item) { mutableStateOf(false) }
	val bitmap = produceState<Bitmap?>(initialValue = null, item) {
		val loaded = withContext(Dispatchers.IO) {
			EphemeralMediaLoader.loadBitmap(item.target, keyFor, maxDimension = 2048)
		}
		value = loaded
		settled = true
	}.value
	// The value, not the state: read at dispose time, the state is already the new bitmap, and the
	// load recycled the very one it was about to draw.
	DisposableEffect(bitmap) {
		onDispose { bitmap?.recycle() }
	}
	var scale by remember { mutableFloatStateOf(1f) }
	var offset by remember { mutableStateOf(Offset.Zero) }

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
				.background(MaterialTheme.colorScheme.background)
				.pointerInput(Unit) {
					detectTransformGestures { _, pan, zoom, _ ->
						val newScale = (scale * zoom).coerceIn(1f, 5f)
						scale = newScale
						if (newScale > 1f) {
							val maxX = (size.width * (newScale - 1f)) / 2f
							val maxY = (size.height * (newScale - 1f)) / 2f
							offset = Offset(
								(offset.x + pan.x).coerceIn(-maxX, maxX),
								(offset.y + pan.y).coerceIn(-maxY, maxY),
							)
						} else {
							offset = Offset.Zero
						}
					}
				}
				.pointerInput(Unit) {
					detectTapGestures(
						onDoubleTap = {
							if (scale > 1f) {
								scale = 1f
								offset = Offset.Zero
							} else {
								scale = 2.5f
							}
						},
					)
				},
		) {
			val bmp = bitmap
			when {
				bmp != null -> Image(
					bitmap = bmp.asImageBitmap(),
					contentDescription = item.name,
					contentScale = ContentScale.Fit,
					modifier = Modifier
						.fillMaxSize()
						.padding(top = 56.dp, bottom = 48.dp)
						.graphicsLayer(
							scaleX = scale,
							scaleY = scale,
							translationX = offset.x,
							translationY = offset.y,
						),
				)

				!settled -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}

				else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					Text(
						stringResource(R.string.image_open_failed),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}

			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(top = 16.dp, start = 8.dp, end = 16.dp)
					.align(Alignment.TopStart),
				verticalAlignment = Alignment.CenterVertically,
			) {
				IconButton(onClick = onDismiss) {
					Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_vault))
				}
				Text(
					item.name,
					style = MaterialTheme.typography.titleMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(start = 8.dp),
				)
			}
		}
	}
}

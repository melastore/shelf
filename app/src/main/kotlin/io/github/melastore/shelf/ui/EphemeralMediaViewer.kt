package io.github.melastore.shelf.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.melastore.shelf.R
import io.github.melastore.shelf.crypto.HeaderCipher
import io.github.melastore.shelf.data.ContentCredential
import io.github.melastore.shelf.data.EphemeralMediaItem
import io.github.melastore.shelf.data.EphemeralMediaLoader
import io.github.melastore.shelf.data.EphemeralMediaType
import io.github.melastore.shelf.data.ShelfCore
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EphemeralMediaViewer(folder: VaultFolder, onDismiss: () -> Unit,) {
	val context = LocalContext.current
	var loading by remember { mutableStateOf(true) }
	var mediaItems by remember { mutableStateOf<List<EphemeralMediaItem>>(emptyList()) }
	var previewItem by remember { mutableStateOf<EphemeralMediaItem?>(null) }
	val keyCache = remember { mutableMapOf<String, SecretKey>() }

	val keyFor: (ByteArray) -> SecretKey? = remember {
		{ salt: ByteArray ->
			val saltKey = salt.contentToString()
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

	LaunchedEffect(folder) {
		loading = true
		val actualPath = folder.entry?.hiddenPath?.takeIf { it.isNotEmpty() } ?: folder.path
		val scanned = withContext(Dispatchers.IO) {
			EphemeralMediaLoader.scanMediaItems(actualPath, context, ShelfCore.paths, keyFor)
		}
		mediaItems = scanned
		loading = false
	}

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
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

					else -> LazyVerticalGrid(
						columns = GridCells.Adaptive(100.dp),
						modifier = Modifier.fillMaxSize(),
						contentPadding = PaddingValues(12.dp),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						items(mediaItems, key = { it.target.name + it.target.size() }) { item ->
							MediaThumbnail(item = item, keyFor = keyFor, onClick = { previewItem = item })
						}
					}
				}
			}
		}

		previewItem?.let { preview ->
			if (preview.type == EphemeralMediaType.VIDEO) {
				EphemeralVideoPlayerDialog(
					item = preview,
					keyFor = keyFor,
					onDismiss = { previewItem = null },
				)
			} else {
				MediaPreviewDialog(
					item = preview,
					keyFor = keyFor,
					onDismiss = { previewItem = null },
				)
			}
		}
	}
}

@Composable
private fun MediaThumbnail(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, onClick: () -> Unit) {
	val bitmapState = produceState<Bitmap?>(initialValue = null, item) {
		value = withContext(Dispatchers.IO) {
			EphemeralMediaLoader.loadThumbnail(item, keyFor, maxDimension = 300)
		}
	}
	val isVideo = item.type == EphemeralMediaType.VIDEO
	Surface(
		modifier = Modifier
			.aspectRatio(1f)
			.clip(RoundedCornerShape(8.dp))
			.clickable(enabled = bitmapState.value != null || isVideo, onClick = onClick),
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Box(Modifier.fillMaxSize()) {
			val bmp = bitmapState.value
			if (bmp != null) {
				Image(
					bitmap = bmp.asImageBitmap(),
					contentDescription = item.name,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
			} else if (isVideo) {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					Icon(
						painter = painterResource(R.drawable.ic_video_badge),
						contentDescription = item.name,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.size(28.dp),
					)
				}
			} else {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
				}
			}
			if (isVideo && bmp != null) {
				Box(
					modifier = Modifier
						.padding(6.dp)
						.align(Alignment.BottomStart)
						.clip(RoundedCornerShape(4.dp))
						.background(Color.Black.copy(alpha = 0.6f))
						.padding(horizontal = 5.dp, vertical = 2.dp),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_video_badge),
						contentDescription = null,
						tint = Color.White,
						modifier = Modifier.size(12.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun MediaPreviewDialog(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, onDismiss: () -> Unit,) {
	val bitmapState = produceState<Bitmap?>(initialValue = null, item) {
		value = withContext(Dispatchers.IO) {
			EphemeralMediaLoader.loadBitmap(item.target, keyFor, maxDimension = 2048)
		}
	}

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(MaterialTheme.colorScheme.background),
		) {
			val bmp = bitmapState.value
			if (bmp != null) {
				Image(
					bitmap = bmp.asImageBitmap(),
					contentDescription = item.name,
					contentScale = ContentScale.Fit,
					modifier = Modifier.fillMaxSize().padding(top = 56.dp, bottom = 48.dp),
				)
			} else {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
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

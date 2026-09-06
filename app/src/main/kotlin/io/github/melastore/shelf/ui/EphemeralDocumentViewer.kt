package io.github.melastore.shelf.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.EphemeralMediaItem
import io.github.melastore.shelf.data.EphemeralMediaLoader
import io.github.melastore.shelf.data.EphemeralPdf
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Chrome shared by both readers: a close button, the file name, and whatever the reader shows. */
@Composable
private fun DocumentScaffold(name: String, onDismiss: () -> Unit, body: @Composable () -> Unit) {
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			securePolicy = SecureFlagPolicy.SecureOn,
		),
	) {
		Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
			Column(Modifier.fillMaxSize()) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 8.dp, start = 4.dp, end = 12.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					IconButton(onClick = onDismiss) {
						Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_vault))
					}
					Text(
						text = name,
						style = MaterialTheme.typography.titleSmall,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(start = 4.dp),
					)
				}
				Box(Modifier.fillMaxSize()) { body() }
			}
		}
	}
}

@Composable
fun EphemeralTextViewerDialog(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, onDismiss: () -> Unit,) {
	var settled by remember(item) { mutableStateOf(false) }
	val text = produceState<String?>(initialValue = null, item) {
		val loaded = withContext(Dispatchers.IO) {
			EphemeralMediaLoader.loadText(item.target, keyFor)
		}
		value = loaded
		settled = true
	}.value

	DocumentScaffold(item.name, onDismiss) {
		when {
			!settled -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}

			text == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					stringResource(R.string.document_open_failed),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			else -> Column(
				modifier = Modifier
					.fillMaxSize()
					.verticalScroll(rememberScrollState())
					.padding(16.dp),
			) {
				Text(
					text = text,
					style = MaterialTheme.typography.bodySmall,
					fontFamily = FontFamily.Monospace,
				)
				if (text.length >= EphemeralMediaLoader.MAX_TEXT_BYTES / 2) {
					Text(
						text = stringResource(R.string.document_truncated),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(top = 20.dp),
					)
				}
			}
		}
	}
}

@Composable
fun EphemeralPdfViewerDialog(item: EphemeralMediaItem, keyFor: (ByteArray) -> SecretKey?, onDismiss: () -> Unit,) {
	var settled by remember(item) { mutableStateOf(false) }
	val document = produceState<EphemeralPdf?>(initialValue = null, item) {
		val loaded = withContext(Dispatchers.IO) { EphemeralMediaLoader.loadPdf(item.target, keyFor) }
		value = loaded
		settled = true
	}.value

	// Closing the renderer is what frees the decrypted document from memory.
	DisposableEffect(document) {
		onDispose { document?.close() }
	}

	DocumentScaffold(item.name, onDismiss) {
		when {
			!settled -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				CircularProgressIndicator()
			}

			document == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(
					stringResource(R.string.document_open_failed),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			else -> {
				// Capped so a wide screen does not ask for a bitmap far larger than it displays.
				val screenWidth = LocalConfiguration.current.screenWidthDp.dp
				val widthPx = with(LocalDensity.current) {
					(screenWidth - 24.dp).roundToPx().coerceIn(320, 2048)
				}
				LazyColumn(
					modifier = Modifier.fillMaxSize(),
					contentPadding = PaddingValues(12.dp),
				) {
					items((0 until document.pageCount).toList(), key = { it }) { index ->
						PdfPage(document = document, index = index, widthPx = widthPx)
					}
				}
			}
		}
	}
}

@Composable
private fun PdfPage(document: EphemeralPdf, index: Int, widthPx: Int) {
	// On demand and dropped with the row: a long document held as bitmaps is far more memory than
	// the file it came from.
	val page = produceState<Bitmap?>(initialValue = null, document, index, widthPx) {
		value = withContext(Dispatchers.IO) { document.renderPage(index, widthPx) }
	}.value

	Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
		Surface(
			modifier = Modifier.fillMaxWidth(),
			color = MaterialTheme.colorScheme.surfaceContainer,
		) {
			if (page != null) {
				Image(
					bitmap = page.asImageBitmap(),
					contentDescription = null,
					contentScale = ContentScale.FillWidth,
					modifier = Modifier.fillMaxWidth(),
				)
			} else {
				Box(
					modifier = Modifier.fillMaxWidth().height(320.dp),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
				}
			}
		}
		Text(
			text = stringResource(R.string.document_page_of, index + 1, document.pageCount),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(top = 4.dp),
		)
	}
}

package io.github.melastore.shelf.data

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * The rename method is the only one that reaches a folder purely through a picker grant, and it was
 * the one silently doing nothing: both protectors saw an empty folder and reported success over
 * files they had never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafGrantsRobolectricTest {

	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private val context: Application
		get() = ApplicationProvider.getApplicationContext()

	private lateinit var paths: StoragePaths
	private lateinit var folder: File

	@Before
	fun setUp() {
		val base = temporaryFolder.newFolder()
		paths = StoragePaths.forTest(
			backingRoot = File(base, "backing").apply { mkdirs() }.path,
			emulatedRoot = File(base, "emulated").apply { mkdirs() }.path,
		)
		folder = File(paths.emulatedRoot, "Pictures/Secret").apply { mkdirs() }
		folder.resolve("one.jpg").writeText("first")
		folder.resolve("two.jpg").writeText("second")
		folder.resolve("nested").mkdirs()
		folder.resolve("nested/three.jpg").writeText("third")

		registerProvider()
		grant("primary:Pictures")
	}

	/**
	 * The provider has to be visible to the package manager as well as to the resolver:
	 * [DocumentFile.fromTreeUri] asks whether the authority is a documents provider, and answers a
	 * "no" by pointing at the tree root instead of the document that was asked for.
	 */
	private fun registerProvider() {
		val name = FakeDocuments::class.java.name
		val packageManager = shadowOf(context.packageManager)
		packageManager.addOrUpdateProvider(
			ProviderInfo().apply {
				authority = AUTHORITY
				packageName = context.packageName
				this.name = name
				exported = true
				applicationInfo = context.applicationInfo
			},
		)
		packageManager.addIntentFilterForProvider(
			ComponentName(context.packageName, name),
			IntentFilter(DOCUMENTS_PROVIDER),
		)
		ShadowContentResolver.registerProviderInternal(AUTHORITY, FakeDocuments(paths.emulatedRoot))
	}

	@Test
	fun `a granted folder can be listed`() {
		val document = SafGrants.folder(context, paths, folder.path)

		assertNotNull(document)
		assertEquals(
			listOf("nested", "one.jpg", "two.jpg"),
			requireNotNull(document).listFiles().mapNotNull { it.name }.sorted(),
		)
	}

	@Test
	fun `every file under a granted folder becomes a lock target`() {
		val document = requireNotNull(SafGrants.folder(context, paths, folder.path))

		val targets = SafLockTarget.targetsUnder(context.contentResolver, document)

		assertEquals(3, targets?.size)
	}

	/**
	 * With no credential the name protector stops before it changes anything. Reaching that answer at
	 * all is the point: the folder had to be resolved and its manifest looked for first, and looking
	 * for the manifest is what used to throw.
	 */
	@Test
	fun `name protection reaches the folder without a credential`() = runBlocking {
		val protector = FolderNameProtector(context, paths, credential = { null })

		assertEquals(NameProtectionResult.CredentialRequired, protector.protect(folder.path))
	}

	@Test
	fun `a folder outside every grant is refused`() {
		val outside = File(paths.emulatedRoot, "Documents/Other").apply { mkdirs() }

		assertEquals(null, SafGrants.folder(context, paths, outside.path))
	}

	private fun grant(documentId: String) {
		val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, documentId)
		context.contentResolver.takePersistableUriPermission(
			tree,
			Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
		)
	}

	/** Just enough of the storage provider to describe a real directory tree under [root]. */
	private class FakeDocuments(private val root: String) : ContentProvider() {

		override fun onCreate() = true

		override fun query(
			uri: Uri,
			projection: Array<out String>?,
			selection: String?,
			selectionArgs: Array<out String>?,
			sortOrder: String?,
		): Cursor {
			// Callers read by index, so the columns have to come back in the order they were asked for.
			val columns = projection?.toList()?.toTypedArray() ?: COLUMNS
			val cursor = MatrixCursor(columns)
			val documentId = DocumentsContract.getDocumentId(uri)
			if (uri.lastPathSegment == "children") {
				file(documentId).listFiles().orEmpty().sortedBy { it.name }.forEach {
					cursor.addRow(row(columns, "$documentId/${it.name}", it))
				}
			} else {
				val file = file(documentId)
				if (file.exists()) cursor.addRow(row(columns, documentId, file))
			}
			return cursor
		}

		private fun row(columns: Array<out String>, documentId: String, file: File): Array<Any?> =
			Array(columns.size) { index ->
				when (columns[index]) {
					DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId

					DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name

					DocumentsContract.Document.COLUMN_MIME_TYPE ->
						if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "image/jpeg"

					DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_RENAME

					DocumentsContract.Document.COLUMN_SIZE -> file.length()

					else -> null
				}
			}

		private fun file(documentId: String): File {
			val relative = documentId.removePrefix("primary:")
			return if (relative.isEmpty()) File(root) else File(root, relative)
		}

		override fun getType(uri: Uri): String? = null

		override fun insert(uri: Uri, values: ContentValues?): Uri? = null

		override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,): Int = 0

		override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
	}

	private companion object {
		const val AUTHORITY = "com.android.externalstorage.documents"
		const val DOCUMENTS_PROVIDER = "android.content.action.DOCUMENTS_PROVIDER"
		val COLUMNS = arrayOf(
			DocumentsContract.Document.COLUMN_DOCUMENT_ID,
			DocumentsContract.Document.COLUMN_DISPLAY_NAME,
			DocumentsContract.Document.COLUMN_MIME_TYPE,
			DocumentsContract.Document.COLUMN_FLAGS,
			DocumentsContract.Document.COLUMN_SIZE,
		)
	}
}

package io.github.melastore.shelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A file whose header Shelf has encrypted, and everything needed to decrypt it again.
 *
 * [salt], [nonce] and [tag] are Base64. New locks also carry the same recovery parameters in an
 * authenticated trailer on the file; this record keeps the normal restore flow quick.
 *
 * [path] keeps the serialised name `backingPath` so vaults written by earlier versions still parse.
 * It is the /data/media/<user> path when the header was written through root, and the emulated path
 * otherwise; [documentUri] carries the persisted grant that lets an unprivileged build reopen the
 * file, and is empty on entries made through root.
 */
@Serializable
data class LockedFile(
	@SerialName("backingPath") val path: String,
	val displayName: String,
	val sliceLen: Int,
	val salt: String,
	val nonce: String,
	val tag: String,
	val lockedAt: Long,
	val documentUri: String = "",
	val originalSize: Long = -1,
	val trailerLength: Int = 0,
)

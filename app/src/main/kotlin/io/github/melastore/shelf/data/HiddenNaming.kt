package io.github.melastore.shelf.data

import kotlin.random.Random

/** What a folder is called once it is hidden. Every style keeps the dot; only the rest differs. */
enum class HiddenNameStyle {
	/** `.Holiday`, the old name with a dot in front. */
	KEEP,

	/** `.meta`, a name storage is already full of. */
	CAMOUFLAGE,

	/** `.7f3ac9e2`, which says nothing. */
	RANDOM,
}

/**
 * Picks the name a folder is hidden under. The way back is the journal and the manifest inside the
 * folder, so the name itself can carry nothing. It does have to be free: a rename onto a name
 * already in the parent fails.
 */
object HiddenNamer {

	// Names that pass for something an app left behind. None of them is a cache: cleaners delete
	// .thumbnails and .cache on sight, and these hold photos.
	private val COVER_NAMES = listOf(
		".data",
		".meta",
		".index",
		".store",
		".local",
		".sync",
		".bundle",
		".state",
	)

	/**
	 * A free name for [original], asking [taken] about each candidate. KEEP comes back whether it is
	 * free or not, since the dotted original is the one name the owner can work out. The others fall
	 * back to an opaque name, never to the readable one.
	 */
	fun hiddenName(
		style: HiddenNameStyle,
		original: String,
		random: Random = Random.Default,
		taken: (String) -> Boolean,
	): String {
		val plain = SafPaths.hiddenName(original)
		if (style == HiddenNameStyle.KEEP) return plain
		val candidates = when (style) {
			HiddenNameStyle.CAMOUFLAGE -> camouflage(random)
			else -> opaque(random)
		}
		return candidates.take(MAX_ATTEMPTS).firstOrNull { it != plain && !taken(it) } ?: opaqueName(random)
	}

	/**
	 * Whether [hiddenName] is already what [style] would produce. A folder that answers yes is left
	 * alone when the setting changes, rather than renamed for nothing.
	 */
	fun matches(style: HiddenNameStyle, original: String, hiddenName: String): Boolean = when (style) {
		HiddenNameStyle.KEEP -> hiddenName == SafPaths.hiddenName(original)

		HiddenNameStyle.RANDOM -> OPAQUE_NAME.matches(hiddenName)

		HiddenNameStyle.CAMOUFLAGE -> COVER_NAMES.any { cover ->
			hiddenName == cover ||
				(hiddenName.startsWith("$cover-") && SUFFIXED_COVER.matches(hiddenName.removePrefix("$cover-")))
		}
	}

	private fun camouflage(random: Random): Sequence<String> = COVER_NAMES.shuffled(random).asSequence() +
		generateSequence { "${COVER_NAMES.random(random)}-${token(random, SUFFIX_LENGTH)}" }

	private fun opaque(random: Random): Sequence<String> = generateSequence { opaqueName(random) }

	private fun opaqueName(random: Random): String = ".${token(random, NAME_LENGTH)}"

	private fun token(random: Random, length: Int): String =
		(1..length).map { HEX[random.nextInt(HEX.length)] }.joinToString("")

	private const val HEX = "0123456789abcdef"
	private const val NAME_LENGTH = 8
	private const val SUFFIX_LENGTH = 6

	/** Twelve probes at worst: the pool, then four suffixed names. */
	private const val MAX_ATTEMPTS = 12

	private val OPAQUE_NAME = Regex("\\.[0-9a-f]{$NAME_LENGTH}")
	private val SUFFIXED_COVER = Regex("[0-9a-f]{$SUFFIX_LENGTH}")
}

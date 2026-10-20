package io.github.melastore.shelf.crypto

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class KeyCacheTest {
	private var open = true
	private lateinit var previous: () -> Boolean
	private val derivations = AtomicInteger()
	private val salt = ByteArray(16) { it.toByte() }

	@Before
	fun setUp() {
		previous = KeyCache.keepWhile
		KeyCache.keepWhile = { open }
		KeyCache.clear()
	}

	@After
	fun tearDown() {
		KeyCache.clear()
		KeyCache.keepWhile = previous
	}

	private fun key(password: String, salt: ByteArray = this.salt, iterations: Int = 1_000): ByteArray =
		KeyCache.get(password.toCharArray(), salt, iterations) {
			derivations.incrementAndGet()
			"$password/${salt.contentToString()}/$iterations".toByteArray().copyOf(32)
		}

	@Test
	fun `a key is derived once per session`() {
		val first = key("1357")
		first.fill(0)
		val second = key("1357")

		assertEquals(1, derivations.get())
		assertArrayEquals("1357/${salt.contentToString()}/1000".toByteArray().copyOf(32), second)
	}

	@Test
	fun `another password, salt or work factor gets its own key`() {
		key("1357")
		key("1358")
		key("1357", salt = ByteArray(16))
		key("1357", iterations = 2_000)

		assertEquals(4, derivations.get())
	}

	@Test
	fun `a lock drops every key`() {
		key("1357")
		KeyCache.clear()
		key("1357")

		assertEquals(2, derivations.get())
	}

	@Test
	fun `nothing is kept while no session is open`() {
		open = false
		key("1357")
		key("1357")

		assertEquals(2, derivations.get())
	}

	@Test
	fun `a lock during a derivation still answers with the real key and keeps nothing`() {
		val expected = "1357".toByteArray().copyOf(32)
		val answered = KeyCache.get("1357".toCharArray(), salt, 1_000) {
			derivations.incrementAndGet()
			open = false
			KeyCache.clear()
			expected.copyOf()
		}
		open = true
		key("1357")

		assertArrayEquals(expected, answered)
		assertEquals(2, derivations.get())
	}

	@Test
	fun `callers asking at once share one derivation`() {
		val started = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(4)
		val results = (1..4).map {
			pool.submit<ByteArray> {
				started.await()
				KeyCache.get("1357".toCharArray(), salt, 1_000) {
					derivations.incrementAndGet()
					Thread.sleep(100)
					ByteArray(32) { 7 }
				}
			}
		}
		started.countDown()
		results.forEach { assertArrayEquals(ByteArray(32) { 7 }, it.get(5, TimeUnit.SECONDS)) }
		pool.shutdown()

		assertEquals(1, derivations.get())
	}
}

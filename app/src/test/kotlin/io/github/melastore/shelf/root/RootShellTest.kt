package io.github.melastore.shelf.root

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellTest {

	@Test
	fun `missing shell is reported rather than thrown`() = runBlocking {
		val result = RootShell.execute("/nonexistent/shelf-test-shell", arrayOf("id -u"))

		assertFalse(result.ok)
		assertTrue(result.stdout.isEmpty())
		assertTrue(result.stderr.isNotEmpty())
	}

	@Test
	fun `output and exit code survive a shell that reads its commands`() = runBlocking {
		val result = RootShell.execute("/bin/sh", arrayOf("echo first", "echo second"))

		assertTrue(result.ok)
		assertEquals(listOf("first", "second"), result.stdout)
	}

	@Test
	fun `first failing command aborts the rest`() = runBlocking {
		val result = RootShell.execute("/bin/sh", arrayOf("exit 3", "echo unreachable"))

		assertEquals(3, result.exitCode)
		assertTrue(result.stdout.isEmpty())
	}

	/**
	 * A denied root request closes stdin while the commands are still being written. The refusal has to
	 * come back as a result: failing the coroutine instead would surface an exception from every hide,
	 * restore and capability check in the app.
	 */
	@Test
	fun `a shell that closes stdin early reports the write failure in the result`() = runBlocking {
		val flood = Array(64) { "true ${"x".repeat(64 * 1024)}" }

		val result = RootShell.execute("/bin/sh", arrayOf("exit 0", *flood))

		assertEquals(0, result.exitCode)
		assertTrue(result.stderr.isNotEmpty())
	}
}

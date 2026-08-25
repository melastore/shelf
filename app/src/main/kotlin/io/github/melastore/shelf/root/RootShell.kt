package io.github.melastore.shelf.root

import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** Result of one privileged command: its exit code and the two streams it produced. */
data class ShellResult(val exitCode: Int, val stdout: List<String>, val stderr: List<String>) {
	val ok: Boolean get() = exitCode == 0
}

/** Injectable boundary around privileged process execution. */
interface RootCommandRunner {
	suspend fun isAvailable(): Boolean
	suspend fun run(vararg commands: String): ShellResult
}

/**
 * Thin wrapper around a `su` invocation. Each call spawns its own root shell and feeds it the
 * commands on stdin; there is no long-lived daemon to leak or to leave holding elevated state.
 */
object RootShell : RootCommandRunner {

	override suspend fun isAvailable(): Boolean = run("id -u").let { it.ok && it.stdout.firstOrNull()?.trim() == "0" }

	/**
	 * Runs [commands] in a single root shell. The commands run under `set -e`, so the first failure
	 * aborts the rest and surfaces as a non-zero exit code rather than a partially applied change.
	 */
	override suspend fun run(vararg commands: String): ShellResult = execute(SU, commands)

	/** [run] against an arbitrary shell, so the failure paths can be exercised without a rooted host. */
	internal suspend fun execute(shell: String, commands: Array<out String>): ShellResult = withContext(Dispatchers.IO) {
		val process = try {
			ProcessBuilder(shell).redirectErrorStream(false).start()
		} catch (e: IOException) {
			return@withContext ShellResult(EXIT_NO_SU, emptyList(), listOf(e.message.orEmpty()))
		}

		// A denied root request closes the shell's stdin, and the write that follows fails. That is one
		// more thing to report through the result; letting it fail the coroutine would cancel the reads
		// alongside it and turn an ordinary refusal into an exception every caller has to guess at.
		val writer = async {
			runCatching {
				process.outputStream.bufferedWriter().use { stdin ->
					stdin.write("set -e\n")
					commands.forEach {
						stdin.write(it)
						stdin.write("\n")
					}
				}
			}.exceptionOrNull()?.message
		}

		// Drain both pipes while the command runs. A large command result can fill stdout, and a denied
		// root request can otherwise leave the process waiting forever.
		val err = async { process.errorStream.bufferedReader().useLinesTrimmed() }
		val out = async { process.inputStream.bufferedReader().useLinesTrimmed() }
		val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		if (!finished) process.destroyForcibly()
		process.waitFor()
		val errors = err.await() + listOfNotNull(writer.await())
		ShellResult(if (finished) process.exitValue() else EXIT_TIMEOUT, out.await(), errors)
	}

	/** Resolves [path] against the real filesystem, following symlinks. Null if it does not exist. */
	suspend fun realPath(path: String): String? =
		run("realpath ${quote(path)}").takeIf { it.ok }?.stdout?.firstOrNull()?.trim()?.ifEmpty { null }

	/** Wraps [value] so the shell treats it as one literal argument, whatever it contains. */
	fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

	private fun BufferedReader.useLinesTrimmed(): List<String> = use { it.readLines() }.filter { it.isNotEmpty() }

	private const val SU = "su"
	private const val EXIT_NO_SU = 127
	private const val EXIT_TIMEOUT = 124
	private const val COMMAND_TIMEOUT_SECONDS = 30L
}

package io.github.melastore.shelf.root

import java.io.BufferedReader
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** One privileged command's exit code and the two streams it produced. */
data class ShellResult(val exitCode: Int, val stdout: List<String>, val stderr: List<String>) {
	val ok: Boolean get() = exitCode == 0
}

/** The boundary around privileged process execution, so tests can stand in for it. */
interface RootCommandRunner {
	suspend fun isAvailable(): Boolean
	suspend fun run(vararg commands: String): ShellResult
}

/**
 * Thin wrapper around `su`. Each call spawns its own shell and feeds it the commands on stdin, so
 * there is no long-lived daemon to leak or leave holding elevated state.
 */
object RootShell : RootCommandRunner {

	override suspend fun isAvailable(): Boolean = run("id -u").let { it.ok && it.stdout.firstOrNull()?.trim() == "0" }

	/**
	 * Runs [commands] in one root shell under `set -e`, so the first failure aborts the rest and
	 * shows up as a non-zero exit code rather than a half-applied change.
	 */
	override suspend fun run(vararg commands: String): ShellResult = execute(SU, commands)

	/** [run] against any shell, so the failure paths can be tested without a rooted host. */
	internal suspend fun execute(shell: String, commands: Array<out String>): ShellResult = withContext(Dispatchers.IO) {
		val process = try {
			ProcessBuilder(shell).redirectErrorStream(false).start()
		} catch (e: IOException) {
			return@withContext ShellResult(EXIT_NO_SU, emptyList(), listOf(e.message.orEmpty()))
		}

		// A denied root request closes stdin and the write that follows fails. Report it through the
		// result: letting it fail the coroutine would cancel the reads too and turn an ordinary
		// refusal into an exception every caller has to guess at.
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

		// Drain both pipes while the command runs. A large result fills stdout, and a denied root
		// request would otherwise leave the process waiting forever.
		val err = async { process.errorStream.bufferedReader().useLinesTrimmed() }
		val out = async { process.inputStream.bufferedReader().useLinesTrimmed() }
		val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
		if (!finished) process.destroyForcibly()
		process.waitFor()
		val errors = err.await() + listOfNotNull(writer.await())
		ShellResult(if (finished) process.exitValue() else EXIT_TIMEOUT, out.await(), errors)
	}

	/** Resolves [path] on the real filesystem, following symlinks. Null when it does not exist. */
	suspend fun realPath(path: String): String? =
		run("realpath ${quote(path)}").takeIf { it.ok }?.stdout?.firstOrNull()?.trim()?.ifEmpty { null }

	/** Wraps [value] so the shell reads it as one literal argument, whatever it contains. */
	fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

	private fun BufferedReader.useLinesTrimmed(): List<String> = use { it.readLines() }.filter { it.isNotEmpty() }

	private const val SU = "su"
	private const val EXIT_NO_SU = 127
	private const val EXIT_TIMEOUT = 124
	private const val COMMAND_TIMEOUT_SECONDS = 30L
}

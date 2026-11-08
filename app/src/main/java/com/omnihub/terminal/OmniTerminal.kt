package com.omnihub.terminal

import android.content.Context
import com.omnihub.policy.PermissionEngine
import com.omnihub.policy.RiskLevel
import com.omnihub.workspace.OmniWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class OmniTerminal(
    private val context: Context,
    private val workspace: OmniWorkspace
) {
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val shell: String
    )

    enum class Shell { SH, BASH, TOYBOX }

    suspend fun run(
        command: String,
        shell: Shell = Shell.SH,
        timeoutSec: Long = 60,
        workDir: File? = null,
        origin: TerminalLog.Origin = TerminalLog.Origin.USER
    ): Result = withContext(Dispatchers.IO) {
        val risk = classify(command)
        if (!PermissionEngine.allow(risk)) {
            val blocked = Result(-1, "", "Blocked by permission engine (risk=$risk)", shell.name)
            TerminalLog.record(origin, command, blocked.stderr, blocked.exitCode)
            return@withContext blocked
        }
        val cwd = workDir ?: workspace.root()
        val binary = when (shell) {
            Shell.BASH -> if (File("/system/bin/bash").exists()) "/system/bin/bash" else "/system/bin/sh"
            Shell.TOYBOX -> "/system/bin/toybox"
            Shell.SH -> "/system/bin/sh"
        }
        val pb = ProcessBuilder(binary, "-c", command)
            .directory(cwd)
            .redirectErrorStream(false)
        val env = pb.environment()
        env["HOME"] = cwd.absolutePath
        env["OMNI_HOME"] = cwd.absolutePath
        val proc = pb.start()
        // Drain streams on side threads into buffers — a command that fills the
        // pipe buffer while stderr is also open deadlocks waitFor() otherwise.
        val outBuf = java.lang.StringBuilder()
        val errBuf = java.lang.StringBuilder()
        val outThread = Thread { runCatching { proc.inputStream.bufferedReader().forEachLine { outBuf.append(it).append('\n') } } }
        val errThread = Thread { runCatching { proc.errorStream.bufferedReader().forEachLine { errBuf.append(it).append('\n') } } }
        outThread.start(); errThread.start()
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            val timeout = Result(-1, "", "Timed out after ${timeoutSec}s", shell.name)
            TerminalLog.record(origin, command, timeout.stderr, timeout.exitCode)
            return@withContext timeout
        }
        outThread.join(3000); errThread.join(3000)
        val out = outBuf.toString()
        val err = errBuf.toString()
        val r = Result(proc.exitValue(), out, err, shell.name)
        TerminalLog.record(origin, command, (out + if (err.isBlank()) "" else "\n[stderr]\n$err").trim(), r.exitCode)
        r
    }

    suspend fun git(args: String): Result =
        run("git $args", workDir = workspace.root())

    private fun classify(command: String): RiskLevel {
        val c = command.lowercase()
        return when {
            c.contains("rm -rf /") || c.contains("mkfs") || c.contains(":(){") -> RiskLevel.CRITICAL
            c.contains("curl ") || c.contains("wget ") || c.contains("git push") || c.contains("git clone") -> RiskLevel.EXTERNAL
            c.contains("rm ") || c.contains("mv ") || c.contains("chmod ") -> RiskLevel.DESTRUCTIVE
            c.contains("git ") || c.contains("mkdir ") || c.contains("touch ") -> RiskLevel.LOCAL_WRITE
            else -> RiskLevel.READ
        }
    }
}

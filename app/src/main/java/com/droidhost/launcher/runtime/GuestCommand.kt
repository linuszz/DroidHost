package com.droidhost.launcher.runtime

import android.content.Context
import android.os.Environment
import com.droidhost.launcher.core.DownloadSource
import com.droidhost.launcher.core.HostProcess
import com.droidhost.launcher.session.OrphanReaper
import com.droidhost.launcher.session.SessionFiles
import com.droidhost.launcher.session.SessionPaths
import com.droidhost.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object GuestCommand {
    fun run(context: Context, argv: List<String>, fakeRoot: Boolean = false, logName: String? = null,
            linkDir: File? = null, extraBinds: List<String>? = null, fastPath: Boolean = false, cancelled: AtomicBoolean? = null,
            onLine: (String) -> Unit): Int =
        run(context, argv, fakeRoot, logName, linkDir, extraBinds, fastPath, cancelled, null, false, onLine)

    /**
     * The same command, run in the rootfs a machine brings of its own.
     *
     * [root] is the rootfs to run in; the app's shared runtime when it is null. [guestRoot] makes
     * the guest believe it is root, which a package manager needs and the app's own identity
     * cannot give it.
     */
    fun run(context: Context, argv: List<String>, fakeRoot: Boolean, logName: String?, linkDir: File?,
            extraBinds: List<String>?, fastPath: Boolean, cancelled: AtomicBoolean?, root: File?,
            guestRoot: Boolean, onLine: (String) -> Unit): Int {
        val log = logName?.let {
            try {
                File(File(LinuxRuntime.logDir(context), SessionPaths.TOOLS_DIR).apply { mkdirs() }, "$it.log").printWriter()
            } catch (e: Exception) { null }
        }
        log?.println("== ${java.util.Date()} ${argv.joinToString(" ")}")
        try {
            return runLogged(context, argv, fakeRoot, linkDir, extraBinds, fastPath, cancelled, root, guestRoot) { line -> log?.println(line); log?.flush(); onLine(line) }
        } finally {
            log?.close()
        }
    }

    private fun runLogged(context: Context, argv: List<String>, fakeRoot: Boolean, linkDir: File?, extraBinds: List<String>?,
                          fastPath: Boolean, cancelled: AtomicBoolean?, root: File?, guestRoot: Boolean, onLine: (String) -> Unit): Int {
        val rootfs = root ?: LinuxRuntime.rootDir(context)
        LinuxRuntime.writeAccounts(context, rootfs)
        SessionFiles.stage(context, rootfs)
        LinuxNetworkLinkComponent(context, rootfs).publish()
        val runtimeDir = File(context.filesDir, ".flatpak-rt").apply { mkdirs() }
        val binds = LinuxRuntime.binds(context, rootfs, null, runtimeDir, Environment.getExternalStorageDirectory(), extraBinds)
        val native = NativeGuest.forSession(context)
        val fastPathKey = if (!native && fastPath && !fakeRoot && linkDir == null && ProotFastPath.enabled(context)) ProotFastPath.key(rootfs, binds) else null
        val guest = mutableListOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games", "XDG_RUNTIME_DIR=${LinuxRuntime.GUEST_RUNTIME_DIR}",
            "XDG_DATA_HOME=/root/.local/share", "FLATPAK_BWRAP=${FlatpakManager.BWRAP}",
            "DROIDHOST_BWRAP_SOCKET=${BwrapSpawner.socket(context)}",
            "XDG_DATA_DIRS=/root/.local/share/flatpak/exports/share:/usr/local/share:/usr/share",
        )
        DownloadSource.proxyHostPort(context).takeIf { it.isNotEmpty() }?.let { hostPort ->
            guest += listOf(
                "http_proxy=http://$hostPort", "https_proxy=http://$hostPort",
                "HTTP_PROXY=http://$hostPort", "HTTPS_PROXY=http://$hostPort",
                "no_proxy=localhost,127.0.0.1,::1", "NO_PROXY=localhost,127.0.0.1,::1",
            )
        }
        fastPathKey?.let { guest += ProotFastPath.guestEnv(rootfs, binds, it) }
        guest += argv
        val builder = if (native) {
            NativeGuest.processBuilder(context, rootfs, binds, guest).redirectErrorStream(true)
        } else {
            val cmd = if (guestRoot) LinuxRuntime.prootPrefix(context, rootfs, "/root", "0:0")
                      else LinuxRuntime.prootPrefix(context, rootfs, "/root", fakeRoot)
            if (linkDir != null) cmd.add(1, "--link2symlink")
            binds.forEach { cmd.add("-b"); cmd.add(it) }
            cmd += guest
            ProcessBuilder(cmd).directory(rootfs).redirectErrorStream(true).also { b ->
                b.environment().apply {
                    put("PROOT_LOADER", LinuxRuntime.prootLoader(context).path)
                    put("PROOT_TMP_DIR", LinuxRuntime.prootTempDir(context).path)
                    if (linkDir != null) put("PROOT_L2S_DIR", linkDir.path)
                    if (SessionPrefs.prootNoSeccomp(context)) put("PROOT_NO_SECCOMP", "1")
                    fastPathKey?.let { ProotFastPath.hostEnv(it).let { (k, v) -> put(k, v) } }
                    LinuxRuntime.prootLibraryPath(context).takeIf { it.isNotEmpty() }?.let { put("LD_LIBRARY_PATH", it) }
                }
            }
        }
        if (cancelled?.get() == true) return 130
        val process = builder.start()
        val pid = HostProcess.pidOf(process)
        OrphanReaper.keep(pid)
        val watcher = cancelled?.let { flag ->
            Thread({
                try {
                    while (process.isAlive && !flag.get()) Thread.sleep(200)
                    if (flag.get()) {
                        if (pid > 0) {
                            runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGQUIT) }
                        } else {
                            process.destroy()
                        }
                        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
            }, "guest-cancel").apply { isDaemon = true; start() }
        }
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            return process.waitFor()
        } finally {
            watcher?.interrupt()
            OrphanReaper.release(pid)
        }
    }
}

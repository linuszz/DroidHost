package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import com.droidhost.launcher.runtime.GuestCommand
import com.droidhost.launcher.runtime.LinuxRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fits a desktop into a machine the user brought.
 *
 * A distribution the user brought has no DroidDeck runtime in it: it needs the programs a session
 * runs on, and the settings the desktop is configured with. The programs come from the
 * distribution's own repository, so their versions match the system that is already there, and the
 * settings are the same for every distribution.
 *
 * The install runs on its own thread and keeps the guest alive while it works, because the guest
 * is what fetches the packages.
 */
object VmDesktop {
    private const val TAG = "VmDesktop"
    private const val ASSETS = "droidhost/vm"
    private const val CONFIG_LIST = "$ASSETS/desktop-config.txt"
    private const val MARKER = "usr/share/droiddeck/desktop-installed"
    private const val PACKAGE_LIST = "/root/.droiddeck-packages.txt"
    private const val UPDATE_TIMEOUT_MS = 15 * 60 * 1000L
    private const val INSTALL_TIMEOUT_MS = 90 * 60 * 1000L

    /** What the installer is doing. */
    enum class Stage { IDLE, UPDATING, INSTALLING, CONFIGURING, READY, FAILED }

    /** What the page shows while the desktop is fitted in. */
    data class State(val stage: Stage, val percent: Int, val detail: String) {
        val busy: Boolean
            get() = stage == Stage.UPDATING || stage == Stage.INSTALLING || stage == Stage.CONFIGURING
    }

    @Volatile
    private var progress = State(Stage.IDLE, 0, "")
    private val running = AtomicBoolean(false)
    private val installed = AtomicInteger(0)

    /** How the install is going. */
    fun state(): State = progress

    /** Whether the machine's own rootfs holds a desktop already. */
    fun desktopInstalled(root: File?): Boolean = root != null && File(root, MARKER).isFile

    /**
     * Starts fitting a desktop into the machine.
     *
     * Returns false when an install is already going: a second one would fight the first over
     * the package database.
     */
    fun start(context: Context, machine: VmMachine): Boolean {
        if (!running.compareAndSet(false, true)) return false
        Thread({ install(context.applicationContext, machine) }, "vm-desktop").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun install(context: Context, machine: VmMachine) {
        val root = VmRuntime.ownRootfs(context, machine.id)
        if (root == null) {
            fail("the machine has no rootfs of its own")
            return
        }
        installed.set(0)
        try {
            progress = State(Stage.UPDATING, 3, "apt-get update")
            val update = VmGuest.run(context, machine.id,
                listOf("/usr/bin/apt-get", "update"), guestRoot = true, timeoutMs = UPDATE_TIMEOUT_MS)
            // apt-get update only warns when it cannot reach a mirror, so the warnings are the
            // thing to look at rather than its exit status.
            if (update.exitCode != 0 || update.output.contains("Failed to fetch")) {
                fail("the distribution's repository could not be reached")
                return
            }

            progress = State(Stage.INSTALLING, 10, "reading the runtime's packages")
            val packages = runtimePackages(context)
            if (packages.isEmpty()) {
                fail("the runtime holds no packages to install")
                return
            }
            val wanted = packagesTheMachineHas(context, machine.id, packages)
            if (wanted.isEmpty()) {
                fail("the distribution has none of the packages a session needs")
                return
            }

            progress = State(Stage.INSTALLING, 15, "installing ${wanted.size} packages")
            val result = VmGuest.run(context, machine.id,
                listOf("/bin/bash", "-c", installScript()), guestRoot = true, timeoutMs = INSTALL_TIMEOUT_MS) { line ->
                onInstallLine(line, wanted.size)
            }
            if (result.exitCode != 0) {
                fail("the packages could not be installed")
                return
            }

            progress = State(Stage.CONFIGURING, 90, "the desktop settings")
            val copied = copySettings(context, root)
            if (copied == 0) {
                fail("the desktop settings could not be copied")
                return
            }
            val marker = File(root, MARKER)
            marker.parentFile?.mkdirs()
            marker.writeText("installed ${System.currentTimeMillis()}\n")
            progress = State(Stage.READY, 100, "")
        } catch (e: Exception) {
            Log.w(TAG, "the desktop could not be fitted in", e)
            fail(e.message ?: "the desktop could not be fitted in")
        } finally {
            running.set(false)
        }
    }

    /**
     * The programs the app's own runtime holds.
     *
     * The machine gets the same ones, so a session behaves the same on a system the user brought
     * as on the one the app ships.
     */
    private fun runtimePackages(context: Context): List<String> {
        val out = StringBuilder()
        GuestCommand.run(context, listOf("/usr/bin/dpkg-query", "-W", "-f=\${Package}\n")) { line ->
            out.append(line).append('\n')
        }
        return out.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** The packages of that list the machine's own repository holds. */
    private fun packagesTheMachineHas(context: Context, machineId: String, packages: List<String>): List<String> {
        val filtered = VmGuest.run(context, machineId,
            listOf("/bin/bash", "-c", filterScript(), "_") + packages,
            guestRoot = true, timeoutMs = 10 * 60 * 1000L)
        if (filtered.exitCode != 0) return emptyList()
        val listed = VmGuest.run(context, machineId,
            listOf("/bin/cat", PACKAGE_LIST), guestRoot = true, timeoutMs = 5 * 60 * 1000L)
        return listed.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** How many packages are set up so far, so the page can show how far it got. */
    private fun onInstallLine(line: String, total: Int) {
        if (!line.startsWith("Setting up ")) return
        val done = installed.incrementAndGet()
        val percent = if (total <= 0) 0 else (15 + done * 70 / total).coerceAtMost(85)
        progress = State(Stage.INSTALLING, percent, "$done of $total packages")
    }

    /**
     * The desktop settings, taken from the app's own runtime.
     *
     * They are the same for every distribution: the Plasma layout, the colours and the shortcuts
     * are ours, not the system's.
     */
    private fun copySettings(context: Context, root: File): Int {
        val shared = LinuxRuntime.rootDir(context)
        var copied = 0
        for (path in configPaths(context)) {
            val from = File(shared, path)
            if (!from.isFile) continue
            val to = File(root, path)
            to.parentFile?.mkdirs()
            val copiedOk = runCatching { from.copyTo(to, overwrite = true) }
                .onFailure { Log.w(TAG, "could not copy $path", it) }
                .isSuccess
            if (!copiedOk) continue
            if (path.startsWith("usr/local/bin/") || path.startsWith("usr/share/droiddeck/")) {
                to.setExecutable(true, false)
            }
            copied++
        }
        return copied
    }

    private fun configPaths(context: Context): List<String> =
        runCatching {
            context.assets.open(CONFIG_LIST).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        }.getOrDefault(emptyList())

    private fun fail(detail: String) {
        Log.w(TAG, detail)
        progress = State(Stage.FAILED, 0, detail)
    }

    private fun filterScript(): String = """
for p in "${'$'}@"; do
  apt-cache show --no-show-versions "${'$'}p" >/dev/null 2>&1 && echo "${'$'}p"
done > $PACKAGE_LIST
"""

    private fun installScript(): String = """
export DEBIAN_FRONTEND=noninteractive LC_ALL=C.UTF-8 LANG=C.UTF-8
xargs apt-get install -y --no-install-recommends < $PACKAGE_LIST
"""
}

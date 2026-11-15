package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import com.droidhost.launcher.R
import com.droidhost.launcher.runtime.GuestCommand
import com.droidhost.launcher.runtime.LinuxRuntime
import com.droidhost.launcher.runtime.RuntimeInstallService
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
    private const val PROGRAM_LIST = "$ASSETS/desktop-programs.txt"
    private const val MARKER = "usr/share/droiddeck/desktop-installed"
    private const val PACKAGE_LIST = "/root/.droiddeck-packages.txt"
    private const val INSTALL_LOG = "/root/.droiddeck-install.log"
    private const val UPDATE_TIMEOUT_MS = 15 * 60 * 1000L
    private const val INSTALL_TIMEOUT_MS = 180 * 60 * 1000L
    private const val DRIVER_TIMEOUT_MS = 30 * 60 * 1000L

    /** What the installer is doing. */
    enum class Stage { IDLE, UPDATING, INSTALLING, CONFIGURING, READY, FAILED }

    /** Why the install did not finish. The page turns it into words. */
    enum class Reason { NONE, REPOSITORY_UNREACHABLE, NO_PACKAGES, INSTALL_FAILED, NO_SETTINGS }

    /** What the page shows while the desktop is fitted in. */
    data class State(val stage: Stage, val percent: Int, val reason: Reason = Reason.NONE) {
        val busy: Boolean
            get() = stage == Stage.UPDATING || stage == Stage.INSTALLING || stage == Stage.CONFIGURING
    }

    @Volatile
    private var progress = State(Stage.IDLE, 0)
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
            fail(Reason.NO_PACKAGES)
            return
        }
        installed.set(0)
        // The system freezes an app that sits in the background, and this install takes an hour.
        val hold = RuntimeInstallService.holdForPackage(context, context.getString(R.string.vm_install_desktop))
        try {
            progress = State(Stage.UPDATING, 3)
            report(context, Stage.UPDATING, 3)
            val update = VmGuest.run(context, machine.id,
                listOf("/usr/bin/apt-get", "update"), guestRoot = true, timeoutMs = UPDATE_TIMEOUT_MS)
            // apt-get update only warns when it cannot reach a mirror, so the warnings are the
            // thing to look at rather than its exit status.
            if (update.exitCode != 0 || update.output.contains("Failed to fetch")) {
                fail(Reason.REPOSITORY_UNREACHABLE)
                return
            }

            progress = State(Stage.INSTALLING, 10)
            report(context, Stage.INSTALLING, 10)
            val packages = runtimePackages(context)
            if (packages.isEmpty()) {
                fail(Reason.NO_PACKAGES)
                return
            }
            val wanted = packagesTheMachineHas(context, machine.id, packages)
            if (wanted.isEmpty()) {
                fail(Reason.NO_PACKAGES)
                return
            }

            progress = State(Stage.INSTALLING, 15)
            report(context, Stage.INSTALLING, 15)
            val result = VmGuest.run(context, machine.id,
                listOf("/bin/bash", "-c", installScript()), guestRoot = true, timeoutMs = INSTALL_TIMEOUT_MS) { line ->
                onInstallLine(context, line, wanted.size)
            }
            if (result.exitCode != 0) {
                fail(Reason.INSTALL_FAILED)
                return
            }

            // The driver the app's own runtime has, for a machine that runs the same system.
            if (VmDriver.sameDistribution(context, root) && VmDriver.prepare(context, root)) {
                val driver = VmGuest.run(context, machine.id,
                    listOf("/bin/bash", "-c", VmDriver.installScript()), guestRoot = true,
                    timeoutMs = DRIVER_TIMEOUT_MS) { line ->
                    onInstallLine(context, line, wanted.size)
                }
                if (driver.exitCode != 0) {
                    fail(Reason.INSTALL_FAILED)
                    return
                }
            }

            progress = State(Stage.CONFIGURING, 90)
            report(context, Stage.CONFIGURING, 90)
            val copied = copySettings(context, root)
            if (copied == 0) {
                fail(Reason.NO_SETTINGS)
                return
            }
            val marker = File(root, MARKER)
            marker.parentFile?.mkdirs()
            marker.writeText("installed ${System.currentTimeMillis()}\n")
            progress = State(Stage.READY, 100)
        } catch (e: Exception) {
            Log.w(TAG, "the desktop could not be fitted in", e)
            fail(Reason.INSTALL_FAILED)
        } finally {
            hold()
            running.set(false)
        }
    }

    /** Says what the install is doing in the notification that keeps the app awake. */
    private fun report(context: Context, stage: Stage, percent: Int) {
        val text = when (stage) {
            Stage.UPDATING -> context.getString(R.string.vm_install_desktop_updating)
            Stage.INSTALLING -> context.getString(R.string.vm_install_desktop_running, percent)
            Stage.CONFIGURING -> context.getString(R.string.vm_install_desktop_configuring)
            else -> context.getString(R.string.vm_install_desktop)
        }
        RuntimeInstallService.packageProgress(text, percent)
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
    private fun onInstallLine(context: Context, line: String, total: Int) {
        if (!line.startsWith("Setting up ")) return
        val done = installed.incrementAndGet()
        val percent = if (total <= 0) 0 else (15 + done * 70 / total).coerceAtMost(85)
        progress = State(Stage.INSTALLING, percent)
        report(context, Stage.INSTALLING, percent)
    }

    /**
     * The desktop programs and settings, taken from the app's own runtime.
     *
     * They are the same for every distribution: the Plasma layout, the colours and the shortcuts
     * are ours, not the system's.
     */
    private fun copySettings(context: Context, root: File): Int {
        val shared = LinuxRuntime.rootDir(context)
        var copied = 0
        for (entry in configPaths(context, CONFIG_LIST) + configPaths(context, PROGRAM_LIST)) {
            // A file may sit under more than one name, because the runtime that is installed and
            // the one the fork builds name it differently. It keeps the name it is found under.
            val name = entry.split('|').firstOrNull { File(shared, it).isFile } ?: continue
            val from = File(shared, name)
            val to = File(root, name)
            to.parentFile?.mkdirs()
            val copiedOk = runCatching { from.copyTo(to, overwrite = true) }
                .onFailure { Log.w(TAG, "could not copy $name", it) }
                .isSuccess
            if (!copiedOk) continue
            if (name.startsWith("usr/local/bin/") || name.startsWith("usr/share/droiddeck/")) {
                to.setExecutable(true, false)
            }
            copied++
        }
        return copied
    }

    private fun configPaths(context: Context, list: String): List<String> =
        runCatching {
            context.assets.open(list).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        }.getOrDefault(emptyList())

    private fun fail(reason: Reason) {
        Log.w(TAG, "the desktop could not be fitted in: $reason")
        progress = State(Stage.FAILED, 0, reason)
    }

    internal fun filterScript(): String = """
printf '%s\n' "${'$'}@" > /root/.droiddeck-wanted.txt
apt-cache dumpavail | sed -n 's/^Package: //p' | sort -u > /root/.droiddeck-repo.txt
grep -F -x -f /root/.droiddeck-wanted.txt /root/.droiddeck-repo.txt > $PACKAGE_LIST
"""

    internal fun installScript(): String = """
export DEBIAN_FRONTEND=noninteractive LC_ALL=C.UTF-8 LANG=C.UTF-8
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d
rc=1
for attempt in 1 2 3; do
  xargs -r apt-get install -y --no-install-recommends -o Acquire::Retries=3 < $PACKAGE_LIST 2>&1 | tee -a $INSTALL_LOG
  rc=${'$'}{PIPESTATUS[0]}
  [ "${'$'}rc" = 0 ] && break
  echo "== attempt ${'$'}attempt left the machine at ${'$'}rc"
done
[ "${'$'}rc" = 0 ] && apt-get clean
exit ${'$'}rc
"""
}

package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import com.droidhost.launcher.R
import com.droidhost.launcher.runtime.GuestCommand
import com.droidhost.launcher.runtime.LinuxRuntime
import com.droidhost.launcher.runtime.RuntimeInstallService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    private const val ARCH_PROGRAM_LIST = "$ASSETS/desktop-programs-arch.txt"
    private const val EXCLUDE_LIST = "$ASSETS/desktop-excluded-packages.txt"
    private const val FILE_LIST = "$ASSETS/desktop-files.txt"
    private const val FILES = "$ASSETS/desktop-files"
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

    /** What the page shows while the desktop is fitted in. [machineId] names the machine it is for. */
    data class State(val stage: Stage, val percent: Int, val reason: Reason = Reason.NONE, val machineId: String? = null) {
        val busy: Boolean
            get() = stage == Stage.UPDATING || stage == Stage.INSTALLING || stage == Stage.CONFIGURING
    }

    @Volatile
    private var progress = State(Stage.IDLE, 0)
    @Volatile
    private var currentMachine: String? = null
    private val running = AtomicBoolean(false)
    private val installed = AtomicInteger(0)
    private val cancelled = ConcurrentHashMap<String, Boolean>()

    /** Whether the live desktop job belongs to this machine. */
    fun isRunningFor(machineId: String?): Boolean =
        machineId != null && running.get() && currentMachine == machineId

    /** Marks this machine's desktop install to stop; a delete waits for it then removes the rootfs. */
    fun cancel(context: Context, machineId: String, forDelete: Boolean) {
        if (isRunningFor(machineId)) cancelled[machineId] = true
    }

    /** Waits until this machine's desktop job has ended, so a delete does not race its writes. */
    fun awaitIdle(machineId: String, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (isRunningFor(machineId) && System.currentTimeMillis() < end) {
            runCatching { Thread.sleep(100) }
        }
    }

    /** How the install is going, and which machine it is for. */
    fun state(): State = progress.copy(machineId = currentMachine)

    /** Whether the machine's own rootfs holds a desktop already. */
    fun desktopInstalled(root: File?): Boolean = root != null && File(root, MARKER).isFile

    /** Records that a desktop is in this rootfs, for a seed that copied one already fitted. */
    fun markInstalled(root: File) {
        val marker = File(root, MARKER)
        marker.parentFile?.mkdirs()
        runCatching { marker.writeText("installed ${System.currentTimeMillis()}\n") }
    }

    /**
     * Starts fitting a desktop into the machine.
     *
     * Returns false when an install is already going: a second one would fight the first over
     * the package database.
     */
    fun start(context: Context, machine: VmMachine): Boolean {
        if (!running.compareAndSet(false, true)) return false
        currentMachine = machine.id
        cancelled.remove(machine.id)
        VmStore.setLifecycle(context.applicationContext, machine.id, VmLife.INSTALLING.name)
        Thread({ install(context.applicationContext, machine) }, "vm-desktop").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun install(context: Context, machine: VmMachine) {
        val root = VmRuntime.ownRootfs(context, machine.id)
        if (root == null) {
            fail(context, machine.id, Reason.NO_PACKAGES)
            return
        }
        val pacman = VmPackageManager.of(root) == VmPackageManager.PACMAN
        installed.set(0)
        // The system freezes an app that sits in the background, and this install takes an hour.
        val hold = RuntimeInstallService.holdForPackage(context, context.getString(R.string.vm_install_desktop))
        try {
            progress = State(Stage.UPDATING, 3)
            report(context, Stage.UPDATING, 3)
            val update = if (pacman) {
                VmGuest.run(context, machine.id,
                    listOf("/bin/bash", "-c", pacmanUpdateScript()), guestRoot = true, timeoutMs = UPDATE_TIMEOUT_MS)
            } else {
                VmGuest.run(context, machine.id,
                    listOf("/usr/bin/apt-get", "update"), guestRoot = true, timeoutMs = UPDATE_TIMEOUT_MS)
            }
            // apt-get update only warns when it cannot reach a mirror, so the warnings are the
            // thing to look at rather than its exit status. pacman says it with its exit status.
            if (update.exitCode != 0 || (!pacman && update.output.contains("Failed to fetch"))) {
                fail(context, machine.id, Reason.REPOSITORY_UNREACHABLE)
                return
            }
            if (cancelled[machine.id] == true) { VmStore.setLifecycle(context, machine.id, VmLife.EMPTY.name); return }

            progress = State(Stage.INSTALLING, 10)
            report(context, Stage.INSTALLING, 10)
            val packages = when {
                pacman -> archPrograms(context, machine.desktop)
                machine.desktop == VmDesktops.KDE -> runtimePackages(context)
                else -> bundledPrograms(context, machine.desktop)
            }
            if (packages.isEmpty()) {
                fail(context, machine.id, Reason.NO_PACKAGES)
                return
            }
            val wanted = packagesTheMachineHas(context, machine.id, packages, pacman)
            if (wanted.isEmpty()) {
                fail(context, machine.id, Reason.NO_PACKAGES)
                return
            }

            progress = State(Stage.INSTALLING, 15)
            report(context, Stage.INSTALLING, 15)
            val result = VmGuest.run(context, machine.id,
                listOf("/bin/bash", "-c", installScript(pacman)), guestRoot = true, timeoutMs = INSTALL_TIMEOUT_MS) { line ->
                onInstallLine(context, line, wanted.size)
            }
            if (result.exitCode != 0) {
                fail(context, machine.id, Reason.INSTALL_FAILED)
                return
            }
            if (cancelled[machine.id] == true) { VmStore.setLifecycle(context, machine.id, VmLife.EMPTY.name); return }

            // The driver the app's own runtime has, for a machine that runs the same system.
            if (VmDriver.sameDistribution(context, root) && VmDriver.prepare(context, root)) {
                val driver = VmGuest.run(context, machine.id,
                    listOf("/bin/bash", "-c", VmDriver.installScript()), guestRoot = true,
                    timeoutMs = DRIVER_TIMEOUT_MS) { line ->
                    onInstallLine(context, line, wanted.size)
                }
                if (driver.exitCode != 0) {
                    fail(context, machine.id, Reason.INSTALL_FAILED)
                    return
                }
                VmDriver.markInstalled(root)
            }

            progress = State(Stage.CONFIGURING, 90)
            report(context, Stage.CONFIGURING, 90)
            val copied = copySettings(context, root, machine.desktop)
            if (copied == 0) {
                fail(context, machine.id, Reason.NO_SETTINGS)
                return
            }
            val marker = File(root, MARKER)
            marker.parentFile?.mkdirs()
            marker.writeText("installed ${System.currentTimeMillis()}\n")
            val version = readDesktopVersion(context, machine)
            if (version.isNotEmpty()) VmStore.setDesktopVersion(context, machine.id, version)
            progress = State(Stage.READY, 100)
            VmStore.setLifecycle(context, machine.id, VmLife.READY.name)
        } catch (e: Exception) {
            Log.w(TAG, "the desktop could not be fitted in", e)
            fail(context, machine.id, Reason.INSTALL_FAILED)
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
        return installable(out.toString().lines(), configPaths(context, EXCLUDE_LIST).toSet())
    }

    /** The names worth asking the machine's repository for, with the ones a desktop has no use for left out. */
    internal fun installable(packages: List<String>, excluded: Set<String>): List<String> =
        packages.map { it.trim() }.filter { it.isNotEmpty() && it !in excluded }.distinct()

    /** The packages of that list the machine's own repository holds. */
    private fun packagesTheMachineHas(context: Context, machineId: String, packages: List<String>, pacman: Boolean): List<String> {
        val filtered = VmGuest.run(context, machineId,
            listOf("/bin/bash", "-c", filterScript(pacman), "_") + packages,
            guestRoot = true, timeoutMs = 10 * 60 * 1000L)
        if (filtered.exitCode != 0) return emptyList()
        val listed = VmGuest.run(context, machineId,
            listOf("/bin/cat", PACKAGE_LIST), guestRoot = true, timeoutMs = 5 * 60 * 1000L)
        return listed.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The desktop programs for a pacman machine.
     *
     * The runtime's dpkg names mean nothing on Arch, so each desktop keeps its own list of the
     * names Arch Linux ARM's repositories carry, as assets of the same shape.
     */
    private fun archPrograms(context: Context, desktop: String): List<String> {
        val list = if (desktop == VmDesktops.KDE) ARCH_PROGRAM_LIST else "$ASSETS/desktop-programs-arch-$desktop.txt"
        return installable(configPaths(context, list), configPaths(context, EXCLUDE_LIST).toSet())
    }

    /** The programs of a non-KDE desktop on an apt machine, from the list the fork bundles for it. */
    private fun bundledPrograms(context: Context, desktop: String): List<String> =
        installable(configPaths(context, "$ASSETS/desktop-programs-$desktop.txt"), configPaths(context, EXCLUDE_LIST).toSet())

    /** How many packages are set up so far, so the page can show how far it got. */
    private fun onInstallLine(context: Context, line: String, total: Int) {
        val settingUp = line.startsWith("Setting up ") ||
            line.contains(") installing ") || line.contains(") upgrading ")
        if (!settingUp) return
        val done = installed.incrementAndGet()
        val percent = if (total <= 0) 0 else (15 + done * 70 / total).coerceAtMost(85)
        progress = State(Stage.INSTALLING, percent)
        report(context, Stage.INSTALLING, percent)
    }

    private val versionBackfill = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Labels a machine fitted before versions were recorded, off the caller's thread. */
    fun backfillDesktopVersion(context: Context, machine: VmMachine) {
        val known = machine.desktopVersion
        fun ev(stage: String, extra: Map<String, Any?> = emptyMap()) =
            com.droidhost.launcher.session.SessionEvents.record("desktop.version_backfill",
                mapOf("machineId" to machine.id, "stage" to stage) + extra)
        ev("entry", mapOf("known" to known.take(40), "desktop" to machine.desktop))
        if (known.isNotEmpty() && !known.startsWith("proot")) { ev("skip-clean"); return }
        val root = VmRuntime.ownRootfs(context, machine.id) ?: run { ev("skip-no-rootfs"); return }
        if (!File(root, MARKER).isFile) { ev("skip-no-marker"); return }
        if (!versionBackfill.add(machine.id)) { ev("skip-in-flight"); return }
        Thread({
            try {
                // The guest only answers once its session is up; the page can open mid-boot,
                // so wait for the runtime rather than recording a failure as the version.
                var v = ""
                var attempt = 0
                while (v.isEmpty() && attempt < 12) {
                    v = readDesktopVersion(context.applicationContext, machine)
                    if (v.isEmpty()) { Thread.sleep(3000); attempt++ }
                }
                if (v.isNotEmpty()) {
                    VmStore.setDesktopVersion(context.applicationContext, machine.id, v)
                    ev("recorded", mapOf("version" to v, "attempts" to attempt))
                } else {
                    ev("gave-up", mapOf("attempts" to attempt))
                }
            } finally {
                versionBackfill.remove(machine.id)
            }
        }, "vm-de-version").apply { isDaemon = true; start() }
    }

    /** Asks the fitted guest for its desktop's own version, so the card can name what it runs. */
    private fun readDesktopVersion(context: Context, machine: VmMachine): String {
        // plasmashell initializes a Qt platform plugin even for --version; a freshly spawned proot
        // has no display, so it aborts on xcb before printing. The minimal plugin works headless.
        val cmd = when (machine.desktop) {
            VmDesktops.GNOME -> "gnome-shell --version 2>/dev/null | head -n1"
            VmDesktops.NIRI -> "niri --version 2>/dev/null | head -n1"
            VmDesktops.HYPR -> "Hyprland --version 2>/dev/null | head -n1"
            else -> "QT_QPA_PLATFORM=minimal plasmashell --version 2>/dev/null | head -n1"
        }
        return runCatching {
            val r = VmGuest.run(context, machine.id, listOf("/bin/bash", "-c", cmd),
                guestRoot = true, timeoutMs = 30_000)
            if (r.exitCode != 0) return@runCatching ""
            // proot prints binding warnings into the same stream ahead of the real answer.
            r.output.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("proot") && !it.contains("warning", ignoreCase = true) }
                .orEmpty()
        }.getOrDefault("")
    }

    /**
     * The desktop programs and settings, taken from the app's own runtime.
     *
     * They are the same for every distribution: the Plasma layout, the colours and the shortcuts
     * are ours, not the system's.
     */
    internal fun copySettings(context: Context, root: File, desktop: String = VmDesktops.KDE): Int {
        var copied = copyBundledSettings(context, root)
        // The shared runtime's configs are Plasma's; another desktop does not read them, so only
        // the bundled, desktop-agnostic launcher files go to a non-KDE machine.
        if (desktop != VmDesktops.KDE) return copied
        val shared = LinuxRuntime.rootDir(context)
        val bundled = configPaths(context, FILE_LIST).toSet()
        for (entry in configPaths(context, CONFIG_LIST) + configPaths(context, PROGRAM_LIST)) {
            // A file may sit under more than one name, because the runtime that is installed and
            // the one the fork builds name it differently. The machine gets it under the first
            // name, the one the settings the fork carries refer to it by.
            val names = entry.split('|')
            val name = names.firstOrNull { File(shared, it).isFile } ?: continue
            if (name in bundled) continue
            val from = File(shared, name)
            val to = File(root, names.first())
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

    /**
     * The settings and programs the fork carries itself, copied over whatever the installed
     * runtime holds.
     *
     * A runtime built before a fix still carries the old file, and a machine fitted from it must
     * not inherit the defect. Runs at install, and again before a desktop session for machines
     * fitted from an older runtime.
     */
    internal fun copyBundledSettings(context: Context, root: File): Int {
        var copied = 0
        for (name in configPaths(context, FILE_LIST)) {
            val ok = runCatching {
                context.assets.open("$FILES/$name").use { input ->
                    val to = File(root, name)
                    to.parentFile?.mkdirs()
                    to.outputStream().use { input.copyTo(it) }
                    if (name.startsWith("usr/local/bin/") || name.startsWith("usr/share/droiddeck/")) {
                        to.setExecutable(true, false)
                    }
                }
            }.onFailure { Log.w(TAG, "could not copy bundled $name", it) }.isSuccess
            if (ok) copied++
        }
        return copied
    }

    private fun configPaths(context: Context, list: String): List<String> =
        runCatching {
            context.assets.open(list).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            }
        }.getOrDefault(emptyList())

    private fun fail(context: Context, machineId: String, reason: Reason) {
        Log.w(TAG, "the desktop could not be fitted in: $reason")
        VmStore.setLifecycle(context, machineId, VmLife.FAILED.name, "", reason.name)
        progress = State(Stage.FAILED, 0, reason, machineId)
    }

    internal fun filterScript(pacman: Boolean = false): String = if (pacman) """
printf '%s\n' "${'$'}@" > /root/.droiddeck-wanted.txt
pacman -Slq | sort -u > /root/.droiddeck-repo.txt
grep -F -x -f /root/.droiddeck-wanted.txt /root/.droiddeck-repo.txt > $PACKAGE_LIST
""" else """
printf '%s\n' "${'$'}@" > /root/.droiddeck-wanted.txt
apt-cache dumpavail | sed -n 's/^Package: //p' | sort -u > /root/.droiddeck-repo.txt
grep -F -x -f /root/.droiddeck-wanted.txt /root/.droiddeck-repo.txt > $PACKAGE_LIST
"""

    /**
     * Syncs a pacman machine the Arch way: a full upgrade before anything new is installed.
     *
     * A rolling release does not support partial upgrades, so new packages must not land
     * against old libraries. The keyring is populated first when the image left it empty;
     * which keyrings exist depends on the distribution (Arch Linux ARM carries its own).
     *
     * The sync databases are fetched fresh: libalpm asks the mirror with the local file's
     * time, and a database kept from another mirror can look newer than the server's while
     * holding older names. The server then answers 304 and the stale names stay.
     */
    internal fun pacmanUpdateScript(): String = """
if [ ! -f /etc/pacman.d/gnupg/pubring.gpg ] && [ ! -f /etc/pacman.d/gnupg/pubring.kbx ]; then
  pacman-key --init || exit 1
  keys=""
  for kr in archlinux archlinuxarm; do
    [ -f "/usr/share/pacman/keyrings/${'$'}kr.gpg" ] && keys="${'$'}keys ${'$'}kr"
  done
  [ -n "${'$'}keys" ] && { pacman-key --populate ${'$'}keys || exit 1; }
fi
rm -f /var/lib/pacman/sync/*.db
pacman -Syu --noconfirm
"""

    /**
     * Installs the packages the machine's repository holds, retrying a mirror that fails.
     *
     * The pacman cache is emptied with an explicit yes: pacman 7 asks whether to remove ALL
     * cached files with a default of no, and --noconfirm takes the default, keeping the cache.
     */
    internal fun installScript(pacman: Boolean = false): String = if (pacman) """
export LC_ALL=C.UTF-8 LANG=C.UTF-8
rc=1
for attempt in 1 2 3; do
  xargs -r pacman -S --noconfirm --needed < $PACKAGE_LIST 2>&1 | tee -a $INSTALL_LOG
  rc=${'$'}{PIPESTATUS[0]}
  [ "${'$'}rc" = 0 ] && break
  echo "== attempt ${'$'}attempt left the machine at ${'$'}rc"
done
[ "${'$'}rc" = 0 ] && yes | pacman -Scc
exit ${'$'}rc
""" else """
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

package com.droidhost.launcher.runtime

import android.content.Context
import com.droidhost.launcher.vm.VmStore
import java.io.File

/**
 * Flatpak apps open inside the KWin desktop session: the de-gamified runtime no longer carries
 * SDL2, so the gamescope compositor a run-mode session needs cannot start. Plasma autostarts the
 * app on a cold launch; a launch into an already running desktop queues through its remote-run
 * watcher. Both channels are files in the host rootfs, so the guest scripts stay untouched.
 */
object FlatpakDesktop {
    private const val AUTOSTART_DIR = "root/.config/autostart"
    private const val AUTOSTART_PREFIX = "droidhost-flatpak-"
    private const val REMOTE_RUN_MARKER = "root/.droiddeck-remote-run"
    private const val RUN_DIR = "root/.cache/droiddeck/run"

    /**
     * A flatpak-driven desktop session runs in exactly its host's rootfs, so the host's machine id
     * is authoritative: null means the shared runtime, never the first VM a plain desktop launch
     * would fall back to.
     */
    fun desktopMachineId(context: Context, machineId: String?, flatpakApp: String?): String? =
        if (flatpakApp != null) machineId else machineId ?: VmStore.load(context)?.id

    /** Stale one-shots go, the remote-run watcher is enabled, and [flatpakApp] gets a one-shot KDE autostart entry. */
    fun prepareSession(root: File, flatpakApp: String?, args: List<String>, name: String?) {
        val autostart = File(root, AUTOSTART_DIR)
        autostart.listFiles()?.forEach { if (it.name.startsWith(AUTOSTART_PREFIX)) it.delete() }
        File(root, RUN_DIR).listFiles()?.forEach { it.delete() }
        val marker = File(root, REMOTE_RUN_MARKER)
        marker.parentFile?.mkdirs()
        if (!marker.exists()) marker.createNewFile()
        if (flatpakApp == null || !safeId(flatpakApp) || flatpakApp !in args || !args.all(::shellSafe)) return
        val entry = File(autostart, "$AUTOSTART_PREFIX$flatpakApp.desktop")
        entry.parentFile?.mkdirs()
        // The entry removes itself before exec, so it fires once per launch, not on every Plasma login.
        val command = "rm -f \$HOME/.config/autostart/${entry.name}; exec ${FlatpakManager.LAUNCHER} ${args.joinToString(" ")}"
        val label = (name ?: flatpakApp).replace(Regex("[\\r\\n]"), " ")
        entry.writeText("[Desktop Entry]\nType=Application\nName=$label\nExec=/bin/bash -c \"$command\"\nX-KDE-autostart-phase=2\n")
    }

    /** Queues a one-shot launch into a running desktop; the watcher runs it with the desktop's displays. */
    fun queueLaunch(root: File, appId: String, args: List<String>): Boolean {
        if (!safeId(appId) || appId !in args || !args.all(::shellSafe)) return false
        val run = File(root, RUN_DIR)
        if (!run.isDirectory && !run.mkdirs()) return false
        // The watcher can start before KWin listens on wayland-1; wait for the socket instead of racing it.
        val script = File(run, "flatpak-$appId-${System.nanoTime()}.sh")
        return runCatching {
            script.writeText(
                "#!/bin/bash\n" +
                    "for _ in $(seq 1 60); do\n" +
                    "  [ -S \"\${XDG_RUNTIME_DIR:-/run/droiddeck}/wayland-1\" ] && break\n" +
                    "  sleep 1\n" +
                    "done\n" +
                    "exec ${FlatpakManager.LAUNCHER} ${args.joinToString(" ")}\n",
            )
        }.isSuccess
    }

    private fun safeId(appId: String): Boolean = appId.isNotEmpty() && '/' !in appId && !appId.startsWith(".")

    private fun shellSafe(token: String): Boolean = token.isNotEmpty() && token.all { it.isLetterOrDigit() || it in "._=-" }
}

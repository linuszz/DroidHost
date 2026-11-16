package com.droidhost.launcher.vm

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Heals a panel a machine was configured with before a fix.
 *
 * Plasma writes the layout a machine first starts with into its appletsrc and keeps it from then
 * on, so a corrected layout script alone never reaches a machine that already ran the old one.
 * Before a desktop session starts, an icon the machine cannot resolve falls back to the one the
 * desktop ships, and a pinned program the machine does not have comes off the panel.
 */
object VmPanelHeal {
    private const val TAG = "VmPanelHeal"
    private const val APPLETSRC = "root/.config/plasma-org.kde.plasma.desktop-appletsrc"
    private const val FALLBACK_ICON = "start-here-kde"
    private val MENU_PLUGINS = setOf("org.kde.plasma.kickoff", "org.kde.plasma.kicker")
    private val TASKS_PLUGINS = setOf("org.kde.plasma.icontasks", "org.kde.plasma.tasks")
    private val APPLET = Regex("""^(\[Containments\]\[\d+\](?:\[Applets\]\[\d+\])+)$""")
    private val GENERAL = Regex("""^(\[Containments\]\[\d+\](?:\[Applets\]\[\d+\])+)\[Configuration\]\[General\]$""")
    private val ICON_DIRS = listOf("usr/share/icons", "usr/share/pixmaps", "root/.local/share/icons")
    private val ICON_EXTENSIONS = setOf("svg", "svgz", "png", "xpm")
    private val DESKTOP_DIRS = listOf(
        "usr/share/applications",
        "usr/local/share/applications",
        "root/.local/share/applications",
        "root/.local/share/flatpak/exports/share/applications",
        "var/lib/flatpak/exports/share/applications",
    )

    /** Runs before a desktop session: the fork's own settings, then the panel the machine kept. */
    fun beforeSession(context: Context, root: File) {
        if (VmDesktop.desktopInstalled(root)) VmDesktop.copyBundledSettings(context, root)
        if (heal(root)) Log.i(TAG, "healed the panel: an icon or a pinned program the machine does not have")
    }

    /** Whether anything was healed. */
    fun heal(root: File): Boolean {
        val file = File(root, APPLETSRC)
        if (!file.isFile) return false
        val text = file.readText()
        val healed = healText(text, { iconExists(root, it) }, { desktopExists(root, it) }) ?: return false
        file.writeText(healed)
        return true
    }

    /**
     * The healed appletsrc, or null when nothing needs healing. Kept apart from the filesystem so
     * the parsing can be tested on its own.
     */
    internal fun healText(text: String, iconFound: (String) -> Boolean, desktopFound: (String) -> Boolean): String? {
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val endsWithNewline = text.endsWith("\n")
        val lines = text.split("\n").let { if (endsWithNewline) it.dropLast(1) else it }
            .map { it.removeSuffix("\r") }

        // Which plugin each applet runs, read from its own section first: only a menu's icon and
        // a task list's pins are ours to heal.
        val plugins = HashMap<String, String>()
        var applet: String? = null
        for (line in lines) {
            if (line.startsWith("[")) {
                applet = APPLET.matchEntire(line)?.groupValues?.get(1)
            } else if (applet != null && line.startsWith("plugin=")) {
                plugins[applet] = line.substringAfter('=').trim()
            }
        }

        var changed = false
        var generalOf: String? = null
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.startsWith("[")) {
                generalOf = GENERAL.matchEntire(line)?.groupValues?.get(1)
                out.add(line)
                continue
            }
            val plugin = generalOf?.let { plugins[it] }
            if (plugin != null && plugin in MENU_PLUGINS && line.startsWith("icon=")) {
                val icon = line.substringAfter('=').trim()
                if (icon.isNotEmpty() && icon != FALLBACK_ICON && !iconFound(icon)) {
                    out.add("icon=$FALLBACK_ICON")
                    changed = true
                    continue
                }
            }
            if (plugin != null && plugin in TASKS_PLUGINS && line.startsWith("launchers=")) {
                val pins = line.substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val kept = pins.filter { !it.startsWith("applications:") || desktopFound(it.removePrefix("applications:")) }
                if (kept.size != pins.size) {
                    out.add("launchers=" + kept.joinToString(","))
                    changed = true
                    continue
                }
            }
            out.add(line)
        }
        if (!changed) return null
        return out.joinToString(eol) + (if (endsWithNewline) eol else "")
    }

    /** Whether the machine holds the icon, under any theme it has. */
    private fun iconExists(root: File, name: String): Boolean {
        if (name.startsWith("/")) return File(root, name.removePrefix("/")).isFile
        for (dir in ICON_DIRS) {
            val base = File(root, dir)
            if (!base.isDirectory) continue
            val found = base.walkTopDown().any {
                it.isFile && (it.name == name ||
                    (it.extension.lowercase() in ICON_EXTENSIONS && it.nameWithoutExtension == name))
            }
            if (found) return true
        }
        return false
    }

    /** Whether the machine holds the program entry, wherever its desktop files live. */
    private fun desktopExists(root: File, name: String): Boolean {
        if (name.isEmpty() || name.contains("..")) return false
        return DESKTOP_DIRS.any { File(root, "$it/$name").isFile }
    }
}

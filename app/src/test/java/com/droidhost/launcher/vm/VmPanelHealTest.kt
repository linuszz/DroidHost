package com.droidhost.launcher.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmPanelHealTest {
    @get:Rule val folder = TemporaryFolder()

    private fun panel(
        icon: String = "distributor-logo-steamdeck",
        launchers: String = "applications:steamdeck-steam.desktop,preferred://filemanager,applications:org.kde.konsole.desktop",
    ) = listOf(
        "[Containments][2]",
        "plugin=org.kde.panel",
        "",
        "[Containments][2][Applets][3]",
        "immutability=1",
        "plugin=org.kde.plasma.kickoff",
        "",
        "[Containments][2][Applets][3][Configuration][General]",
        "favorites=preferred://browser",
        "icon=$icon",
        "",
        "[Containments][2][Applets][4]",
        "immutability=1",
        "plugin=org.kde.plasma.icontasks",
        "",
        "[Containments][2][Applets][4][Configuration][General]",
        "launchers=$launchers",
        "",
        "[Containments][2][General]",
        "AppletOrder=3;4;5",
        "",
    ).joinToString("\n")

    private fun appletsrc(root: File, text: String): File =
        File(root, "root/.config/plasma-org.kde.plasma.desktop-appletsrc").apply {
            parentFile?.mkdirs()
            writeText(text)
        }

    @Test fun anIconTheMachineCannotResolveFallsBackToTheDesktopsOwn() {
        val healed = VmPanelHeal.healText(panel(), { false }, { true })!!
        assertTrue(healed.contains("icon=start-here-kde"))
        assertFalse(healed.contains("distributor-logo-steamdeck"))
        assertTrue(healed.contains("launchers=applications:steamdeck-steam.desktop,preferred://filemanager,applications:org.kde.konsole.desktop"))
    }

    @Test fun anIconTheMachineHasIsKept() {
        assertNull(VmPanelHeal.healText(panel(icon = "custom-logo"), { true }, { true }))
    }

    @Test fun theFallbackIconNeedsNoSearch() {
        assertNull(VmPanelHeal.healText(panel(icon = "start-here-kde"),
            { name -> throw AssertionError("the fallback needs no search: $name") }, { true }))
    }

    @Test fun anEmptyIconKeepsPlasmasOwnDefault() {
        assertNull(VmPanelHeal.healText(panel(icon = "", launchers = "preferred://filemanager"), { false }, { true }))
    }

    @Test fun aPinnedProgramTheMachineDoesNotHaveComesOffThePanel() {
        val healed = VmPanelHeal.healText(panel(), { true }, { it != "steamdeck-steam.desktop" })!!
        assertTrue(healed.contains("launchers=preferred://filemanager,applications:org.kde.konsole.desktop"))
        assertTrue(healed.contains("icon=distributor-logo-steamdeck"))
    }

    @Test fun healingTwiceChangesNothingTheSecondTime() {
        val once = VmPanelHeal.healText(panel(), { false }, { it != "steamdeck-steam.desktop" })!!
        assertNull(VmPanelHeal.healText(once, { false }, { it != "steamdeck-steam.desktop" }))
    }

    @Test fun anotherAppletsIconIsNotOursToHeal() {
        val text = panel() + listOf(
            "[Containments][2][Applets][5]",
            "plugin=org.kde.plasma.weather",
            "",
            "[Containments][2][Applets][5][Configuration][General]",
            "icon=missing-weather-icon",
            "",
        ).joinToString("\n")
        val healed = VmPanelHeal.healText(text, { false }, { true })!!
        assertTrue(healed.contains("icon=start-here-kde"))
        assertTrue(healed.contains("icon=missing-weather-icon"))
    }

    @Test fun anAppletNestedInTheTrayIsHealedToo() {
        val text = listOf(
            "[Containments][2][Applets][5]",
            "plugin=org.kde.plasma.systemtray",
            "",
            "[Containments][2][Applets][5][Applets][10]",
            "plugin=org.kde.plasma.kickoff",
            "",
            "[Containments][2][Applets][5][Applets][10][Configuration][General]",
            "icon=distributor-logo-steamdeck",
            "",
            "[Containments][2][Applets][5][General]",
            "extraItems=org.kde.plasma.volume",
            "",
        ).joinToString("\n")
        val healed = VmPanelHeal.healText(text, { false }, { true })!!
        assertTrue(healed.contains("icon=start-here-kde"))
        assertTrue(healed.contains("extraItems=org.kde.plasma.volume"))
    }

    @Test fun theRestOfTheFileSurvivesByteForByte() {
        val text = panel().replace("\n", "\r\n")
        val healed = VmPanelHeal.healText(text, { false }, { it != "steamdeck-steam.desktop" })!!
        val expected = panel()
            .replace("icon=distributor-logo-steamdeck", "icon=start-here-kde")
            .replace("launchers=applications:steamdeck-steam.desktop,preferred://filemanager,applications:org.kde.konsole.desktop",
                "launchers=preferred://filemanager,applications:org.kde.konsole.desktop")
        assertEquals(expected.replace("\n", "\r\n"), healed)
    }

    @Test fun aMachineWithoutAnAppletsrcIsLeftAlone() {
        assertFalse(VmPanelHeal.heal(folder.newFolder("rootfs")))
    }

    @Test fun theHealWritesTheFileOnceAndThenLeavesItAlone() {
        val root = folder.newFolder("rootfs")
        val file = appletsrc(root, panel())
        File(root, "usr/share/applications/org.kde.konsole.desktop").apply {
            parentFile?.mkdirs()
            writeText("[Desktop Entry]\n")
        }
        assertTrue(VmPanelHeal.heal(root))
        val healed = file.readText()
        assertTrue(healed.contains("icon=start-here-kde"))
        assertTrue(healed.contains("launchers=preferred://filemanager,applications:org.kde.konsole.desktop"))
        assertFalse(VmPanelHeal.heal(root))
        assertEquals(healed, file.readText())
    }

    @Test fun anIconUnderAnyThemeTheMachineHoldsIsKept() {
        val root = folder.newFolder("rootfs")
        File(root, "usr/share/icons/breeze/places/16/custom-logo.svg").apply {
            parentFile?.mkdirs()
            writeText("<svg/>\n")
        }
        appletsrc(root, panel(icon = "custom-logo", launchers = "preferred://filemanager"))
        assertFalse(VmPanelHeal.heal(root))
    }

    @Test fun aFlatpakPinIsFoundWhereFlatpakKeepsIt() {
        val root = folder.newFolder("rootfs")
        File(root, "root/.local/share/flatpak/exports/share/applications/com.example.App.desktop").apply {
            parentFile?.mkdirs()
            writeText("[Desktop Entry]\n")
        }
        appletsrc(root, panel(icon = "start-here-kde", launchers = "applications:com.example.App.desktop"))
        assertFalse(VmPanelHeal.heal(root))
    }
}

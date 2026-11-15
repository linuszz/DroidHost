package com.droidhost.launcher.session

import android.content.Context
import com.droidhost.launcher.core.AppUiPrefs
import com.droidhost.launcher.gpu.FrameGen
import com.droidhost.launcher.input.ControllerPrefs
import com.droidhost.launcher.input.InputAction
import com.droidhost.launcher.input.InputControl
import com.droidhost.launcher.input.InputProfile
import com.droidhost.launcher.input.InputProfiles
import com.droidhost.launcher.input.InputRole
import com.droidhost.launcher.input.InputSurfaceLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsResetTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val session get() = prefs("session")

    @Before fun clear() {
        listOf("session", "controller", "input-profiles", "input-surfaces", "app-ui", "frame_gen", "steam_touch", "agent").forEach {
            prefs(it).edit().clear().commit()
        }
    }

    @Test fun settingsGoBackToTheirDefaults() {
        SessionPrefs.setTheme(app, "ocean")
        SessionPrefs.setBackDoubleTapMs(app, 100)
        SessionPrefs.setUnfoldedControlsWithController(app, true)
        SessionPrefs.setNoXalia(app, false)
        session.edit().putInt("fpsLimit.steam", 30).commit()
        SessionPrefs.setHdr(app, SessionService.MODE_DESKTOP, true)
        SessionPrefs.setResolutionChoice(app, SessionService.MODE_STEAM, "1280x800")
        SessionPrefs.setGuestHostname(app, "MyDeck")
        assertTrue(SessionPrefs.setGameDebugLogsEnabled(app, true))
        assertTrue(EnhancedDiagnostics.setArmed(app, true))
        SessionPrefs.setHudShowFps(app, false)
        SessionPrefs.setHudShowBattery(app, false)
        SessionPrefs.setHudShowGraph(app, false)
        SessionPrefs.setHudColor(app, "amber")
        SessionPrefs.setHudOpacity(app, 40)
        SessionPrefs.setDroidHostHudConfig(app, "hudSize=mega,showFPSGraph=0")
        SessionPrefs.setDroidHostHudPosition(app, 120f, 40f)
        ControllerPrefs.setOpacity(app, 40)
        AppUiPrefs.setScale(app, 150)
        FrameGen.set(app, FrameGen.Mode(FrameGen.ENGINE_DDFG, 3))
        prefs("steam_touch").edit().putBoolean("gyro", false).commit()

        assertTrue(SettingsReset.resetAll(app, gameProfiles = false))

        assertEquals("graphite", SessionPrefs.theme(app))
        assertEquals(500, SessionPrefs.backDoubleTapMs(app))
        assertFalse(SessionPrefs.unfoldedControlsWithController(app))
        assertTrue(SessionPrefs.noXalia(app))
        assertFalse(session.contains("fpsLimit.steam"))
        assertFalse(SessionPrefs.hdr(app, SessionService.MODE_DESKTOP))
        assertFalse(SessionPrefs.resolutionChosen(app, SessionService.MODE_STEAM))
        assertEquals(SessionPrefs.DEFAULT_GUEST_HOSTNAME, SessionPrefs.guestHostname(app))
        assertFalse(SessionPrefs.gameDebugLogsEnabled(app))
        assertFalse(EnhancedDiagnostics.isArmed(app))
        val guest = java.io.File(com.droidhost.launcher.runtime.LinuxRuntime.rootDir(app),
            "root/.config/droiddeck/game-environment.json")
        assertFalse(org.json.JSONObject(guest.readText()).getBoolean(GameEnvironmentStore.GAME_DEBUG_LOGS))
        assertTrue(SessionPrefs.hudShowFps(app))
        assertTrue(SessionPrefs.hudShowBattery(app))
        assertTrue(SessionPrefs.hudShowGraph(app))
        assertEquals(SessionPrefs.HUD_COLOR_MINT, SessionPrefs.hudColor(app))
        assertEquals(SessionPrefs.HUD_OPACITY_DEFAULT, SessionPrefs.hudOpacity(app))
        assertNull(SessionPrefs.droidDeckHudConfig(app))
        assertNull(SessionPrefs.droidDeckHudPosition(app))
        assertEquals(100, ControllerPrefs.read(app).opacity)
        assertEquals(AppUiPrefs.DEFAULT_SCALE, AppUiPrefs.scale(app))
        assertEquals(FrameGen.Mode.OFF.engine, FrameGen.engine(app))
        assertFalse(prefs("steam_touch").contains("gyro"))
    }

    @Test fun aFailedDebugOptOutDoesNotHideTheEnabledPreference() {
        assertTrue(SessionPrefs.setGameDebugLogsEnabled(app, true))
        val directory = java.io.File(com.droidhost.launcher.runtime.LinuxRuntime.rootDir(app),
            "root/.config/droiddeck")
        directory.deleteRecursively()
        directory.writeText("blocks configuration publication")
        try {
            assertFalse(SettingsReset.resetAll(app, gameProfiles = false))
            assertTrue(SessionPrefs.gameDebugLogsEnabled(app))
        } finally {
            directory.delete()
            directory.mkdirs()
            GameEnvironmentStore.publish(app)
        }
    }

    @Test fun libraryAndRecordsAreKept() {
        val folder = "/storage/emulated/0/Games/Foo"
        SessionPrefs.setAddedGameName(app, folder, "Foo")
        SessionPrefs.setAddedGameExe(app, folder, "$folder/foo.exe")
        SessionPrefs.addedGameAppId(app, folder, 1234L)
        SessionPrefs.setAddedGameRemoved(app, "/storage/emulated/0/Games/Bar", true)
        SessionPrefs.setGamesFoldersMigrated(app)
        SessionPrefs.setGpuAutoInstalled(app, setOf("turnip-a"))
        SessionPrefs.setRomsDir(app, "/storage/emulated/0/ROMs")
        SessionPrefs.setGameStorage(app, "/storage/ABCD-1234", "SD Card")
        SessionPrefs.setMicAsked(app)
        prefs("steam_touch").edit().putString("copiedLayout", "a:1,2").commit()

        SettingsReset.resetAll(app, gameProfiles = false)

        assertEquals("Foo", SessionPrefs.addedGameName(app, folder))
        assertEquals("$folder/foo.exe", SessionPrefs.addedGameExe(app, folder))
        assertEquals(1234L, SessionPrefs.addedGameStoredAppId(app, folder))
        assertEquals(listOf("/storage/emulated/0/Games/Bar"), SessionPrefs.removedAddedGames(app))
        assertTrue(SessionPrefs.gamesFoldersMigrated(app))
        assertEquals(setOf("turnip-a"), SessionPrefs.gpuAutoInstalled(app))
        assertEquals("/storage/emulated/0/ROMs", SessionPrefs.romsDir(app))
        assertEquals("/storage/ABCD-1234", SessionPrefs.gameStorage(app))
        assertTrue(SessionPrefs.micAsked(app))
        assertEquals("a:1,2", prefs("steam_touch").getString("copiedLayout", null))
    }

    @Test fun inputProfilesAndSurfacesResetWithoutRestoringLegacyMappings() {
        val defaultGame = InputProfiles.read(app, InputProfile.GAME)
        val defaultDesktop = InputProfiles.read(app, InputProfile.DESKTOP)
        val defaultSurface = InputSurfaceLayout.read(app, InputProfile.DESKTOP)
        ControllerPrefs.setTarget(app, "a", "b")
        InputProfiles.select(app, InputProfile.DESKTOP)
        InputProfiles.save(app, InputProfile.defaults(InputProfile.GAME)
            .withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.Disabled))
        InputSurfaceLayout.save(app, InputProfile.DESKTOP, emptyList())

        assertTrue(SettingsReset.resetAll(app, gameProfiles = false))

        assertEquals(InputProfiles.AUTO, InputProfiles.selection(app))
        assertEquals(defaultGame, InputProfiles.read(app, InputProfile.GAME))
        assertEquals(defaultDesktop, InputProfiles.read(app, InputProfile.DESKTOP))
        assertEquals(defaultSurface, InputSurfaceLayout.read(app, InputProfile.DESKTOP))
    }

    @Test fun inputLayoutsAndQuickButtonVisibilityReset() {
        val original = com.droidhost.launcher.input.InputLibraries.read(app)
        val gamepad = original.layouts.first { it.id == com.droidhost.launcher.input.InputLibraries.OVERLAY }
        com.droidhost.launcher.input.InputLibraries.update(app) { library ->
            library.withLayout(gamepad.withQuickButton(com.droidhost.launcher.input.InputCommand.STEAM, false)
                .copy(elements = gamepad.elements.map { it.copy(x = 0f, y = 0f) })).copyLayout(gamepad.id, "Mine", "mine")
        }

        assertTrue(SettingsReset.resetAll(app, gameProfiles = false))

        val reset = com.droidhost.launcher.input.InputLibraries.read(app)
        assertEquals(original.layouts.map { it.id }.toSet(), reset.layouts.map { it.id }.toSet())
        assertEquals(gamepad, reset.layouts.first { it.id == gamepad.id })
    }

    @Test fun gameTextureFilteringIsKeptUnlessGameProfilesAreReset() {
        session.edit().putString("gameTextureFiltering", """{"440":{"anisotropy":16}}""").commit()
        SettingsReset.resetAll(app, gameProfiles = false)
        assertEquals(16, SessionPrefs.gameTextureFiltering(app, "440").anisotropy)

        SettingsReset.resetAll(app, gameProfiles = true)
        assertTrue(SessionPrefs.gameTextureFiltering(app).isEmpty())
    }

    @Test fun perModeKeysAreSettings() {
        listOf("resolutionCap.steam", "displayResolution.desktop", "customRes.steam", "hdr.steam", "fpsLimit.desktop", "suspendPolicy.steam")
            .forEach { assertTrue(it, SettingsReset.isSessionSetting(it)) }
        listOf("addedName:/x", "addedAppId:/x", "gpuAutoInstalled", "romsDir", "gameStorage", "micAsked", "gameTextureFiltering")
            .forEach { assertFalse(it, SettingsReset.isSessionSetting(it)) }
    }
}

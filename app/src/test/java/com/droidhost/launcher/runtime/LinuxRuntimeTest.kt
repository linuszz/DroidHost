package com.droidhost.launcher.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LinuxRuntimeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() { LinuxRuntime.rootDir(ctx).deleteRecursively() }
    @After fun tearDown() { LinuxRuntime.rootDir(ctx).deleteRecursively() }

    private fun write(path: String) =
        File(LinuxRuntime.rootDir(ctx), path).apply { parentFile?.mkdirs(); writeText("#!/bin/sh") }

    @Test fun aDesktopRuntimeIsPresentWithoutGamescope() {
        write("usr/local/bin/droiddeck-session")
        assertTrue(LinuxRuntime.hasRootfs(ctx))
    }

    @Test fun theLegacySessionScriptStillCounts() {
        write("usr/local/bin/bannerlator-session")
        assertTrue(LinuxRuntime.hasRootfs(ctx))
    }

    @Test fun gamescopeAloneDoesNotMakeARuntime() {
        write("usr/bin/gamescope")
        assertFalse(LinuxRuntime.hasRootfs(ctx))
    }

    @Test fun anEmptyRootfsIsNotPresent() {
        LinuxRuntime.rootDir(ctx).mkdirs()
        assertFalse(LinuxRuntime.hasRootfs(ctx))
    }
}

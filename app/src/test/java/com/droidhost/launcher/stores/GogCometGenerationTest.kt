package com.droidhost.launcher.stores

import com.droidhost.launcher.session.SessionState
import com.droidhost.launcher.stores.gog.GogComet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GogCometGenerationTest {
    @After fun reset() {
        GogComet.end(SessionState.sessionId)
        SessionState.running = false
        SessionState.sessionId = null
    }

    @Test fun anOldTeardownCannotDisableTheReplacementSession() {
        SessionState.running = true
        SessionState.sessionId = "old"
        GogComet.begin()
        SessionState.sessionId = "replacement"
        GogComet.begin()
        GogComet.end("old")
        val app = RuntimeEnvironment.getApplication()
        assertEquals("no-game", GogComet.prepare(app, "missing").getString("reason"))
        GogComet.end("replacement")
        assertEquals("no-session", GogComet.prepare(app, "missing").getString("reason"))
    }
}

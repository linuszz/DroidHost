package com.droidhost.launcher.vm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmDriverTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun theDriverGoesOnlyToAMachineThatRunsTheSameSystem() {
        // The Mesa and KWin builds belong to one release; another machine must not get them.
        val shared = folder.newFile("shared-release")
        shared.writeText("ID=ubuntu\nVERSION_ID=\"26.04\"\n")
        val same = folder.newFile("same-release")
        same.writeText("ID=ubuntu\nVERSION_ID=\"26.04\"\n")
        val other = folder.newFile("other-release")
        other.writeText("ID=ubuntu\nVERSION_ID=\"24.04\"\n")
        assertTrue(VmDriver.sameRelease(shared, same))
        assertFalse(VmDriver.sameRelease(shared, other))
    }

    @Test fun theDriverReadsTheLocalRepositoryOnly() {
        val script = VmDriver.installScript()
        assertTrue(script.contains("droiddeck.list"))
        assertTrue(script.contains("mesa-vulkan-drivers"))
        assertTrue(script.contains("kwin-wayland"))
    }
}

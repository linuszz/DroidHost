package com.droidhost.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RootSpawnerTest {
    @Test fun aCommandThatNamesNoMachineRunsInTheAppsOwnRuntime() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(LinuxRuntime.rootDir(context).canonicalPath,
            RootSpawner.rootFor(context, "").canonicalPath)
    }

    @Test fun aCommandThatNamesAMachineRunsInThatMachine() {
        val context = RuntimeEnvironment.getApplication()
        val machine = File(context.filesDir, "vms/vm-test/rootfs").apply { mkdirs() }
        assertEquals(machine.canonicalPath, RootSpawner.rootFor(context, "vm-test").canonicalPath)
    }

    @Test fun aNameThatIsNoMachineFallsBackToTheAppsRuntime() {
        val context = RuntimeEnvironment.getApplication()
        val runtime = LinuxRuntime.rootDir(context).canonicalPath
        assertEquals(runtime, RootSpawner.rootFor(context, "../../etc").canonicalPath)
        assertEquals(runtime, RootSpawner.rootFor(context, "vm-missing").canonicalPath)
    }
}

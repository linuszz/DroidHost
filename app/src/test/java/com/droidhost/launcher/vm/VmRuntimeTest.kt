package com.droidhost.launcher.vm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.droidhost.launcher.runtime.LinuxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VmRuntimeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).edit().clear().commit()
        VmRuntime.machinesDir(ctx).deleteRecursively()
    }

    @Test fun aMachineWithoutARootfsRunsOnTheSharedRuntime() {
        assertEquals(LinuxRuntime.rootDir(ctx), VmRuntime.rootFor(ctx, "vm-1"))
        assertNull(VmRuntime.ownRootfs(ctx, "vm-1"))
    }

    @Test fun noMachineAtAllRunsOnTheSharedRuntime() {
        assertEquals(LinuxRuntime.rootDir(ctx), VmRuntime.rootFor(ctx, null))
    }

    @Test fun aRootfsOfItsOwnIsUsedWhenTheMachineHasOne() {
        val rootfs = VmRuntime.allocateRootfs(ctx, "vm-1")
        assertTrue(rootfs.isDirectory)
        assertEquals(rootfs, VmRuntime.ownRootfs(ctx, "vm-1"))
        assertEquals(rootfs, VmRuntime.rootFor(ctx, "vm-1"))
    }

    @Test fun theRootfsSitsBesideTheMachinesAndNotInsideTheSharedRuntime() {
        val rootfs = VmRuntime.allocateRootfs(ctx, "vm-1")
        assertEquals(File(ctx.filesDir, "vms/vm-1/rootfs"), rootfs)
        assertFalse(rootfs.path.startsWith(LinuxRuntime.rootDir(ctx).path))
    }

    @Test fun theSharedRuntimeIsUntouchedByAMachineRootfs() {
        val shared = LinuxRuntime.rootDir(ctx)
        shared.mkdirs()
        val marker = File(shared, "usr/bin/gamescope")
        marker.parentFile?.mkdirs()
        marker.writeText("runtime")
        VmRuntime.allocateRootfs(ctx, "vm-1")
        assertTrue(marker.isFile)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VmStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun noMachineIsStoredUntilOneIsCreated() {
        assertNull(VmStore.load(ctx))
        assertTrue(VmStore.all(ctx).isEmpty())
    }

    @Test fun aCreatedMachineComesBack() {
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        assertEquals(VmCatalog.UBUNTU, machine.distro.id)
        assertEquals(machine.id, VmStore.load(ctx)!!.id)
        assertEquals(1, VmStore.all(ctx).size)
    }

    @Test fun aMachineThatHasNoEngineIsNotCreated() {
        assertNull(VmStore.create(ctx, VmCatalog.distros.first { !it.engine }))
        assertNull(VmStore.load(ctx))
    }

    @Test fun theRuntimeOwnsOneRootfsSoASecondMachineIsRefused() {
        assertTrue(VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!) != null)
        assertNull(VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!))
        assertEquals(1, VmStore.all(ctx).size)
    }

    @Test fun aMachineFromAnOlderBuildIsStillThere() {
        // The build that predates the machine list kept one machine under a single key.
        ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).edit()
            .putString("machine", VmCatalog.UBUNTU + "|1700000000000").commit()
        val machine = VmStore.load(ctx)!!
        assertEquals(VmCatalog.UBUNTU, machine.distro.id)
        assertEquals(VmMachine.DEFAULT_ID, machine.id)
    }

    @Test fun forgettingRemovesTheMachine() {
        VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)
        VmStore.forget(ctx)
        assertNull(VmStore.load(ctx))
        assertTrue(VmStore.all(ctx).isEmpty())
    }

    @Test fun theMachineSurvivesANewStoreInstance() {
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        assertEquals(machine.id, VmStore.all(ctx).first().id)
        assertEquals(machine.createdAt, VmStore.all(ctx).first().createdAt)
    }
}

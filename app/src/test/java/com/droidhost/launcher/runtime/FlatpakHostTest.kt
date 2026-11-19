package com.droidhost.launcher.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.droidhost.launcher.vm.VmCatalog
import com.droidhost.launcher.vm.VmRuntime
import com.droidhost.launcher.vm.VmStore
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
class FlatpakHostTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun reset() {
        ctx.getSharedPreferences("flatpak", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("vm", Context.MODE_PRIVATE).edit().clear().commit()
        VmRuntime.machinesDir(ctx).deleteRecursively()
        LinuxRuntime.rootDir(ctx).deleteRecursively()
    }

    @Test fun byDefaultFlatpakUsesTheSharedRuntime() {
        assertNull(FlatpakHost.hostMachineId(ctx))
        assertEquals(LinuxRuntime.rootDir(ctx), FlatpakHost.rootfs(ctx))
    }

    @Test fun choosingAMachineMovesFlatpakIntoItsOwnRootfs() {
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        val rootfs = VmRuntime.allocateRootfs(ctx, machine.id)
        FlatpakHost.setHost(ctx, machine.id)
        assertEquals(machine.id, FlatpakHost.hostMachineId(ctx))
        assertEquals(rootfs, FlatpakHost.rootfs(ctx))
    }

    @Test fun aMachineWithoutItsOwnRootfsFallsBackToShared() {
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        FlatpakHost.setHost(ctx, machine.id)
        assertNull(FlatpakHost.hostMachineId(ctx))
        assertEquals(LinuxRuntime.rootDir(ctx), FlatpakHost.rootfs(ctx))
    }

    @Test fun aDeletedMachineFallsBackAndIsForgotten() {
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        VmRuntime.allocateRootfs(ctx, machine.id)
        FlatpakHost.setHost(ctx, machine.id)
        VmStore.remove(ctx, machine)
        assertNull(FlatpakHost.hostMachineId(ctx))
        assertEquals(LinuxRuntime.rootDir(ctx), FlatpakHost.rootfs(ctx))
        assertEquals("", ctx.getSharedPreferences("flatpak", Context.MODE_PRIVATE).getString("host", null))
    }

    @Test fun aHostIsEligibleWhenAMachineCarriesTheFlatpakBinary() {
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        val rootfs = VmRuntime.allocateRootfs(ctx, machine.id)
        val flatpak = File(rootfs, "usr/bin/flatpak")
        flatpak.parentFile?.mkdirs()
        flatpak.writeText("#!/bin/sh")
        assertTrue(FlatpakHost.anyEligible(ctx))
    }

    @Test fun aMachineRootfsWithoutFlatpakIsNotEligible() {
        val machine = VmStore.create(ctx, VmCatalog.of(VmCatalog.UBUNTU)!!)!!
        VmRuntime.allocateRootfs(ctx, machine.id)
        assertFalse(FlatpakHost.anyEligible(ctx))
    }

    @Test fun noHostIsEligibleWithoutARuntimeOrFlatpakBinary() {
        assertFalse(FlatpakHost.anyEligible(ctx))
    }
}

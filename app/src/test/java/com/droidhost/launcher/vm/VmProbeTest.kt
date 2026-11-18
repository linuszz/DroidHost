package com.droidhost.launcher.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VmProbeTest {
    @get:Rule val folder = TemporaryFolder()

    private fun rootfs(
        release: String?,
        loader: String? = "lib/ld-linux-aarch64.so.1",
        tools: List<String> = emptyList(),
        extra: List<String> = emptyList(),
    ): File {
        val root = folder.newFolder()
        if (release != null) {
            val file = File(root, "etc/os-release")
            file.parentFile?.mkdirs()
            file.writeText(release)
        }
        if (loader != null) {
            val file = File(root, loader)
            file.parentFile?.mkdirs()
            file.writeText("")
        }
        for (path in tools + extra) {
            val file = File(root, path)
            file.parentFile?.mkdirs()
            file.writeText("")
        }
        return root
    }

    @Test fun aDebianSystemIsReadTheWayItDescribesItself() {
        val probe = VmProbeReader.probe(rootfs(
            "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\nNAME=\"Debian GNU/Linux\"\nID=debian\nVERSION_ID=\"13\"\n",
            tools = listOf("/usr/bin/apt"),
        ))
        assertTrue(probe.usable)
        assertEquals("debian", probe.id)
        assertEquals("13", probe.versionId)
        assertEquals("Debian GNU/Linux 13 (trixie)", probe.prettyName)
        assertEquals("apt", probe.packageManager)
        assertEquals("aarch64", probe.architecture)
        assertEquals(VmDriverTier.CONFIGURATION, probe.driverTier)
    }

    @Test fun anUbuntuSystemIsReadToo() {
        val probe = VmProbeReader.probe(rootfs(
            "PRETTY_NAME=\"Ubuntu 24.04.1 LTS\"\nID=ubuntu\nVERSION_ID=\"24.04\"\n",
            tools = listOf("/usr/bin/apt"),
        ))
        assertTrue(probe.usable)
        assertEquals("ubuntu", probe.id)
        assertEquals(VmDriverTier.CONFIGURATION, probe.driverTier)
    }

    @Test fun thePackagedDistributionReportsThePackagedTier() {
        val probe = VmProbeReader.probe(rootfs(
            "PRETTY_NAME=\"Ubuntu 26.04 LTS\"\nID=ubuntu\nVERSION_ID=\"26.04\"\n",
            tools = listOf("/usr/bin/apt"),
        ))
        assertTrue(probe.usable)
        assertEquals(VmDriverTier.PACKAGED, probe.driverTier)
    }

    @Test fun aDistributionWeHaveNoDriverPackageForFallsBackToConfiguration() {
        val probe = VmProbeReader.probe(rootfs(
            "PRETTY_NAME=\"Arch Linux\"\nID=arch\nVERSION_ID=\"rolling\"\n",
            tools = listOf("/usr/bin/pacman"),
        ))
        assertTrue(probe.usable)
        assertEquals(VmDriverTier.CONFIGURATION, probe.driverTier)
        assertEquals("pacman", probe.packageManager)
    }

    @Test fun aSystemBuiltForAnotherArchitectureIsRefused() {
        val probe = VmProbeReader.probe(rootfs(
            "PRETTY_NAME=\"Debian GNU/Linux 13\"\nID=debian\nVERSION_ID=\"13\"\n",
            loader = "lib64/ld-linux-x86-64.so.2",
        ))
        assertFalse(probe.usable)
        assertEquals(VmProblem.WRONG_ARCHITECTURE, probe.problem)
        assertEquals("x86-64", probe.architecture)
    }

    @Test fun somethingThatIsNotASystemIsRefused() {
        val probe = VmProbeReader.probe(rootfs(null, loader = null))
        assertFalse(probe.usable)
        assertEquals(VmProblem.NOT_A_SYSTEM, probe.problem)
    }

    @Test fun aSystemThatDoesNotSayWhichOneItIsIsRefused() {
        val probe = VmProbeReader.probe(rootfs("NAME=\"Something\"\nVERSION_ID=\"1\"\n"))
        assertFalse(probe.usable)
        assertEquals(VmProblem.NO_DISTRIBUTION, probe.problem)
    }

    @Test fun aRootfsThatIsNotThereIsRefused() {
        val probe = VmProbeReader.probe(File(folder.root, "nowhere"))
        assertFalse(probe.usable)
        assertEquals(VmProblem.NOT_READABLE, probe.problem)
    }

    @Test fun thePackageManagerIsFoundWhereverItSits() {
        assertEquals("dnf", VmProbeReader.probe(rootfs(
            "ID=fedora\nVERSION_ID=\"41\"\n", tools = listOf("/usr/bin/dnf"),
        )).packageManager)
        assertEquals("apk", VmProbeReader.probe(rootfs(
            "ID=alpine\nVERSION_ID=\"3.21\"\n", tools = listOf("/sbin/apk"),
        )).packageManager)
    }

    @Test fun aSystemWithoutAPackageManagerIsStillReadable() {
        val probe = VmProbeReader.probe(rootfs("ID=debian\nVERSION_ID=\"13\"\n"))
        assertTrue(probe.usable)
        assertNull(probe.packageManager)
    }

    @Test fun theOlderOsReleaseIsReadWhenThereIsNoOther() {
        val root = folder.newFolder()
        val file = File(root, "usr/lib/os-release")
        file.parentFile?.mkdirs()
        file.writeText("ID=debian\nVERSION_ID=\"12\"\n")
        assertEquals("debian", VmProbeReader.probe(root).id)
    }

    @Test fun aRootfsThatCarriesItsOwnGpuStackIsNoticed() {
        val probe = VmProbeReader.probe(rootfs(
            "ID=debian\nVERSION_ID=\"13\"\n",
            extra = listOf("usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"),
        ))
        assertTrue(probe.hasGpuStack)
    }

    @Test fun anArchLinuxArmGpuStackIsNoticedWhereItKeepsIt() {
        // Arch Linux ARM keeps a flat /usr/lib and rolls without a VERSION_ID.
        val probe = VmProbeReader.probe(rootfs(
            "PRETTY_NAME=\"Arch Linux ARM\"\nID=arch\n",
            tools = listOf("/usr/bin/pacman"),
            extra = listOf("usr/lib/libvulkan_freedreno.so"),
        ))
        assertTrue(probe.usable)
        assertTrue(probe.hasGpuStack)
        assertEquals("pacman", probe.packageManager)
        assertEquals("", probe.versionId)
        assertEquals(VmDriverTier.CONFIGURATION, probe.driverTier)
    }

    @Test fun theLoaderIsFoundWhereverTheDistributionKeepsIt() {
        assertEquals("aarch64", VmProbeReader.probe(rootfs(
            "ID=ubuntu\nVERSION_ID=\"24.04\"\n",
            loader = "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
        )).architecture)
        assertEquals("aarch64", VmProbeReader.probe(rootfs(
            "ID=ubuntu\nVERSION_ID=\"24.04\"\n",
            loader = "usr/lib/ld-linux-aarch64.so.1",
        )).architecture)
    }
}

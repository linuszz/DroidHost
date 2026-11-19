package com.droidhost.launcher.vm

import com.droidhost.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VmCatalogTest {
    @Test
    fun everyBuiltinDistroCarriesItsOwnLogo() {
        val logos = VmCatalog.builtins.map { VmCatalog.logoRes(it.distro) }
        logos.forEach { assertNotNull(it) }
        assertEquals(logos.size, logos.distinct().size)
        assertEquals(R.drawable.distro_ubuntu, VmCatalog.logoRes(VmCatalog.UBUNTU))
        assertEquals(R.drawable.distro_debian, VmCatalog.logoRes(VmCatalog.DEBIAN))
        assertEquals(R.drawable.distro_arch, VmCatalog.logoRes(VmCatalog.ARCH))
    }

    @Test
    fun systemsWithoutPackagedArtKeepTheGenericIcon() {
        assertNull(VmCatalog.logoRes("fedora"))
        assertNull(VmCatalog.logoRes("windows-11"))
        assertNull(VmCatalog.logoRes("a-user-import"))
    }
}

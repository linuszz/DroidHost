package com.droidhost.launcher.stores

/** The stores whose launcher services this build no longer ships; see docs/fork-upstream.md. */
object StoreGating {
    val disabled: Set<Store> = setOf(Store.GOG, Store.UBISOFT, Store.ROCKSTAR)
}

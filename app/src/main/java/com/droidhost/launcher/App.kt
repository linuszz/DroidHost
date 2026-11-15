package com.droidhost.launcher

import android.app.Application
import com.droidhost.launcher.runtime.BwrapSpawner
import com.droidhost.launcher.session.CrashHandler
import com.droidhost.launcher.session.EnhancedSessionLogShare
import com.droidhost.launcher.session.GpuClockPin

class App : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.droidhost.launcher.core.AppLanguage.wrap(base))
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName() else packageName
        com.droidhost.launcher.lab.LabOverlay.load(this, process == packageName)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.droidhost.launcher.core.AppLanguage.systemChanged(newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        Thread({ runCatching { EnhancedSessionLogShare.cleanupAbandonedStages(this) } }, "diagnostic-stage-cleanup").start()
        com.droidhost.launcher.ui.Themes.loadGradient(this)
        com.droidhost.launcher.ui.BuildWatermark.install(this)
        HomeApp.trackRecents(this)
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        if (process == "$packageName:xboxlogin") {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.webkit.WebView.setDataDirectorySuffix("xbox")
            return
        }
        if (process == "$packageName:xboxcloud") {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.webkit.WebView.setDataDirectorySuffix("xboxcloud")
            return
        }
        if (process == "$packageName:xcloudnative") {
            com.droidhost.launcher.stores.StoresState.init(this)
            return
        }
        if (process == "$packageName:vr_recovery" || process == "$packageName:rgb_diagnostic" ||
            process == "$packageName:shortcuts_sync" || process == "$packageName:bottom_desktop") return
        CrashHandler.install(this)
        com.droidhost.launcher.agent.AgentHost.refresh(this)
        com.droidhost.launcher.agent.AgentTab.load(this)
        com.droidhost.launcher.ui.NavTabs.load(this)
        com.droidhost.launcher.ui.AgentPet.install(this)
        if (com.droidhost.launcher.agent.AgentTab.visible) com.droidhost.launcher.lab.LabNotice.openOnInstall = "agent"
        com.droidhost.launcher.lab.LabNotice.started(this)
        com.droidhost.launcher.core.SteamIntegration.syncShortcutsListing(this)
        com.droidhost.launcher.session.QuestExperimental.initialize(this)
        com.droidhost.launcher.session.SessionPrefs.applyNtsyncDefault(this)
        com.droidhost.launcher.session.SessionPrefs.settleGpuDriverMode(this)
        com.droidhost.launcher.update.AppUpdates.init(this)
        com.droidhost.launcher.update.UpdateWatch.sync(this, com.droidhost.launcher.update.AppUpdates.follow(this, null))
        com.droidhost.launcher.core.DownloadSource.init(this)
        prefetchAddonPacks()
        GpuClockPin.clearLeftover(this)
        com.droidhost.launcher.glasses.Glasses.init(this)
        com.droidhost.launcher.glasses.GlassesMenuMirror.initialize(this)
        BwrapSpawner.start(this)
        com.droidhost.launcher.runtime.RootSpawner.start(this)
        com.droidhost.launcher.frontend.GameFileSync.start(this)
        com.droidhost.launcher.stores.StoresState.init(this)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.gog.GogBackend)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.epic.EpicBackend)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.amazon.AmazonBackend)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.itch.ItchBackend)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.gamejolt.GameJoltBackend)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.xbox.XboxBackend)
        com.droidhost.launcher.sync.Syncthing.init(this)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.battlenet.BattleNetBackend)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.ubisoft.UbisoftBackend)
        com.droidhost.launcher.stores.ea.EaBackend.app = this
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.ea.EaBackend)
        com.droidhost.launcher.stores.StoresState.register(com.droidhost.launcher.stores.rockstar.RockstarBackend)
    }

    private fun prefetchAddonPacks() {
        val app = this
        Thread({
            runCatching {
                if (com.droidhost.launcher.runtime.LinuxRuntime.rootDir(app).isDirectory) {
                    com.droidhost.launcher.core.AddonPack.PROOT.ensure(app)
                    com.droidhost.launcher.core.AddonPack.DIRECTAUDIO.ensure(app)
                }
                if (com.droidhost.launcher.stores.gog.GogComet.enabled(app)) com.droidhost.launcher.core.AddonPack.COMET.ensure(app)
                if (com.droidhost.launcher.stores.StoreAccounts.signedInAs(app, com.droidhost.launcher.stores.Store.XBOX) != null)
                    com.droidhost.launcher.core.AddonPack.XBOX.ensure(app)
                if (com.droidhost.launcher.stores.StoreAccounts.signedInAs(app, com.droidhost.launcher.stores.Store.UBISOFT) != null)
                    com.droidhost.launcher.session.SessionFiles.ensureUbisoftService(app)
                com.droidhost.launcher.handtracking.HandTracking.load(app)
            }.onFailure { android.util.Log.w("App", "add-on pack prefetch failed", it) }
        }, "addon-packs").start()
    }
}

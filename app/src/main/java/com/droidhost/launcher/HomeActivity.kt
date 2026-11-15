package com.droidhost.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.droidhost.launcher.session.SessionService
import com.droidhost.launcher.session.SessionState

class HomeActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droidhost.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = com.droidhost.launcher.core.AppUiPrefs.orientation(this)
        routeHome()
    }

    private fun routeHome() {
        val destination = if (HomeApp.isActiveSteamSession() && !SessionState.pipActive) {
            Intent(this, SessionActivity::class.java)
                .setAction(SessionService.ACTION_HOME_GUIDE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent(this, FrontEndActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply {
                    val page = com.droidhost.launcher.core.AppUiPrefs.homePage(this@HomeActivity)
                    if (page != com.droidhost.launcher.core.AppUiPrefs.HOME_PAGE_DESKTOP) {
                        putExtra(com.droidhost.launcher.stores.download.StoreDownloadService.EXTRA_NAV, page)
                    }
                }
        }
        if (SessionState.pipActive && destination.component?.className == FrontEndActivity::class.java.name) HomeApp.showFrontEnd(this, destination)
        else startActivity(destination)
        finish()
    }
}

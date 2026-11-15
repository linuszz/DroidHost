package com.droidhost.launcher.session

internal class GameProfileFollowState {
    var followsSteam = true
        private set

    fun selectManually() {
        followsSteam = false
    }

    fun resumeFollowingSteam() {
        followsSteam = true
    }
}

package com.droidhost.launcher.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * nubia's display policy drops window-level refresh votes (AppRequestObserver
 * `mIgnorePreferredRefreshRate=true` in dumpsys display), so a plain Compose window stays
 * downclocked at 60 Hz even with preferredDisplayModeId set. Only the SurfaceFlinger-level
 * producer frame-rate vote — the channel the video session already uses in CompositorHost —
 * raises the panel, and only while that layer actually keeps producing frames: a layer that
 * posted a single buffer is treated as static and its vote never reaches the display.
 *
 * This overlay is that vote without video: a tiny transparent surface that posts frames on
 * demand and asks the panel for the peak rate. Mode is switchable without a rebuild through
 * the global setting `droidhost_peak_vote_mode`:
 *   off    - no frames, no vote
 *   on     - frames while the user touches the screen (default)
 *   boot   - frames for the first few seconds after launch
 *   always - frames all the time
 */
class PeakSurfaceVote(activity: Activity) : SurfaceView(activity), SurfaceHolder.Callback {
    private var target = 0f
    private var ready = false
    private var touching = false
    private var booting = false
    private val mode = readMode(activity)
    private val handler = Handler(Looper.getMainLooper())

    private val frameTick = object : Runnable {
        override fun run() {
            if (!ready || target <= 0f) return
            val period = (1000.0 / target).toLong().coerceAtLeast(4L)
            postFrame()
            handler.postDelayed(this, period)
        }
    }

    init {
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        holder.addCallback(this)
        val size = (64 * resources.displayMetrics.density).toInt().coerceAtLeast(16)
        activity.findViewById<ViewGroup>(android.R.id.content).addView(
            this, 0, FrameLayout.LayoutParams(size, size),
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        setTouching(event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE)
        return false
    }

    /** The activity forwards touches here: a Compose page consumes them before our view sees them. */
    fun setTouching(active: Boolean) {
        if (touching == active) return
        touching = active
        syncFrames()
    }

    fun setHz(hz: Float) {
        if (target == hz) return
        target = hz
        if (ready) vote()
        syncFrames()
    }

    private fun syncFrames() {
        val want = when (mode) {
            "off" -> false
            "always" -> true
            "boot" -> booting || touching
            else -> touching
        }
        if (want && !handler.hasCallbacks(frameTick)) {
            handler.post(frameTick)
        } else if (!want) {
            handler.removeCallbacks(frameTick)
        }
    }

    private fun postFrame() {
        if (!holder.surface.isValid) return
        runCatching {
            holder.surface.lockCanvas(null)?.let {
                it.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                holder.surface.unlockCanvasAndPost(it)
            }
        }.onFailure { Log.w(TAG, "frame post", it) }
        vote()
    }

    private fun vote() {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            if (holder.surface.isValid) holder.surface.setFrameRate(
                target, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS)
        }.onFailure { Log.w(TAG, "surface vote $target Hz", it) }
        Log.i(TAG, "surface vote ${target}Hz mode=$mode ready=$ready")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        ready = true
        runCatching {
            holder.surface.lockCanvas(null)?.let {
                it.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                holder.surface.unlockCanvasAndPost(it)
            }
        }
        vote()
        if (mode == "boot") {
            booting = true
            syncFrames()
            handler.postDelayed({ booting = false; syncFrames() }, 4000L)
        } else {
            syncFrames()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        vote()
        syncFrames()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        ready = false
        handler.removeCallbacks(frameTick)
    }

    companion object {
        private const val TAG = "PeakRefresh"
        private const val MODE_SETTING = "droidhost_peak_vote_mode"

        /** Only nubia/RedMagic is known to ignore app-side mode votes. */
        fun needed(): Boolean =
            Build.BRAND.lowercase().contains("nubia") || Build.MANUFACTURER.lowercase().contains("nubia")

        private fun readMode(activity: Activity): String = try {
            Settings.Global.getString(activity.contentResolver, MODE_SETTING) ?: "on"
        } catch (_: Throwable) {
            "on"
        }
    }
}

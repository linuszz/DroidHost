package com.droidhost.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

class KeyboardHost(
    context: Context,
    private val sendKeyEvent: (KeyEvent) -> Boolean,
) : View(context) {
    constructor(activity: Activity) : this(activity, { activity.dispatchKeyEvent(it) })

    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val map: KeyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    var shown = false
        private set
    var imeShowing = false
        private set
    // A password input type forces the OEM "secure" IME and blocks switching; normal text lets any
    // installed keyboard show and be switched. Toggle re-creates the input connection to apply.
    var secureKeyboard = true
        private set
    var onImeChanged: ((Boolean) -> Unit)? = null
    var onImeHeightChanged: ((Int) -> Unit)? = null
    // Whole-string delivery for text the virtual keyboard cannot map to scancodes (CJK/emoji). The host
    // routes it through the guest clipboard + paste, since X11 clients never bind text-input-v3.
    var onCommitText: ((String) -> Unit)? = null
    // True while Ctrl/Alt/Super is held: typed letters are shortcut chords, so drop the IME auto-cap.
    var shortcutActive: () -> Boolean = { false }
    private var imeHeight = 0

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        visibility = VISIBLE
        layoutParams = android.widget.FrameLayout.LayoutParams(1, 1)
    }

    override fun onCheckIsTextEditor(): Boolean = shown

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!shown) return null
        outAttrs.inputType = if (secureKeyboard)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        Log.i("KBHost", "onCreateInputConnection secure=$secureKeyboard inputType=${outAttrs.inputType}")
        return KeyConnection()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val visible = insets.isVisible(WindowInsets.Type.ime())
            val height = if (visible) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
            if (height != imeHeight) {
                imeHeight = height
                onImeHeightChanged?.invoke(height)
            }
            if (visible != imeShowing) {
                imeShowing = visible
                onImeChanged?.invoke(visible)
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || keyCode != KeyEvent.KEYCODE_BACK || !shown) {
            return super.onKeyPreIme(keyCode, event)
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            hide()
            onImeChanged?.invoke(false)
        }
        return true
    }

    fun toggle() = if (shown && imeVisible()) hide() else show()

    fun setSecureKeyboard(secure: Boolean) {
        if (secureKeyboard == secure) return
        secureKeyboard = secure
        if (shown) imm.restartInput(this)
    }

    private fun imeVisible() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false

    fun show() {
        shown = true
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        imm.restartInput(this)
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        shown = false
        imm.hideSoftInputFromWindow(windowToken, 0)
        isFocusable = false
        isFocusableInTouchMode = false
        clearFocus()
    }

    fun type(text: CharSequence?) {
        if (text.isNullOrEmpty()) return
        Log.i("KBHost", "type text='$text' shortcut=${shortcutActive()}")
        // A held Ctrl/Alt/Super makes this a shortcut chord: lowercase so getEvents emits the bare
        // letter (Alt+T), not the auto-capitalized Shift+T (Alt+Shift+T) that misses the Mod+T bind.
        val typed = if (shortcutActive()) text.toString().lowercase() else text.toString()
        val events = map.getEvents(typed.toCharArray())
        if (events != null) {
            for (event in events) sendKeyEvent(event)
        } else {
            onCommitText?.let { it(text.toString()); return }
            for (ch in text) {
                val t = System.currentTimeMillis()
                val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, 0)
                sendKeyEvent(CharKeyEvent(down, ch))
                sendKeyEvent(CharKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP), ch))
            }
        }
    }

    fun tap(keyCode: Int) {
        val t = System.currentTimeMillis()
        sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private inner class KeyConnection : BaseInputConnection(this, false) {
        private var composing = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            Log.i("KBHost", "commitText '$text' shortcut=${shortcutActive()}")
            composing = ""
            type(text)
            return true
        }

        // A composing IME (e.g. Baidu pinyin) routes letter keys through setComposingText rather than
        // commitText. While Ctrl/Alt/Super is held the letters are a shortcut chord, so deliver them as
        // raw keys here; otherwise Alt+T never reaches the guest because the composition is never committed.
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val s = text?.toString() ?: ""
            Log.i("KBHost", "setComposingText '$s' shortcut=${shortcutActive()} prev='$composing'")
            if (shortcutActive() && s.isNotEmpty()) {
                val delta = if (s.startsWith(composing)) s.substring(composing.length) else s
                if (delta.isNotEmpty()) type(delta)
            }
            composing = s
            return true
        }
        override fun setComposingRegion(start: Int, end: Int): Boolean {
            Log.i("KBHost", "setComposingRegion $start..$end shortcut=${shortcutActive()}")
            return super.setComposingRegion(start, end)
        }
        override fun finishComposingText(): Boolean {
            Log.i("KBHost", "finishComposingText shortcut=${shortcutActive()}")
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { tap(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            Log.i("KBHost", "ic.sendKeyEvent kc=${event.keyCode} uni=${event.unicodeChar} meta=${event.metaState} action=${event.action}")
            return this@KeyboardHost.sendKeyEvent(event)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            tap(KeyEvent.KEYCODE_ENTER)
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
    }

    private class CharKeyEvent(origin: KeyEvent, private val ch: Char) : KeyEvent(origin) {
        override fun getUnicodeChar(): Int = ch.code
        override fun getUnicodeChar(metaState: Int): Int = ch.code
    }
}

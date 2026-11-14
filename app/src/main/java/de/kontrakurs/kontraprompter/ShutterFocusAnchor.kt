package de.kontrakurs.kontraprompter

import android.app.Activity
import android.content.Context
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager

/** Scoped to a paired prompter with a deliberately selected external remote. */
internal class ShutterFocusAnchor(private val activity: Activity, private val descriptor: String) : AutoCloseable {
    private val root = activity.window.decorView as ViewGroup
    private val accessibility = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val inputMethod = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var anchor: View? = null
    private var lastTrace: String? = null
    private var closed = false
    private var keyboardNavigation = false
    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
        if (enabled) removeAnchor() else ensureFocus()
    }
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { root.post { ensureFocus() } }

    init {
        accessibility.addAccessibilityStateChangeListener(accessibilityListener)
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    /** Never displace a currently focused view or run while accessibility is enabled. */
    fun ensureFocus() {
        if (BuildConfig.DEBUG) {
            val focused = root.findFocus()
            val focusKind = when { focused == null -> "none"; focused === anchor -> "anchor"; focused is ViewGroup -> "otherGroup"; else -> "otherView" }
            val mode = (focused as? ViewGroup)?.descendantFocusability
            val snapshot = "closed=$closed keyboard=$keyboardNavigation accessibility=${accessibility.isEnabled} imeActive=${inputMethod.isActive} imeAccepting=${inputMethod.isAcceptingText} window=${activity.hasWindowFocus()} selectedConnected=${selectedRemoteConnected()} focus=$focusKind groupMode=$mode focusableInTouch=${focused?.isFocusableInTouchMode}"
            if (snapshot != lastTrace) { lastTrace = snapshot; android.util.Log.d("KpFocusTrace", "check $snapshot") }
        }
        if (closed || keyboardNavigation || accessibility.isEnabled || inputMethod.isActive || inputMethod.isAcceptingText ||
            !activity.hasWindowFocus() || !selectedRemoteConnected()) {
            removeAnchor()
            return
        }
        val focused = root.findFocus()
        if (focused != null && focused !== anchor) { removeAnchor(); return }
        val target = anchor ?: View(activity).apply {
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha = 0f
            root.addView(this, ViewGroup.LayoutParams(1, 1))
            anchor = this
        }
        val success = target.requestFocus()
        if (BuildConfig.DEBUG && !success) android.util.Log.d("KpFocusTrace", "requestFocus failed")
        if (!success) removeAnchor()
    }

    /** A normal navigation key immediately yields focus to ordinary keyboard navigation. */
    fun yieldToKeyboard() { keyboardNavigation = true; removeAnchor() }

    /** A new touch explicitly returns to touch operation, without replacing an existing focus. */
    fun afterTouch() { keyboardNavigation = false; ensureFocus() }

    private fun selectedRemoteConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { it.isExternal && !it.isVirtual && it.descriptor == descriptor } == true
    }

    private fun removeAnchor() { anchor?.let { root.removeView(it) }; anchor = null }

    override fun close() {
        if (closed) return
        closed = true
        accessibility.removeAccessibilityStateChangeListener(accessibilityListener)
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        removeAnchor()
    }
}

package de.kontrakurs.kontraprompter

import android.app.Activity
import android.content.Context
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager

/** Scoped to a paired prompter with a deliberately selected external remote. */
internal class ShutterFocusAnchor(private val activity: Activity, private val descriptor: String) : AutoCloseable {
    private val root = activity.window.decorView as ViewGroup
    private val accessibility = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var anchor: View? = null
    private var closed = false
    private var keyboardNavigation = false
    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
        if (enabled) removeAnchor()
    }

    init { accessibility.addAccessibilityStateChangeListener(accessibilityListener) }

    /** Never displace a currently focused view or run while accessibility is enabled. */
    fun ensureFocus() {
        if (closed || keyboardNavigation || accessibility.isEnabled || !activity.hasWindowFocus() || !selectedRemoteConnected()) {
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
        if (!target.requestFocus()) removeAnchor()
    }

    /** A normal navigation key immediately yields focus to ordinary keyboard navigation. */
    fun yieldToKeyboard() { keyboardNavigation = true; removeAnchor() }

    private fun selectedRemoteConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { it.isExternal && !it.isVirtual && it.descriptor == descriptor } == true
    }

    private fun removeAnchor() { anchor?.let { root.removeView(it) }; anchor = null }

    override fun close() {
        if (closed) return
        closed = true
        accessibility.removeAccessibilityStateChangeListener(accessibilityListener)
        removeAnchor()
    }
}

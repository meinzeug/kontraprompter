package de.kontrakurs.kontraprompter

import android.app.Activity
import android.content.Context
import android.view.InputDevice
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager

/** Experimental: preserve the already focused Compose host without taking focus. */
internal class ShutterFocusAnchor(private val activity: Activity, private val descriptor: String) : AutoCloseable {
    private val root = activity.window.decorView as ViewGroup
    private val accessibility = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val inputMethod = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private var guarded: ViewGroup? = null
    private var originalPriority: Int? = null
    private var closed = false
    private var keyboardNavigation = false
    private val accessibilityListener = AccessibilityManager.AccessibilityStateChangeListener { enabled ->
        if (enabled) restore() else ensureFocus()
    }
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { root.post { ensureFocus() } }

    init {
        accessibility.addAccessibilityStateChangeListener(accessibilityListener)
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    /** Never request focus, replace a focused view, or modify arbitrary view groups. */
    fun ensureFocus() {
        if (closed || keyboardNavigation || accessibility.isEnabled || inputMethod.isActive || inputMethod.isAcceptingText ||
            !activity.hasWindowFocus() || !selectedRemoteConnected()) { restore(); return }
        val focused = root.findFocus() as? ViewGroup
        if (focused == null || focused.javaClass.name != "androidx.compose.ui.platform.AndroidComposeView" ||
            !focused.isFocused || !focused.isFocusableInTouchMode || !focused.isAttachedToWindow) { restore(); return }
        if (focused === guarded && focused.descendantFocusability == ViewGroup.FOCUS_BEFORE_DESCENDANTS) return
        restore()
        if (focused.descendantFocusability != ViewGroup.FOCUS_AFTER_DESCENDANTS) return
        originalPriority = focused.descendantFocusability
        guarded = focused
        focused.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    }

    fun yieldToKeyboard() { keyboardNavigation = true; restore() }
    fun afterTouch() { keyboardNavigation = false; ensureFocus() }

    private fun selectedRemoteConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.let { it.isExternal && !it.isVirtual && it.descriptor == descriptor } == true
    }

    private fun restore() {
        val current = guarded
        val original = originalPriority
        guarded = null; originalPriority = null
        if (current != null && original != null && current.descendantFocusability == ViewGroup.FOCUS_BEFORE_DESCENDANTS)
            current.descendantFocusability = original
    }

    override fun close() {
        if (closed) return
        closed = true
        accessibility.removeAccessibilityStateChangeListener(accessibilityListener)
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        restore()
    }
}

package com.nexicode.aeopin.ui

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.awt.EventQueue
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GlobalHotkeyManager(
    private val onToggle: () -> Unit
) : NativeKeyListener {
    private val registered = AtomicBoolean(false)
    private val hotkeyDown = AtomicBoolean(false)
    private val lastActivation = AtomicLong(0L)
    private val debounceMillis = 200L

    fun init() {
        if (!registered.compareAndSet(false, true)) return

        // Fix for "Access is denied" when installed in Program Files
        // Forces JNativeHook to extract its native library to a writeable temp folder
        System.setProperty("jnativehook.lib.path", System.getProperty("java.io.tmpdir"))

        // Disable JNativeHook logging
        val logger = Logger.getLogger(GlobalScreen::class.java.`package`.name)
        logger.level = Level.OFF
        logger.useParentHandlers = false

        try {
            GlobalScreen.registerNativeHook()
        } catch (e: Exception) {
            registered.set(false)
            e.printStackTrace()
            return
        }

        GlobalScreen.addNativeKeyListener(this)
    }

    override fun nativeKeyPressed(e: NativeKeyEvent) {
        // Default: Alt + Shift + V
        val isAlt = (e.modifiers and NativeKeyEvent.ALT_MASK) != 0
        val isShift = (e.modifiers and NativeKeyEvent.SHIFT_MASK) != 0
        
        if (isAlt && isShift && e.keyCode == NativeKeyEvent.VC_V) {
            if (!hotkeyDown.compareAndSet(false, true)) return

            val now = System.currentTimeMillis()
            if (now - lastActivation.getAndSet(now) < debounceMillis) return

            // JNativeHook invokes listeners off the Compose/AWT UI thread.
            EventQueue.invokeLater(onToggle)
        }
    }

    override fun nativeKeyReleased(e: NativeKeyEvent) {
        if (e.keyCode == NativeKeyEvent.VC_V) {
            hotkeyDown.set(false)
        }
    }

    fun stop() {
        if (registered.compareAndSet(true, false)) {
            GlobalScreen.removeNativeKeyListener(this)
            if (GlobalScreen.isNativeHookRegistered()) {
                GlobalScreen.unregisterNativeHook()
            }
        }
    }
}

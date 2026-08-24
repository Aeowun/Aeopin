package com.nexicode.aeopin.ui

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger

class GlobalHotkeyManager(
    private val onToggle: () -> Unit
) : NativeKeyListener {

    fun init() {
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
            onToggle()
        }
    }

    fun stop() {
        GlobalScreen.unregisterNativeHook()
    }
}

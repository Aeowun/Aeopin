/*
 * AEOPIN — Local Capture & Search
 * Copyright (C) 2026 Aeowun
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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

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

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

class SystemTrayManager(
    private val onShow: () -> Unit,
    private val onHide: () -> Unit,
    private val onExit: () -> Unit
) {
    private var trayIcon: TrayIcon? = null

    fun init() {
        if (!SystemTray.isSupported()) return

        val tray = SystemTray.getSystemTray()
        
        // Create a simple 16x16 turquoise square as icon
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color(0x1B, 0xC4, 0xC4)
        g.fillRect(0, 0, 16, 16)
        g.dispose()

        val popup = PopupMenu()
        
        val showItem = MenuItem("Show AEOPIN")
        showItem.addActionListener { onShow() }
        popup.add(showItem)

        val hideItem = MenuItem("Hide AEOPIN")
        hideItem.addActionListener { onHide() }
        popup.add(hideItem)

        popup.addSeparator()

        val exitItem = MenuItem("Exit")
        exitItem.addActionListener { onExit() }
        popup.add(exitItem)

        trayIcon = TrayIcon(image, "AEOPIN", popup).apply {
            isImageAutoSize = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        onShow()
                    }
                }
            })
        }

        try {
            tray.add(trayIcon)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

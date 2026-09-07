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

import com.nexicode.aeopin.data.AeopinItems
import com.nexicode.aeopin.data.storage.VaultManager
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.*
import java.io.File

class DesktopDragSource(
    private val item: AeopinItems,
    private val vaultManager: VaultManager,
    private val onDelete: () -> Unit
) : DragSourceListener, DragGestureListener {

    override fun dragGestureRecognized(dge: DragGestureEvent) {
        val file = item.contentHash?.let { vaultManager.getVaultPath(it).toFile() } ?: return
        
        val transferable = object : Transferable {
            override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
            override fun getTransferData(flavor: DataFlavor): Any {
                if (flavor != DataFlavor.javaFileListFlavor) throw UnsupportedFlavorException(flavor)
                return listOf(file)
            }
        }

        dge.startDrag(DragSource.DefaultMoveDrop, transferable, this)
    }

    override fun dragEnter(dsde: DragSourceDragEvent) {
        dsde.dragSourceContext.cursor = DragSource.DefaultMoveDrop
    }
    override fun dragOver(dsde: DragSourceDragEvent) {}
    override fun dropActionChanged(dsde: DragSourceDragEvent) {}
    override fun dragExit(dsde: DragSourceEvent) {}
    
    override fun dragDropEnd(dsde: DragSourceDropEvent) {
        if (dsde.dropSuccess && dsde.dropAction == DnDConstants.ACTION_MOVE) {
            onDelete()
        }
    }
}

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

import com.nexicode.aeopin.domain.AeopinInput
import com.nexicode.aeopin.domain.VaultService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File
import java.net.URI

/**
 * A native AWT DropTarget adapter for Windows/Desktop ingestion.
 * Bypasses Compose's experimental and portable drag-and-drop abstractions
 * in favor of direct OS interaction via the JVM.
 */
class DesktopDropAdapter(
    private val vaultService: VaultService,
    private val scope: CoroutineScope,
    private val onDragStateChange: (Boolean) -> Unit,
    private val onStorageStarted: () -> Unit,
    private val onStorageSuccess: (String) -> Unit,
    private val onStorageError: (String) -> Unit
) : DropTargetListener {

    override fun dragEnter(dtde: DropTargetDragEvent) {
        if (InternalDragTracker.isInternalDrag.get()) return
        onDragStateChange(true)
        dtde.acceptDrag(DnDConstants.ACTION_COPY)
    }

    override fun dragOver(dtde: DropTargetDragEvent) {
        if (InternalDragTracker.isInternalDrag.get()) {
            dtde.rejectDrag()
            return
        }
        dtde.acceptDrag(DnDConstants.ACTION_COPY)
    }

    override fun dropActionChanged(dtde: DropTargetDragEvent) {}

    override fun dragExit(dte: DropTargetEvent) {
        onDragStateChange(false)
    }

    override fun drop(dtde: DropTargetDropEvent) {
        onDragStateChange(false)
        onStorageStarted()
        try {
            dtde.acceptDrop(DnDConstants.ACTION_COPY)
            val transferable = dtde.transferable
            
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<*>
                scope.launch {
                    val fileList = files.filterIsInstance<File>()
                    for (file in fileList) {
                        if (file.isDirectory) {
                            if (isFolderTooLarge(file)) {
                                onStorageError("Folder too large (>500MB/5k files)")
                                continue
                            }
                            vaultService.store(AeopinInput.FolderInput(file))
                        } else {
                            vaultService.store(AeopinInput.FileInput(file))
                        }
                    }
                    val label = if (fileList.size == 1) fileList[0].name else "${fileList.size} drops"
                    onStorageSuccess(label)
                }
                dtde.dropComplete(true)
            } else if (transferable.isDataFlavorSupported(DataFlavor.selectionHtmlFlavor)) {
                val html = transferable.getTransferData(DataFlavor.selectionHtmlFlavor) as String
                val link = extractLinkFromHtml(html)
                if (link != null) {
                    scope.launch {
                        vaultService.store(
                            AeopinInput.UrlInput(
                                url = link.first,
                                title = link.second
                            )
                        )
                        onStorageSuccess("Link")
                    }
                    dtde.dropComplete(true)
                } else {
                    onStorageError("Dropped HTML did not contain a link")
                    dtde.dropComplete(false)
                }
            } else if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val text = (transferable.getTransferData(DataFlavor.stringFlavor) as String).trim()
                val uri = runCatching { URI(text) }.getOrNull()
                scope.launch {
                    if (uri?.scheme.equals("http", ignoreCase = true) ||
                        uri?.scheme.equals("https", ignoreCase = true)
                    ) {
                        vaultService.store(AeopinInput.UrlInput(text))
                        onStorageSuccess("Link")
                    } else {
                        vaultService.store(AeopinInput.TextInput(text))
                        onStorageSuccess("Text")
                    }
                }
                dtde.dropComplete(true)
            } else {
                onMessage("Unsupported drop content")
                dtde.dropComplete(false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onStorageError(e.message ?: "Unknown error")
            dtde.dropComplete(false)
        }
    }

    private fun onMessage(msg: String) {
        // Simple fallback
        println(msg)
    }

    private fun extractLinkFromHtml(html: String): Pair<String, String?>? {
        val match = Regex(
            """<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: return null
        val url = match.groupValues[1].trim()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.equals("http", ignoreCase = true) != true &&
            uri.scheme?.equals("https", ignoreCase = true) != true
        ) return null
        val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim().ifBlank { null }
        return url to title
    }

    private fun isFolderTooLarge(folder: File): Boolean {
        var size = 0L
        var count = 0
        folder.walkTopDown().forEach {
            if (it.isFile) {
                size += it.length()
                count++
            }
            if (size > 500 * 1024 * 1024 || count > 5000) return true
        }
        return false
    }
}

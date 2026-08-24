package com.nexicode.aeopin.ui

import com.nexicode.aeopin.domain.AeopinInput
import com.nexicode.aeopin.domain.VaultService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*
import java.io.File

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
            } else if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                val text = transferable.getTransferData(DataFlavor.stringFlavor) as String
                scope.launch {
                    if (text.startsWith("http")) {
                        vaultService.store(AeopinInput.UrlInput(text))
                        onStorageSuccess("URL")
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

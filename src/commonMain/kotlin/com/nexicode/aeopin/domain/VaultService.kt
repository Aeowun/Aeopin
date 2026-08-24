package com.nexicode.aeopin.domain

import com.nexicode.aeopin.data.Database
import com.nexicode.aeopin.data.storage.VaultManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

sealed class AeopinInput {
    data class FileInput(val file: File) : AeopinInput()
    data class FolderInput(val folder: File) : AeopinInput()
    data class TextInput(val text: String) : AeopinInput()
    data class UrlInput(val url: String) : AeopinInput()
}

class VaultService(
    private val database: Database,
    private val vaultManager: VaultManager,
    private val scope: CoroutineScope
) {
    private val queries = database.databaseQueries
    private val processingMutex = Mutex()

    suspend fun store(input: AeopinInput): Boolean = withContext(Dispatchers.IO) {
        try {
            when (input) {
                is AeopinInput.FileInput -> {
                    queries.insertJournal(
                        type = "FILE",
                        stagedPath = null,
                        sourcePath = input.file.absolutePath,
                        expectedSize = input.file.length(),
                        state = "PREPARING",
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AeopinInput.FolderInput -> {
                    queries.insertJournal(
                        type = "FOLDER",
                        stagedPath = null,
                        sourcePath = input.folder.absolutePath,
                        expectedSize = 0L,
                        state = "PREPARING",
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AeopinInput.TextInput -> {
                    queries.insertJournal(
                        type = "TEXT",
                        stagedPath = input.text,
                        sourcePath = null,
                        expectedSize = input.text.length.toLong(),
                        state = "COMPLETE",
                        timestamp = System.currentTimeMillis()
                    )
                }
                is AeopinInput.UrlInput -> {
                    queries.insertJournal(
                        type = "URL",
                        stagedPath = input.url,
                        sourcePath = null,
                        expectedSize = input.url.length.toLong(),
                        state = "COMPLETE",
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
            scope.launch { processPending() }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun processPending() = processingMutex.withLock {
        withContext(Dispatchers.IO) {
            var changed: Boolean
            do {
                changed = false
                val pending = queries.selectAllPending().executeAsList()
                for (journal in pending) {
                    val wasState = journal.state
                    try {
                        when (journal.type) {
                            "FILE" -> processFileJournal(journal)
                            "FOLDER" -> processFolderJournal(journal)
                            "TEXT" -> processTextJournal(journal)
                            "URL" -> processUrlJournal(journal)
                        }
                    } catch (e: Exception) {
                        println("[AUDIT] STORAGE_FAILURE: ID=${journal.id} State=${journal.state} Error=${e.message}")
                    }
                    // Re-check if state changed to decide if we should loop
                    val updated = queries.selectAllPending().executeAsList().find { it.id == journal.id }
                    if (updated == null || updated.state != wasState) {
                        changed = true
                    }
                }
            } while (changed)
        }
    }

    suspend fun startScavenger() = withContext(Dispatchers.IO) {
        processPending()
    }

    private fun processFileJournal(journal: com.nexicode.aeopin.data.PendingIngestion) {
        val source = File(journal.sourcePath ?: return)
        
        when (journal.state) {
            "PREPARING" -> {
                if (!source.exists()) {
                    queries.deleteJournal(journal.id)
                    return
                }
                val staged = vaultManager.stageFileSafe(source)
                queries.updateJournalStagedInfo(staged.absolutePathString(), null, "STAGED", journal.id)
            }
            "STAGED" -> {
                val staged = File(journal.stagedPath!!)
                if (!staged.exists()) {
                    queries.updateJournalState("PREPARING", journal.id)
                    return
                }
                val hash = vaultManager.calculateHash(staged.toPath())
                queries.updateJournalStagedInfo(journal.stagedPath, hash, "VERIFIED", journal.id)
            }
            "VERIFIED" -> {
                val staged = File(journal.stagedPath!!)
                vaultManager.commitToVault(staged.toPath(), journal.expectedHash!!)
                queries.updateJournalState("VAULT_COMMITTED", journal.id)
            }
            "VAULT_COMMITTED" -> {
                val isDuplicate = queries.selectAllItems().executeAsList().any { 
                    it.originalPath == journal.sourcePath && it.contentHash == journal.expectedHash 
                }
                if (!isDuplicate) {
                    queries.insertItem(
                        type = "FILE",
                        originalName = File(journal.sourcePath!!).name,
                        originalPath = journal.sourcePath,
                        contentHash = journal.expectedHash,
                        metadataJson = "{}",
                        timestamp = journal.timestamp,
                        isPinned = false
                    )
                }
                queries.updateJournalState("DELETE_PENDING", journal.id)
            }
            "DELETE_PENDING" -> {
                if (source.exists()) {
                    Files.deleteIfExists(source.toPath())
                }
                queries.deleteJournal(journal.id)
            }
        }
    }

    private fun processFolderJournal(journal: com.nexicode.aeopin.data.PendingIngestion) {
        val source = File(journal.sourcePath ?: return)
        
        when (journal.state) {
            "PREPARING" -> {
                if (!source.exists()) {
                    queries.deleteJournal(journal.id)
                    return
                }
                val tempZip = File(System.getProperty("java.io.tmpdir"), "aeopin_${UUID.randomUUID()}.zip")
                zipFolder(source, tempZip)
                val staged = vaultManager.stageFileSafe(tempZip)
                tempZip.delete()
                queries.updateJournalStagedInfo(staged.absolutePathString(), null, "STAGED", journal.id)
            }
            "STAGED" -> {
                val staged = File(journal.stagedPath!!)
                val hash = vaultManager.calculateHash(staged.toPath())
                queries.updateJournalStagedInfo(journal.stagedPath, hash, "VAULT_COMMITTED", journal.id)
            }
            "VAULT_COMMITTED" -> {
                val staged = File(journal.stagedPath!!)
                vaultManager.commitToVault(staged.toPath(), journal.expectedHash!!)
                queries.insertItem(
                    type = "FOLDER",
                    originalName = source.name,
                    originalPath = journal.sourcePath,
                    contentHash = journal.expectedHash,
                    metadataJson = "{\"isZip\": true}",
                    timestamp = journal.timestamp,
                    isPinned = false
                )
                queries.updateJournalState("DELETE_PENDING", journal.id)
            }
            "DELETE_PENDING" -> {
                if (source.exists()) source.deleteRecursively()
                queries.deleteJournal(journal.id)
            }
        }
    }

    private fun processTextJournal(journal: com.nexicode.aeopin.data.PendingIngestion) {
        if (journal.state == "COMPLETE") {
            queries.insertItem("TEXT", "Snippet", null, null, journal.stagedPath ?: "", journal.timestamp, false)
            queries.deleteJournal(journal.id)
        }
    }

    private fun processUrlJournal(journal: com.nexicode.aeopin.data.PendingIngestion) {
        if (journal.state == "COMPLETE") {
            queries.insertItem("URL", journal.stagedPath ?: "URL", null, null, "{\"url\": \"${journal.stagedPath}\"}", journal.timestamp, false)
            queries.deleteJournal(journal.id)
        }
    }

    private fun zipFolder(sourceDir: File, targetZip: File) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(targetZip)).use { zos ->
            sourceDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val entryName = sourceDir.toPath().relativize(file.toPath()).toString()
                    val entry = java.util.zip.ZipEntry(entryName)
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
}

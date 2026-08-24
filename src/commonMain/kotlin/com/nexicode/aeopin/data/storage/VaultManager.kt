package com.nexicode.aeopin.data.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.absolutePathString
import kotlin.io.path.fileSize

class VaultManager(private val rootPath: String = System.getProperty("user.home") + "/Documents/AEOPIN") {

    private val vaultDir = Paths.get(rootPath, "vault")
    private val stagingDir = Paths.get(rootPath, "staging")
    private val exportDir = Paths.get(rootPath, "temp")

    init {
        Files.createDirectories(vaultDir)
        Files.createDirectories(stagingDir)
        Files.createDirectories(exportDir)
        clearTemp()
    }

    /**
     * Prepares a file or folder for Drag-Out by creating a temporary link or unzipping.
     */
    fun prepareForExport(item: com.nexicode.aeopin.data.AeopinItems): File {
        val hash = item.contentHash!!
        val originalName = item.originalName ?: "item"
        val source = getVaultPath(hash)
        
        val sessionDir = exportDir.resolve(UUID.randomUUID().toString())
        Files.createDirectories(sessionDir)
        
        val target = sessionDir.resolve(originalName)
        
        println("[AUDIT] EXPORT_PREP: Source=${source.absolutePathString()} Target=${target.absolutePathString()}")

        if (item.type == "FOLDER") {
            Files.createDirectories(target)
            unzip(source.toFile(), target.toFile())
        } else {
            try {
                Files.createLink(target, source)
            } catch (e: Exception) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return target.toFile()
    }

    private fun unzip(zipFile: File, destDir: File) {
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = destDir.resolve(entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile.mkdirs()
                    newFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun clearTemp() {
        if (Files.exists(exportDir)) {
            exportDir.toFile().deleteRecursively()
            Files.createDirectories(exportDir)
        }
    }

    /**
     * Stage 1: Safe Copy to staging area.
     * Original source is NOT touched yet.
     */
    fun stageFileSafe(sourceFile: File): Path {
        val sourcePath = sourceFile.toPath().toAbsolutePath().normalize()
        if (!Files.exists(sourcePath)) {
            throw IllegalArgumentException("Source missing: $sourcePath")
        }

        val stagedName = "${UUID.randomUUID()}_${sourcePath.fileName}"
        val target = stagingDir.resolve(stagedName).normalize()
        
        println("[AUDIT] STAGING_SAFE: Copying $sourcePath to $target")
        
        // Using streams to allow explicit flushing
        sourceFile.inputStream().use { input ->
            FileOutputStream(target.toFile()).use { output ->
                input.copyTo(output)
                output.fd.sync() // Force flush to disk
            }
        }
        
        return target
    }

    /**
     * Final commit of verified staging object to CAS vault.
     */
    fun commitToVault(stagedPath: Path, verifiedHash: String): Path {
        val vaultPath = getVaultPath(verifiedHash)
        
        if (!vaultPath.exists()) {
            println("[AUDIT] VAULT_COMMIT: Moving verified $stagedPath to $vaultPath")
            Files.move(stagedPath, vaultPath, StandardCopyOption.ATOMIC_MOVE)
        } else {
            println("[AUDIT] VAULT_COMMIT: Deduplicated. Deleting verified staging object $stagedPath")
            Files.delete(stagedPath)
        }
        
        return vaultPath
    }

    fun getVaultPath(hash: String): Path {
        val shard = hash.take(2)
        val shardDir = vaultDir.resolve(shard)
        Files.createDirectories(shardDir)
        return shardDir.resolve(hash)
    }

    fun getStagedFiles(): List<Path> {
        return if (Files.exists(stagingDir)) {
            Files.list(stagingDir).use { it.toList() }
        } else emptyList()
    }

    fun calculateHash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

package com.nexicode.aeopin.domain

import com.nexicode.aeopin.data.Database
import com.nexicode.aeopin.data.storage.VaultManager
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VaultServiceTest {

    private lateinit var database: Database
    private lateinit var vaultManager: VaultManager
    private lateinit var service: VaultService
    private lateinit var tempDir: File

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("aeopin_test").toFile()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        vaultManager = VaultManager(tempDir.absolutePath)
        service = VaultService(database, vaultManager, CoroutineScope(Dispatchers.Unconfined))
    }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testTextCapture() = runTest {
        service.store(AeopinInput.TextInput("Hello AEOPIN"))
        service.processPending()
        
        val items = database.databaseQueries.selectAllItems().executeAsList()
        assertEquals(1, items.size)
        assertEquals("Hello AEOPIN", items[0].metadataJson)
    }

    @Test
    fun testFailSafeFileCapture() = runTest {
        val sourceFile = tempDir.resolve("source.txt")
        sourceFile.writeText("Critical Data")
        val originalSize = sourceFile.length()

        // Capture (Staging only)
        service.store(AeopinInput.FileInput(sourceFile))
        
        // Finalize (Process)
        service.processPending()
        
        val items = database.databaseQueries.selectAllItems().executeAsList()
        assertEquals(1, items.size)
        assertEquals("source.txt", items[0].originalName)
        
        // Invariant check: Original deleted only after successful vault commit
        assertFalse(sourceFile.exists(), "Original file must be deleted after successful capture protocol")
    }

    @Test
    fun testDuplicateIdentityPolicy() = runTest {
        val file1 = tempDir.resolve("doc1.txt")
        file1.writeText("Common Content")
        
        service.store(AeopinInput.FileInput(file1))
        service.processPending()

        // Same content, different name
        val file2 = tempDir.resolve("doc2.txt")
        file2.writeText("Common Content")
        
        service.store(AeopinInput.FileInput(file2))
        service.processPending()
        
        val items = database.databaseQueries.selectAllItems().executeAsList()
        assertEquals(2, items.size, "Identity preservation: same content, different file should both exist")
        assertEquals(items[0].contentHash, items[1].contentHash, "Storage optimization: should share same CAS hash")
    }

    @Test
    fun testRecoveryAfterVaultCommit() = runTest {
        val sourceFile = tempDir.resolve("recovered.txt")
        sourceFile.writeText("Durable Data")
        
        val hash = "fakehash"
        database.databaseQueries.insertJournal(
            type = "FILE",
            stagedPath = tempDir.resolve("staged.txt").absolutePath,
            sourcePath = sourceFile.absolutePath,
            expectedSize = sourceFile.length(),
            state = "VAULT_COMMITTED",
            timestamp = System.currentTimeMillis()
        )
        
        // Set expected hash in journal for reconciliation
        database.databaseQueries.updateJournalStagedInfo(
            stagedPath = tempDir.resolve("staged.txt").absolutePath,
            expectedHash = hash,
            state = "VAULT_COMMITTED",
            id = 1L
        )

        // Mock vault file existence
        val vaultPath = vaultManager.getVaultPath(hash)
        Files.write(vaultPath, "Durable Data".toByteArray())

        service.startScavenger()
        
        assertFalse(sourceFile.exists(), "Source should be cleaned up during recovery from VAULT_COMMITTED")
        assertEquals(1, database.databaseQueries.selectAllItems().executeAsList().size)
    }
}

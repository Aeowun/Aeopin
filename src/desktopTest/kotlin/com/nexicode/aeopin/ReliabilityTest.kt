package com.nexicode.aeopin

import com.nexicode.aeopin.data.Database
import com.nexicode.aeopin.data.storage.VaultManager
import com.nexicode.aeopin.domain.AeopinInput
import com.nexicode.aeopin.domain.VaultService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import java.util.Properties
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

class ReliabilityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `test user data transaction integrity - interrupted move`() = runBlocking {
        val vaultDir = tempFolder.newFolder("AEOPIN")
        val sourceFile = tempFolder.newFile("user_data.txt")
        sourceFile.writeText("CRITICAL_USER_DATA")
        
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)
        val queries = database.databaseQueries
        
        val vaultManager = VaultManager(vaultDir.absolutePath)
        val vaultService = VaultService(database, vaultManager, CoroutineScope(Dispatchers.Default))
        
        // 1. Start ingestion
        vaultService.store(AeopinInput.FileInput(sourceFile))
        
        // 2. Verify Journal entry exists in PREPARING
        val pending = queries.selectAllPending().executeAsList()
        assertEquals(1, pending.size)
        assertEquals("PREPARING", pending[0].state)
        
        // 3. Process to STAGED
        vaultService.processPending()
        
        val staged = queries.selectAllPending().executeAsList()
        assertEquals(1, staged.size)
        assertEquals("STAGED", staged[0].state)
        assertTrue(File(staged[0].stagedPath!!).exists())
        
        // 4. Simulate crash by stopping here and creating a NEW service (Scavenger mode)
        val newVaultService = VaultService(database, vaultManager, CoroutineScope(Dispatchers.Default))
        newVaultService.startScavenger()
        
        // 5. Verify it finished the move and deleted source
        assertEquals(0, queries.selectAllPending().executeAsList().size)
        assertEquals(1, queries.selectAllItems().executeAsList().size)
        assertTrue(!sourceFile.exists(), "Source should be deleted after successful vaulting")
        
        val item = queries.selectAllItems().executeAsList()[0]
        val vaultedFile = vaultManager.getVaultPath(item.contentHash!!).toFile()
        assertTrue(vaultedFile.exists())
        assertEquals("CRITICAL_USER_DATA", vaultedFile.readText())
    }
}

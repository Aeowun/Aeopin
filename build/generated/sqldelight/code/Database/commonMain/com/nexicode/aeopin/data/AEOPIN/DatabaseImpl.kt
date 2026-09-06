package com.nexicode.aeopin.`data`.AEOPIN

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.nexicode.aeopin.`data`.Database
import com.nexicode.aeopin.`data`.DatabaseQueries
import kotlin.Long
import kotlin.Unit
import kotlin.reflect.KClass

internal val KClass<Database>.schema: SqlSchema<QueryResult.Value<Unit>>
  get() = DatabaseImpl.Schema

internal fun KClass<Database>.newInstance(driver: SqlDriver): Database = DatabaseImpl(driver)

private class DatabaseImpl(
  driver: SqlDriver,
) : TransacterImpl(driver), Database {
  override val databaseQueries: DatabaseQueries = DatabaseQueries(driver)

  public object Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long
      get() = 2

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(null, """
          |CREATE TABLE PendingIngestion (
          |    id INTEGER PRIMARY KEY AUTOINCREMENT,
          |    type TEXT NOT NULL, -- 'FILE', 'FOLDER', 'TEXT', 'URL'
          |    stagedPath TEXT,    -- Path in AEOPIN staging
          |    sourcePath TEXT,    -- Path of the original user file
          |    expectedHash TEXT,
          |    expectedSize INTEGER,
          |    state TEXT NOT NULL, -- 'PREPARING', 'STAGED', 'VERIFIED', 'VAULT_COMMITTED', 'DELETE_PENDING'
          |    timestamp INTEGER NOT NULL
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE AeopinItems (
          |    id INTEGER PRIMARY KEY AUTOINCREMENT,
          |    type TEXT NOT NULL,
          |    originalName TEXT,
          |    originalPath TEXT,
          |    contentHash TEXT,
          |    metadataJson TEXT,
          |    timestamp INTEGER NOT NULL,
          |    isPinned INTEGER NOT NULL DEFAULT 0
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TRIGGER aeopin_items_insert AFTER INSERT ON AeopinItems BEGIN
          |  INSERT INTO AeopinItemsFts(rowid, originalName, metadataJson)
          |  VALUES (new.id, new.originalName, new.metadataJson);
          |END
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TRIGGER aeopin_items_delete AFTER DELETE ON AeopinItems BEGIN
          |  INSERT INTO AeopinItemsFts(AeopinItemsFts, rowid, originalName, metadataJson)
          |  VALUES ('delete', old.id, old.originalName, old.metadataJson);
          |END
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TRIGGER aeopin_items_update AFTER UPDATE ON AeopinItems BEGIN
          |  INSERT INTO AeopinItemsFts(AeopinItemsFts, rowid, originalName, metadataJson)
          |  VALUES ('delete', old.id, old.originalName, old.metadataJson);
          |  INSERT INTO AeopinItemsFts(rowid, originalName, metadataJson)
          |  VALUES (new.id, new.originalName, new.metadataJson);
          |END
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE VIRTUAL TABLE AeopinItemsFts USING fts5(
          |    originalName,
          |    metadataJson,
          |    content='AeopinItems',
          |    content_rowid='id'
          |)
          """.trimMargin(), 0)
      return QueryResult.Unit
    }

    private fun migrateInternal(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
    ): QueryResult.Value<Unit> {
      if (oldVersion <= 1 && newVersion > 1) {
        driver.execute(null,
            "ALTER TABLE AeopinItems ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0", 0)
      }
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
      var lastVersion = oldVersion

      callbacks.filter { it.afterVersion in oldVersion until newVersion }
      .sortedBy { it.afterVersion }
      .forEach { callback ->
        migrateInternal(driver, oldVersion = lastVersion, newVersion = callback.afterVersion + 1)
        callback.block(driver)
        lastVersion = callback.afterVersion + 1
      }

      if (lastVersion < newVersion) {
        migrateInternal(driver, lastVersion, newVersion)
      }
      return QueryResult.Unit
    }
  }
}

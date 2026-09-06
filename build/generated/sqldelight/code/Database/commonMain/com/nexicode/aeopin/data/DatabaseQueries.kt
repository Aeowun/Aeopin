package com.nexicode.aeopin.`data`

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Boolean
import kotlin.Long
import kotlin.String

public class DatabaseQueries(
  driver: SqlDriver,
) : TransacterImpl(driver) {
  public fun <T : Any> selectAllPending(mapper: (
    id: Long,
    type: String,
    stagedPath: String?,
    sourcePath: String?,
    expectedHash: String?,
    expectedSize: Long?,
    state: String,
    timestamp: Long,
  ) -> T): Query<T> = Query(738_139_955, arrayOf("PendingIngestion"), driver, "Database.sq",
      "selectAllPending",
      "SELECT PendingIngestion.id, PendingIngestion.type, PendingIngestion.stagedPath, PendingIngestion.sourcePath, PendingIngestion.expectedHash, PendingIngestion.expectedSize, PendingIngestion.state, PendingIngestion.timestamp FROM PendingIngestion") {
      cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getString(4),
      cursor.getLong(5),
      cursor.getString(6)!!,
      cursor.getLong(7)!!
    )
  }

  public fun selectAllPending(): Query<PendingIngestion> = selectAllPending { id, type, stagedPath,
      sourcePath, expectedHash, expectedSize, state, timestamp ->
    PendingIngestion(
      id,
      type,
      stagedPath,
      sourcePath,
      expectedHash,
      expectedSize,
      state,
      timestamp
    )
  }

  public fun <T : Any> search(query: String, mapper: (
    id: Long,
    type: String,
    originalName: String?,
    originalPath: String?,
    contentHash: String?,
    metadataJson: String?,
    timestamp: Long,
    isPinned: Boolean,
  ) -> T): Query<T> = SearchQuery(query) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5),
      cursor.getLong(6)!!,
      cursor.getBoolean(7)!!
    )
  }

  public fun search(query: String): Query<AeopinItems> = search(query) { id, type, originalName,
      originalPath, contentHash, metadataJson, timestamp, isPinned ->
    AeopinItems(
      id,
      type,
      originalName,
      originalPath,
      contentHash,
      metadataJson,
      timestamp,
      isPinned
    )
  }

  public fun <T : Any> selectAllItems(mapper: (
    id: Long,
    type: String,
    originalName: String?,
    originalPath: String?,
    contentHash: String?,
    metadataJson: String?,
    timestamp: Long,
    isPinned: Boolean,
  ) -> T): Query<T> = Query(-246_598_564, arrayOf("AeopinItems"), driver, "Database.sq",
      "selectAllItems",
      "SELECT AeopinItems.id, AeopinItems.type, AeopinItems.originalName, AeopinItems.originalPath, AeopinItems.contentHash, AeopinItems.metadataJson, AeopinItems.timestamp, AeopinItems.isPinned FROM AeopinItems ORDER BY isPinned DESC, timestamp DESC") {
      cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5),
      cursor.getLong(6)!!,
      cursor.getBoolean(7)!!
    )
  }

  public fun selectAllItems(): Query<AeopinItems> = selectAllItems { id, type, originalName,
      originalPath, contentHash, metadataJson, timestamp, isPinned ->
    AeopinItems(
      id,
      type,
      originalName,
      originalPath,
      contentHash,
      metadataJson,
      timestamp,
      isPinned
    )
  }

  public fun <T : Any> filterByType(type: String, mapper: (
    id: Long,
    type: String,
    originalName: String?,
    originalPath: String?,
    contentHash: String?,
    metadataJson: String?,
    timestamp: Long,
    isPinned: Boolean,
  ) -> T): Query<T> = FilterByTypeQuery(type) { cursor ->
    mapper(
      cursor.getLong(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2),
      cursor.getString(3),
      cursor.getString(4),
      cursor.getString(5),
      cursor.getLong(6)!!,
      cursor.getBoolean(7)!!
    )
  }

  public fun filterByType(type: String): Query<AeopinItems> = filterByType(type) { id, type_,
      originalName, originalPath, contentHash, metadataJson, timestamp, isPinned ->
    AeopinItems(
      id,
      type_,
      originalName,
      originalPath,
      contentHash,
      metadataJson,
      timestamp,
      isPinned
    )
  }

  public fun insertJournal(
    type: String,
    stagedPath: String?,
    sourcePath: String?,
    expectedSize: Long?,
    state: String,
    timestamp: Long,
  ) {
    driver.execute(1_575_230_845, """
        |INSERT INTO PendingIngestion (type, stagedPath, sourcePath, expectedSize, state, timestamp)
        |VALUES (?, ?, ?, ?, ?, ?)
        """.trimMargin(), 6) {
          bindString(0, type)
          bindString(1, stagedPath)
          bindString(2, sourcePath)
          bindLong(3, expectedSize)
          bindString(4, state)
          bindLong(5, timestamp)
        }
    notifyQueries(1_575_230_845) { emit ->
      emit("PendingIngestion")
    }
  }

  public fun updateJournalState(state: String, id: Long) {
    driver.execute(175_799_076, """UPDATE PendingIngestion SET state = ? WHERE id = ?""", 2) {
          bindString(0, state)
          bindLong(1, id)
        }
    notifyQueries(175_799_076) { emit ->
      emit("PendingIngestion")
    }
  }

  public fun updateJournalStagedInfo(
    stagedPath: String?,
    expectedHash: String?,
    state: String,
    id: Long,
  ) {
    driver.execute(-381_951_935,
        """UPDATE PendingIngestion SET stagedPath = ?, expectedHash = ?, state = ? WHERE id = ?""",
        4) {
          bindString(0, stagedPath)
          bindString(1, expectedHash)
          bindString(2, state)
          bindLong(3, id)
        }
    notifyQueries(-381_951_935) { emit ->
      emit("PendingIngestion")
    }
  }

  public fun deleteJournal(id: Long) {
    driver.execute(1_881_966_155, """DELETE FROM PendingIngestion WHERE id = ?""", 1) {
          bindLong(0, id)
        }
    notifyQueries(1_881_966_155) { emit ->
      emit("PendingIngestion")
    }
  }

  public fun insertItem(
    type: String,
    originalName: String?,
    originalPath: String?,
    contentHash: String?,
    metadataJson: String?,
    timestamp: Long,
    isPinned: Boolean,
  ) {
    driver.execute(1_720_984_205, """
        |INSERT INTO AeopinItems (type, originalName, originalPath, contentHash, metadataJson, timestamp, isPinned)
        |VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimMargin(), 7) {
          bindString(0, type)
          bindString(1, originalName)
          bindString(2, originalPath)
          bindString(3, contentHash)
          bindString(4, metadataJson)
          bindLong(5, timestamp)
          bindBoolean(6, isPinned)
        }
    notifyQueries(1_720_984_205) { emit ->
      emit("AeopinItems")
      emit("AeopinItemsFts")
    }
  }

  public fun deleteItem(id: Long) {
    driver.execute(-1_775_559_553, """DELETE FROM AeopinItems WHERE id = ?""", 1) {
          bindLong(0, id)
        }
    notifyQueries(-1_775_559_553) { emit ->
      emit("AeopinItems")
      emit("AeopinItemsFts")
    }
  }

  public fun togglePinned(isPinned: Boolean, id: Long) {
    driver.execute(860_527_917, """UPDATE AeopinItems SET isPinned = ? WHERE id = ?""", 2) {
          bindBoolean(0, isPinned)
          bindLong(1, id)
        }
    notifyQueries(860_527_917) { emit ->
      emit("AeopinItems")
      emit("AeopinItemsFts")
    }
  }

  private inner class SearchQuery<out T : Any>(
    public val query: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("AeopinItems", "AeopinItemsFts", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("AeopinItems", "AeopinItemsFts", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
        driver.executeQuery(369_005_385, """
    |SELECT AeopinItems.id, AeopinItems.type, AeopinItems.originalName, AeopinItems.originalPath, AeopinItems.contentHash, AeopinItems.metadataJson, AeopinItems.timestamp, AeopinItems.isPinned
    |FROM AeopinItems
    |JOIN AeopinItemsFts ON AeopinItems.id = AeopinItemsFts.rowid
    |WHERE AeopinItemsFts MATCH ?
    |ORDER BY isPinned DESC, AeopinItems.timestamp DESC
    """.trimMargin(), mapper, 1) {
      bindString(0, query)
    }

    override fun toString(): String = "Database.sq:search"
  }

  private inner class FilterByTypeQuery<out T : Any>(
    public val type: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("AeopinItems", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("AeopinItems", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
        driver.executeQuery(1_486_551_722,
        """SELECT AeopinItems.id, AeopinItems.type, AeopinItems.originalName, AeopinItems.originalPath, AeopinItems.contentHash, AeopinItems.metadataJson, AeopinItems.timestamp, AeopinItems.isPinned FROM AeopinItems WHERE type = ? ORDER BY isPinned DESC, timestamp DESC""",
        mapper, 1) {
      bindString(0, type)
    }

    override fun toString(): String = "Database.sq:filterByType"
  }
}

package com.nexicode.aeopin.`data`

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.nexicode.aeopin.`data`.AEOPIN.newInstance
import com.nexicode.aeopin.`data`.AEOPIN.schema
import kotlin.Unit

public interface Database : Transacter {
  public val databaseQueries: DatabaseQueries

  public companion object {
    public val Schema: SqlSchema<QueryResult.Value<Unit>>
      get() = Database::class.schema

    public operator fun invoke(driver: SqlDriver): Database = Database::class.newInstance(driver)
  }
}

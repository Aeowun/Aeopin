package com.nexicode.aeopin.`data`

import kotlin.Long
import kotlin.String

public data class PendingIngestion(
  public val id: Long,
  public val type: String,
  public val stagedPath: String?,
  public val sourcePath: String?,
  public val expectedHash: String?,
  public val expectedSize: Long?,
  public val state: String,
  public val timestamp: Long,
)

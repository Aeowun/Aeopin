package com.nexicode.aeopin.`data`

import kotlin.Boolean
import kotlin.Long
import kotlin.String

public data class AeopinItems(
  public val id: Long,
  public val type: String,
  public val originalName: String?,
  public val originalPath: String?,
  public val contentHash: String?,
  public val metadataJson: String?,
  public val timestamp: Long,
  public val isPinned: Boolean,
)

package com.nexicode.aeopin.ui

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global tracker to distinguish between drags originating from within AEOPIN
 * and those coming from external sources (e.g., Windows Explorer).
 */
object InternalDragTracker {
    val isInternalDrag = AtomicBoolean(false)
}

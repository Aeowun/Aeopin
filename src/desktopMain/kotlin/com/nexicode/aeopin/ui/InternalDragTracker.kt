/*
 * AEOPIN — Local Capture & Search
 * Copyright (C) 2026 Aeowun
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.nexicode.aeopin.ui

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global tracker to distinguish between drags originating from within AEOPIN
 * and those coming from external sources (e.g., Windows Explorer).
 */
object InternalDragTracker {
    val isInternalDrag = AtomicBoolean(false)
}

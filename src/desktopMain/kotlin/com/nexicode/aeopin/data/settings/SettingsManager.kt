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

package com.nexicode.aeopin.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AeopinSettings(
    val hotkeyModifiers: Int = 10, // Ctrl + Shift (JNativeHook constants)
    val hotkeyCode: Int = 32,      // D
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowWidth: Int = 500,
    val windowHeight: Int = 600
)

class SettingsManager(private val vaultPath: String) {
    private val settingsFile = File(vaultPath, "settings.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): AeopinSettings {
        return if (settingsFile.exists()) {
            try {
                json.decodeFromString(settingsFile.readText())
            } catch (e: Exception) {
                AeopinSettings()
            }
        } else {
            AeopinSettings()
        }
    }

    fun save(settings: AeopinSettings) {
        if (!settingsFile.parentFile.exists()) settingsFile.parentFile.mkdirs()
        settingsFile.writeText(json.encodeToString(AeopinSettings.serializer(), settings))
    }
}

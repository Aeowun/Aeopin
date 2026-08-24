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

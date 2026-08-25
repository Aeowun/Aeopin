@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
    kotlin("multiplatform") version "2.0.20"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("app.cash.sqldelight") version "2.0.2"
}

group = "com.nexicode.aeopin"
version = "1.6.0"

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                
                // DI
                implementation("io.insert-koin:koin-core:3.5.6")
                
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                
                // Serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                
                // DI
                implementation("io.insert-koin:koin-compose:1.1.5")
                
                // Persistence
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
                
                // Hashing (Kotlin-first)
                implementation("org.kotlincrypto.hash:sha2:0.5.1")

                // Global Hotkeys & Native Hooks
                implementation("com.github.kwhat:jnativehook:2.2.2")
            }
        }
        
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.uiTestJUnit4)
                implementation("io.insert-koin:koin-test:3.5.6")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.nexicode.aeopin.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe, 
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )
            packageName = "AEOPIN"
            packageVersion = "1.6.0"
            includeAllModules = true
            // Explicitly including modules that Skia, SQLite, and JNativeHook depend on
            modules(
                "java.instrument", 
                "java.sql", 
                "jdk.unsupported", 
                "java.desktop", 
                "java.management", 
                "java.naming", 
                "java.xml",
                "jdk.charsets",
                "java.logging",
                "java.prefs"
            ) 
            vendor = "Aeowun"
            description = "Drop first, organize later. A Windows utility for immediate information capture."
            copyright = "© 2026 Aeowun"
            
            windows {
                iconFile.set(project.file("icon.ico"))
                shortcut = true
                menu = true
                upgradeUuid = "edec756c-6285-450a-97b8-65d084e6ebc8"
                perUserInstall = false
            }
        }
    }
}

tasks.register<Zip>("zipDistributable") {
    group = "package"
    from("build/compose/binaries/main/app/AEOPIN")
    archiveFileName.set("aeopin-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    dependsOn("createDistributable")
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("com.nexicode.aeopin.data")
        }
    }
}

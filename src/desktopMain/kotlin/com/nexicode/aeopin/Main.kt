package com.nexicode.aeopin

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.painter.ColorPainter
import com.nexicode.aeopin.data.Database
import com.nexicode.aeopin.data.settings.SettingsManager
import com.nexicode.aeopin.data.storage.VaultManager
import com.nexicode.aeopin.domain.VaultService
import com.nexicode.aeopin.ui.DesktopDropAdapter
import com.nexicode.aeopin.ui.GlobalHotkeyManager
import com.nexicode.aeopin.ui.screens.SearchScreen
import com.nexicode.aeopin.ui.theme.AeopinTheme
import com.nexicode.aeopin.ui.theme.AeopinTurquoise
import com.nexicode.aeopin.ui.theme.AeopinMidnight
import com.nexicode.aeopin.ui.theme.AeopinDeepSlate
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.awt.dnd.DropTarget
import java.io.File
import java.util.Properties
import kotlinx.coroutines.*

sealed class UiStorageState {
    object Idle : UiStorageState()
    object Dragging : UiStorageState()
    data class Success(val name: String) : UiStorageState()
    data class Error(val message: String) : UiStorageState()
}

fun main() = application {
    // SINGLE INSTANCE LOCK
    val lockSocket = try {
        java.net.ServerSocket(49152)
    } catch (e: Exception) {
        return@application
    }

    val koinApp = remember {
        startKoin {
            modules(appModule)
        }
    }
    
    val vaultService = koinApp.koin.get<VaultService>()
    val settingsManager = koinApp.koin.get<SettingsManager>()

    var isVisible by remember { mutableStateOf(true) }
    var windowActive by remember { mutableStateOf(true) }
    var storageState by remember { mutableStateOf<UiStorageState>(UiStorageState.Idle) }
    var searchQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val hotkeyManager = remember { GlobalHotkeyManager { isVisible = !isVisible } }

    LaunchedEffect(Unit) {
        vaultService.startScavenger()
        hotkeyManager.init()
    }

    // THE "WINK" ANIMATION (Vertical Shrink)
    val winkProgress = remember { Animatable(0f) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            windowActive = true
            winkProgress.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
        } else {
            winkProgress.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
            windowActive = false
        }
    }

    Tray(
        icon = painterResource("icon.ico"),
        tooltip = "DROP",
        onAction = { isVisible = true },
        menu = {
            Item("Show", onClick = { isVisible = true })
            Item("Exit", onClick = { exitApplication() })
        }
    )

    if (windowActive) {
        Dialog(
            onCloseRequest = { isVisible = false },
            state = rememberDialogState(
                width = 340.dp,
                height = 520.dp,
                position = WindowPosition(Alignment.Center)
            ),
            title = "DROP",
            undecorated = true,
            transparent = true,
            resizable = false
        ) {
            // Dialogs are hidden from taskbar by default on Windows
            LaunchedEffect(window) {
                window.isAlwaysOnTop = true
            }

            val windowInfo = LocalWindowInfo.current
            val isFocused = windowInfo.isWindowFocused
            val isDraggingOver = storageState is UiStorageState.Dragging
            
            val opacity by animateFloatAsState(
                targetValue = if (isFocused || isDraggingOver) 1.0f else 0.95f,
                animationSpec = tween(200)
            )

            LaunchedEffect(window) {
                DropTarget(window, DesktopDropAdapter(
                    vaultService = vaultService,
                    scope = scope,
                    onDragStateChange = { if (it) storageState = UiStorageState.Dragging else if (storageState == UiStorageState.Dragging) storageState = UiStorageState.Idle },
                    onStorageStarted = { },
                    onStorageSuccess = { label ->
                        scope.launch {
                            storageState = UiStorageState.Success(label)
                            delay(2000)
                            storageState = UiStorageState.Idle
                        }
                    },
                    onStorageError = { msg ->
                        scope.launch {
                            storageState = UiStorageState.Error(msg)
                            delay(3000)
                            storageState = UiStorageState.Idle
                        }
                    }
                ))
            }

            AeopinTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // VERTICAL WINK EFFECT
                            scaleY = winkProgress.value
                            alpha = winkProgress.value
                            scaleX = 0.95f + (0.05f * winkProgress.value)
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
                        }
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(
                                    if (isFocused) AeopinTurquoise.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clip(RoundedCornerShape(12.dp)),
                    color = AeopinMidnight.copy(alpha = opacity)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        WindowDraggableArea {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(AeopinTurquoise, CircleShape)
                                            .shadow(6.dp, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        "DROP",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            StorageAppliance(storageState)
                            
                            Spacer(modifier = Modifier.height(28.dp))
                            
                            SearchScreen(
                                modifier = Modifier.weight(1f),
                                searchQuery = searchQuery,
                                onSearchQueryChange = { searchQuery = it },
                                window = window
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StorageAppliance(state: UiStorageState) {
    val isDragging = state is UiStorageState.Dragging
    val isSuccess = state is UiStorageState.Success
    val isError = state is UiStorageState.Error
    
    val lift by animateDpAsState(if (isDragging) 12.dp else 0.dp, tween(200))
    val scale by animateFloatAsState(if (isDragging) 1.05f else 1.0f, tween(250, easing = FastOutSlowInEasing))
    val glowAlpha by animateFloatAsState(if (isDragging) 0.6f else 0f, tween(300))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = -lift.toPx() / 2
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(24.dp)
                .alpha(glowAlpha)
                .background(AeopinTurquoise.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(16.dp),
            color = AeopinDeepSlate,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                )
            ),
            shadowElevation = lift
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (fadeIn(tween(150)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(150))
                }
            ) { targetState ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (targetState) {
                        is UiStorageState.Idle -> {
                            Text(
                                "DRAG AND DROP",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.35f)
                            )
                        }
                        is UiStorageState.Dragging -> {
                            Text(
                                "RELEASE",
                                style = MaterialTheme.typography.headlineLarge,
                                color = AeopinTurquoise
                            )
                            Text(
                                "TO DROP",
                                style = MaterialTheme.typography.labelLarge,
                                color = AeopinTurquoise.copy(alpha = 0.6f)
                            )
                        }
                        is UiStorageState.Success -> {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = AeopinTurquoise,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                targetState.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1
                            )
                        }
                        is UiStorageState.Error -> {
                            Text("FAIL", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.error)
                            Text(targetState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

val appModule = module {
    val vaultPath = System.getProperty("user.home") + "/Documents/AEOPIN"
    single<Database> {
        val dbFile = File(vaultPath, "aeopin.db")
        if (!dbFile.parentFile.exists()) dbFile.parentFile.mkdirs()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        
        java.sql.DriverManager.getConnection(url).use { conn ->
            val metadata = conn.metaData

            fun addColumnIfMissing(tableName: String, columnName: String, type: String) {
                val rs = metadata.getColumns(null, null, tableName, columnName)
                val exists = rs.next()
                rs.close()
                if (!exists) {
                    val tables = metadata.getTables(null, null, tableName, null)
                    if (tables.next()) {
                        conn.createStatement().use { it.execute("ALTER TABLE $tableName ADD COLUMN $columnName $type;") }
                    }
                    tables.close()
                }
            }

            addColumnIfMissing("AeopinItems", "isPinned", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("AeopinItems", "originalPath", "TEXT")

            val piCols = metadata.getColumns(null, null, "PendingIngestion", null)
            val colNames = mutableSetOf<String>()
            var isStagedPathNotNull = false
            while(piCols.next()) {
                val name = piCols.getString("COLUMN_NAME")
                colNames.add(name)
                if (name == "stagedPath" && piCols.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls) {
                    isStagedPathNotNull = true
                }
            }
            piCols.close()

            if (isStagedPathNotNull || colNames.contains("rawContent") || !colNames.contains("stagedPath")) {
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP TABLE IF EXISTS PendingIngestion;")
                    stmt.execute("""
                        CREATE TABLE PendingIngestion (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            type TEXT NOT NULL,
                            stagedPath TEXT,
                            sourcePath TEXT,
                            expectedHash TEXT,
                            expectedSize INTEGER,
                            state TEXT NOT NULL,
                            timestamp INTEGER NOT NULL
                        );
                    """.trimIndent())
                }
            }

            addColumnIfMissing("PendingIngestion", "expectedHash", "TEXT")
            addColumnIfMissing("PendingIngestion", "expectedSize", "INTEGER")
            addColumnIfMissing("PendingIngestion", "state", "TEXT NOT NULL DEFAULT 'PREPARING'")

            val version = conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA user_version;")
                if (rs.next()) rs.getLong(1) else 0L
            }
            
            if (version < 3L) {
                conn.createStatement().use { it.execute("PRAGMA user_version = 3;") }
            }
        }

        val driver = JdbcSqliteDriver(url, Properties())
        val currentVersion = driver.executeQuery(null, "PRAGMA user_version;", { cursor ->
            if (cursor.next().value) app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)) 
            else app.cash.sqldelight.db.QueryResult.Value(0L)
        }, 0).value ?: 0L

        if (currentVersion == 0L) {
            try {
                Database.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version = 3;", 0)
            } catch (e: Exception) {}
        }
        
        Database(driver)
    }
    single { VaultManager(vaultPath) }
    single { SettingsManager(vaultPath) }
    single { VaultService(get(), get(), CoroutineScope(Dispatchers.IO + SupervisorJob())) }
}

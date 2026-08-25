package com.nexicode.aeopin.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexicode.aeopin.data.Database
import com.nexicode.aeopin.data.AeopinItems
import com.nexicode.aeopin.data.storage.VaultManager
import com.nexicode.aeopin.ui.theme.AeopinTurquoise
import com.nexicode.aeopin.ui.theme.AeopinDeepSlate
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.JPanel
import com.nexicode.aeopin.ui.InternalDragTracker
import kotlin.io.path.deleteRecursively
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    window: java.awt.Component
) {
    val database = koinInject<Database>()
    val vaultManager = koinInject<VaultManager>()
    val queries = database.databaseQueries
    
    var selectedType by remember { mutableStateOf<String?>(null) }
    var itemToDelete by remember { mutableStateOf<AeopinItems?>(null) }
    var pendingDragItem by remember { mutableStateOf<AeopinItems?>(null) }

    LaunchedEffect(window) {
        val ds = DragSource.getDefaultDragSource()
        ds.createDefaultDragGestureRecognizer(window, DnDConstants.ACTION_MOVE) { dge ->
            val item = pendingDragItem
            if (item != null && (item.type == "FILE" || item.type == "FOLDER") && item.contentHash != null) {
                InternalDragTracker.isInternalDrag.set(true)
                val file = vaultManager.prepareForExport(item)
                val transferable = object : Transferable {
                    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
                    override fun isDataFlavorSupported(f: DataFlavor) = f == DataFlavor.javaFileListFlavor
                    override fun getTransferData(f: DataFlavor) = listOf(file)
                }

                dge.startDrag(DragSource.DefaultMoveDrop, transferable, object : DragSourceAdapter() {
                    override fun dragDropEnd(dsde: DragSourceDropEvent) {
                        InternalDragTracker.isInternalDrag.set(false)
                        pendingDragItem = null
                        if (dsde.dropSuccess && dsde.dropAction == DnDConstants.ACTION_MOVE) {
                            deleteItemPermanently(item, queries, vaultManager)
                        }
                        // Delayed cleanup to ensure OS has finished with the file/folder
                        java.util.Timer().schedule(object : java.util.TimerTask() {
                            override fun run() {
                                if (file.exists()) {
                                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                                }
                            }
                        }, 1000)
                    }
                })
            }
        }
    }

    val itemsFlow = remember(searchQuery, selectedType) {
        val baseQuery = if (searchQuery.isEmpty()) {
            if (selectedType == null) queries.selectAllItems() else queries.filterByType(selectedType!!)
        } else {
            queries.search(searchQuery)
        }
        
        baseQuery.asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                if (selectedType != null && searchQuery.isNotEmpty()) {
                    list.filter { it.type == selectedType }
                } else list
            }
    }

    val items by itemsFlow.collectAsState(emptyList())

    val groupedItems = remember(items) {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        items.groupBy { item ->
            when {
                item.isPinned -> "Pinned"
                item.timestamp >= today -> "Today"
                else -> "Earlier"
            }
        }
    }

    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                color = AeopinTurquoise.copy(alpha = 0.03f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AeopinTurquoise.copy(alpha = 0.1f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp), tint = AeopinTurquoise.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text("Search drops...", color = Color.Gray.copy(alpha = 0.4f), style = MaterialTheme.typography.bodyLarge)
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AeopinTurquoise.copy(alpha = 0.9f)),
                            cursorBrush = SolidColor(AeopinTurquoise),
                            singleLine = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(label = "All", selected = selectedType == null) { selectedType = null }
                FilterChip(label = "Files", selected = selectedType == "FILE") { selectedType = "FILE" }
                FilterChip(label = "Folders", selected = selectedType == "FOLDER") { selectedType = "FOLDER" }
                FilterChip(label = "Links", selected = selectedType == "URL") { selectedType = "URL" }
                FilterChip(label = "Text", selected = selectedType == "TEXT") { selectedType = "TEXT" }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (items.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val sectionOrder = listOf("Pinned", "Today", "Earlier")
                    sectionOrder.forEach { section ->
                        groupedItems[section]?.let { sectionItems ->
                            stickyHeader {
                                SectionHeader(section)
                            }
                            items(sectionItems, key = { it.id }) { item ->
                                AeopinItemRow(
                                    item = item,
                                    dateLabel = dateFormatter.format(Date(item.timestamp)),
                                    onTogglePin = { queries.togglePinned(!item.isPinned, item.id) },
                                    onDeleteRequest = { itemToDelete = item },
                                    onOpen = { openItem(item, vaultManager) },
                                    vaultManager = vaultManager,
                                    onDeletePermanently = { deleteItemPermanently(item, queries, vaultManager) },
                                    window = window,
                                    onDragStarted = { pendingDragItem = it }
                                )
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searchQuery.isEmpty()) "No drops here yet" else "No matches found", style = MaterialTheme.typography.bodySmall, color = Color.Gray.copy(alpha = 0.5f))
                }
            }
        }

        itemToDelete?.let { item ->
            AlertDialog(
                onDismissRequest = { itemToDelete = null },
                title = { Text("Delete forever?", style = MaterialTheme.typography.headlineMedium) },
                text = { Text("This will permanently remove the original ${if(item.type == "FOLDER") "folder" else "file"} from your computer.", style = MaterialTheme.typography.bodyLarge) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteItemPermanently(item, queries, vaultManager)
                            itemToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { itemToDelete = null }) {
                        Text("Cancel")
                    }
                },
                containerColor = AeopinDeepSlate,
                textContentColor = AeopinTurquoise.copy(alpha = 0.8f),
                titleContentColor = AeopinTurquoise
            )
        }
    }
}

private fun openItem(item: AeopinItems, vaultManager: VaultManager) {
    try {
        when (item.type) {
            "FILE", "FOLDER" -> {
                item.contentHash?.let { hash ->
                    val path = vaultManager.getVaultPath(hash)
                    if (Files.exists(path)) Desktop.getDesktop().open(path.toFile())
                }
            }
            "URL" -> {
                val url = item.metadataJson?.substringAfter("\"url\": \"")?.substringBefore("\"") ?: ""
                if (url.startsWith("http")) Desktop.getDesktop().browse(URI(url))
            }
            "TEXT" -> {}
        }
    } catch (e: Exception) { e.printStackTrace() }
}

private fun deleteItemPermanently(item: AeopinItems, queries: com.nexicode.aeopin.data.DatabaseQueries, vaultManager: VaultManager) {
    queries.deleteItem(item.id)
    if ((item.type == "FILE" || item.type == "FOLDER") && item.contentHash != null) {
        val path = vaultManager.getVaultPath(item.contentHash)
        val others = queries.selectAllItems().executeAsList().count { it.contentHash == item.contentHash }
        if (others == 0 && Files.exists(path)) {
            Files.deleteIfExists(path)
        }
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (selected) 1f else 0.4f)
    val bgColor by animateColorAsState(if (selected) AeopinTurquoise.copy(alpha = 0.15f) else Color.Transparent)
    
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, if (selected) AeopinTurquoise.copy(alpha = 0.3f) else AeopinTurquoise.copy(alpha = 0.1f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            label, 
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 10.sp),
            color = if (selected) AeopinTurquoise else AeopinTurquoise.copy(alpha = alpha)
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 9.sp, letterSpacing = 1.2.sp),
        color = AeopinTurquoise.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp)
    )
}

@Composable
fun AeopinItemRow(
    item: AeopinItems,
    dateLabel: String,
    onTogglePin: () -> Unit,
    onDeleteRequest: () -> Unit,
    onOpen: () -> Unit,
    vaultManager: VaultManager,
    onDeletePermanently: () -> Unit,
    window: java.awt.Component,
    onDragStarted: (AeopinItems) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(item.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                            onDragStarted(item)
                        }
                    }
                }
            }
            .clickable { onOpen() },
        color = AeopinTurquoise.copy(alpha = 0.02f)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(28.dp).background(AeopinTurquoise.copy(alpha = 0.05f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                AeopinIdentityIcon(type = item.type, color = AeopinTurquoise.copy(alpha = 0.9f))
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.originalName ?: "Untitled",
                    style = MaterialTheme.typography.bodyLarge.copy(color = AeopinTurquoise.copy(alpha = 0.9f), fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
                Text(text = "$dateLabel · ${item.type.lowercase()}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = Color.Gray.copy(alpha = 0.6f))
            }

            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.MoreVert, null, tint = AeopinTurquoise.copy(alpha = 0.3f), modifier = Modifier.size(14.dp))
                
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(AeopinDeepSlate).border(1.dp, AeopinTurquoise.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Open", style = MaterialTheme.typography.bodyLarge.copy(color = AeopinTurquoise), fontWeight = FontWeight.Bold) },
                        onClick = { showMenu = false; onOpen() }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy", style = MaterialTheme.typography.bodyLarge.copy(color = AeopinTurquoise.copy(alpha = 0.8f))) },
                        onClick = { 
                            showMenu = false
                            if ((item.type == "FILE" || item.type == "FOLDER") && item.contentHash != null) {
                                val file = vaultManager.prepareForExport(item)
                                val transferable = object : Transferable {
                                    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
                                    override fun isDataFlavorSupported(f: DataFlavor) = f == DataFlavor.javaFileListFlavor
                                    override fun getTransferData(f: DataFlavor) = listOf(file)
                                }
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
                            } else {
                                val text = when(item.type) {
                                    "URL" -> item.metadataJson?.substringAfter("\"url\": \"")?.substringBefore("\"") ?: ""
                                    else -> item.metadataJson ?: ""
                                }
                                clipboardManager.setText(AnnotatedString(text))
                            }
                        }
                    )
                    
                    if ((item.type == "FILE" || item.type == "FOLDER") && item.originalPath != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = AeopinTurquoise.copy(alpha = 0.1f))
                        DropdownMenuItem(
                            text = { Text("Restore to original folder", style = MaterialTheme.typography.bodyLarge.copy(color = AeopinTurquoise.copy(alpha = 0.8f))) },
                            onClick = {
                                showMenu = false
                                try {
                                    val source = vaultManager.prepareForExport(item)
                                    val target = File(item.originalPath).toPath()
                                    if (!Files.exists(target.parent)) Files.createDirectories(target.parent)
                                    Files.move(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
                                    onDeletePermanently()
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = AeopinTurquoise.copy(alpha = 0.1f))
                    DropdownMenuItem(
                        text = { Text(if (item.isPinned) "Unpin" else "Pin", style = MaterialTheme.typography.bodyLarge.copy(color = AeopinTurquoise.copy(alpha = 0.8f))) },
                        onClick = { showMenu = false; onTogglePin() }
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = AeopinTurquoise.copy(alpha = 0.1f))
                    DropdownMenuItem(
                        text = { Text("Delete forever", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDeleteRequest() }
                    )
                }
            }
        }
    }
}

@Composable
fun AeopinIdentityIcon(type: String, color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        when(type) {
            "FOLDER" -> {
                val path = Path().apply {
                    moveTo(2.dp.toPx(), 4.dp.toPx())
                    lineTo(6.dp.toPx(), 4.dp.toPx())
                    lineTo(8.dp.toPx(), 2.dp.toPx())
                    lineTo(14.dp.toPx(), 2.dp.toPx())
                    lineTo(14.dp.toPx(), 14.dp.toPx())
                    lineTo(2.dp.toPx(), 14.dp.toPx())
                    close()
                }
                drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
            }
            "URL" -> {
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(5.dp.toPx(), 5.dp.toPx()), style = Stroke(width = 1.5.dp.toPx()))
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(11.dp.toPx(), 11.dp.toPx()), style = Stroke(width = 1.5.dp.toPx()))
                drawLine(color, start = Offset(6.dp.toPx(), 6.dp.toPx()), end = Offset(10.dp.toPx(), 10.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
            "TEXT" -> {
                drawLine(color, start = Offset(2.dp.toPx(), 4.dp.toPx()), end = Offset(14.dp.toPx(), 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                drawLine(color, start = Offset(2.dp.toPx(), 8.dp.toPx()), end = Offset(11.dp.toPx(), 8.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                drawLine(color, start = Offset(2.dp.toPx(), 12.dp.toPx()), end = Offset(14.dp.toPx(), 12.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
            else -> {
                drawRoundRect(color, topLeft = Offset(3.dp.toPx(), 2.dp.toPx()), size = Size(10.dp.toPx(), 12.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()), style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

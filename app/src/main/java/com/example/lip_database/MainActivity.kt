package com.example.lip_database

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.lip_database.ui.theme.Lip_DataBaseTheme
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppTab(val label: String) {
    ADD("Add"),
    REMOVE("Remove"),
    INVENTORIES("Inventories"),
    STICKERS("Stickers"),
    SYNC("Sync"),
}

private data class InventoryUiState(
    val inventories: List<Inventory> = emptyList(),
    val catalogItems: List<CatalogItem> = emptyList(),
    val selectedInventoryId: Long? = null,
    val selectedStock: List<InventoryItem> = emptyList(),
    val message: String? = null,
    val cloud: CloudUiState = CloudUiState(),
)

private class InventoryViewModel(context: Context) : ViewModel() {
    private val database = InventoryDatabase(context.applicationContext)
    private val cloudSync = CloudSyncManager(database) { cloud ->
        state = state.copy(cloud = cloud)
        refresh()
    }

    var state by mutableStateOf(InventoryUiState())
        private set

    init {
        refresh()
        cloudSync.start()
    }

    fun selectInventory(inventoryId: Long?) {
        if (inventoryId == state.selectedInventoryId) return
        state = state.copy(selectedInventoryId = inventoryId)
        refresh()
    }

    fun createItem(name: String, itemCode: String, storage: String, smNumber: String, weightText: String) {
        if (!hasAccessTo(smNumber.trim())) {
            state = state.copy(message = "This PIN only allows SM ${state.cloud.unlockedSmNumber}.")
            return
        }
        val weight = weightText.replace(',', '.').toDoubleOrNull()
        if (weight == null) {
            state = state.copy(message = "Enter a valid weight in kilograms.")
            return
        }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { database.addItem(name, itemCode, storage, smNumber, weight) }
            if (error == null) {
                state = state.copy(
                    message = if (smNumber.isBlank()) {
                        "General item added. It can be used in any inventory."
                    } else {
                        "Item added. ${InventoryDatabase.inventoryName(storage, smNumber)} is ready to use."
                    },
                )
                refresh()
                cloudSync.publishLocalChanges()
            } else {
                state = state.copy(message = error)
            }
        }
    }

    fun deleteItem(id: String) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { database.deleteItem(id) }
            if (error == null) {
                state = state.copy(message = "Item deleted.")
                refresh()
                cloudSync.publishLocalChanges()
            } else {
                state = state.copy(message = error)
            }
        }
    }

    fun deleteEmptyInventory(inventoryId: Long) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { database.deleteEmptyInventory(inventoryId) }
            if (error == null) {
                state = state.copy(message = "Empty inventory deleted.")
                cloudSync.publishLocalChanges()
                refresh()
            } else {
                state = state.copy(message = error)
            }
        }
    }

    fun recordChange(inventoryId: Long, quantities: Map<String, Int>, isAddition: Boolean, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                database.applyStockChange(inventoryId, quantities, isAddition)
            }
            if (error == null) {
                state = state.copy(message = if (isAddition) "Items added to inventory." else "Items removed from inventory.")
                onSuccess()
                refresh()
                cloudSync.publishLocalChanges()
            } else {
                state = state.copy(message = error)
            }

        }
    }

    fun syncNow() = cloudSync.syncNow()
    fun setMasterPin(pin: String) = cloudSync.setMasterPin(pin)
    fun unlock(pin: String) = cloudSync.unlock(pin)
    fun setSmPin(smNumber: String, pin: String) = cloudSync.setSmPin(smNumber, pin)
    fun lock() = cloudSync.lock()

    fun hasAccessTo(smNumber: String): Boolean =
        !state.cloud.isLocked &&
            (smNumber.isBlank() ||
                state.cloud.isMasterUnlocked ||
                state.cloud.unlockedSmNumber == null ||
                state.cloud.unlockedSmNumber == smNumber)

    private fun refresh() {
        viewModelScope.launch {
            val selectedId = state.selectedInventoryId
            val data = withContext(Dispatchers.IO) {
                Triple(database.inventories(), database.catalogItems(), selectedId?.let(database::stock).orEmpty())
            }
            val availableInventoryIds = data.first.map(Inventory::id).toSet()
            val retainedSelection = selectedId?.takeIf(availableInventoryIds::contains)
            state = state.copy(
                inventories = data.first,
                catalogItems = data.second,
                selectedInventoryId = retainedSelection,
                selectedStock = if (retainedSelection == selectedId) data.third else emptyList(),
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private val inventoryViewModel by viewModels<InventoryViewModel> {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                InventoryViewModel(applicationContext) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Lip_DataBaseTheme {
                InventoryApp(inventoryViewModel)
            }
        }
    }
}

@Composable
private fun InventoryApp(viewModel: InventoryViewModel) {
    var selectedTab by remember { mutableStateOf(AppTab.ADD) }
    val state = viewModel.state
    if (state.cloud.isLocked) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                SyncScreen(state, viewModel)
            }
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { AppTabIcon(tab) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            when (selectedTab) {
                AppTab.ADD -> OperationScreen(state, viewModel, isAddition = true)
                AppTab.REMOVE -> OperationScreen(state, viewModel, isAddition = false)
                AppTab.INVENTORIES -> InventoriesScreen(state, viewModel)
                AppTab.STICKERS -> StickersScreen(state, viewModel)
                AppTab.SYNC -> SyncScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun AppTabIcon(tab: AppTab) {
    val (imageVector, description) = when (tab) {
        AppTab.ADD -> Icons.Filled.AddCircle to "Add"
        AppTab.REMOVE -> Icons.Filled.RemoveCircle to "Remove"
        AppTab.INVENTORIES -> Icons.AutoMirrored.Filled.FormatListBulleted to "Inventories"
        AppTab.STICKERS -> Icons.Filled.QrCode2 to "Stickers"
        AppTab.SYNC -> Icons.Filled.QrCode2 to "Cloud sync"
    }
    Icon(imageVector, contentDescription = description)
}

@Composable
private fun ColumnScope.OperationScreen(state: InventoryUiState, viewModel: InventoryViewModel, isAddition: Boolean) {
    var inventoryMenuOpen by remember { mutableStateOf(false) }
    var cameraMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val pending = remember { mutableStateMapOf<String, Int>() }
    val quantityText = remember { mutableStateMapOf<String, String>() }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (!granted) cameraMessage = "Camera access is required to scan QR codes."
    }
    val visibleInventories = state.inventories.filter { inventory ->
        !state.cloud.isLocked &&
            (state.cloud.unlockedSmNumber == null || inventory.name.endsWith(" - ${state.cloud.unlockedSmNumber}"))
    }
    val selectedInventory = visibleInventories.firstOrNull { it.id == state.selectedInventoryId }
    val catalogById = state.catalogItems.filter { viewModel.hasAccessTo(it.smNumber) }.associateBy(CatalogItem::id)
    val currentStockById = state.selectedStock.associateBy(InventoryItem::id)

    LaunchedEffect(Unit) {
        if (!cameraPermissionGranted) requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    Text(if (isAddition) "Add items" else "Remove items", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Box {
        OutlinedButton(onClick = { inventoryMenuOpen = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(selectedInventory?.name ?: "Select inventory", style = MaterialTheme.typography.titleMedium)
        }
        androidx.compose.material3.DropdownMenu(
            expanded = inventoryMenuOpen,
            onDismissRequest = { inventoryMenuOpen = false },
        ) {
            visibleInventories.forEach { inventory ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(inventory.name) },
                    onClick = {
                        viewModel.selectInventory(inventory.id)
                        inventoryMenuOpen = false
                        pending.clear()
                        quantityText.clear()
                    },
                )
            }
        }
    }
    if (cameraPermissionGranted) {
        CameraScanner(
            onCode = { code ->
                val quantity = (pending[code] ?: 0) + 1
                pending[code] = quantity
                quantityText[code] = quantity.toString()
            },
            showPreview = false,
        )
    }
    cameraMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text("Items waiting for confirmation", style = MaterialTheme.typography.titleMedium)
            if (pending.isEmpty()) {
                Text(
                    "Scan a QR code to add an item.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(pending.keys.toList(), key = { it }) { itemId ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            ) {
                            val item = catalogById[itemId]
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item?.let { "${it.name} - ${it.storage}" } ?: "Unknown QR item",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = "In inventory: ${currentStockById[itemId]?.quantity ?: 0}",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            OutlinedTextField(
                                value = quantityText[itemId] ?: pending[itemId].toString(),
                                onValueChange = { value ->
                                    if (value.all(Char::isDigit)) {
                                        quantityText[itemId] = value
                                        value.toIntOrNull()?.let { pending[itemId] = it }
                                    }
                                },
                                label = { Text("Qty") },
                                modifier = Modifier.width(104.dp),
                                textStyle = MaterialTheme.typography.titleLarge,
                                singleLine = true,
                            )
                            }
                        }
                    }
                }
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        OutlinedButton(onClick = {
            pending.clear()
            quantityText.clear()
        }, modifier = Modifier.weight(1f).height(52.dp)) {
            Text("Cancel")
        }
        Button(
            onClick = {
                state.selectedInventoryId?.let { inventoryId ->
                    val quantities = pending.mapValues { (itemId, _) -> quantityText[itemId]!!.toInt() }
                    viewModel.recordChange(inventoryId, quantities, isAddition) {
                        pending.clear()
                        quantityText.clear()
                    }
                }
            },
            enabled = selectedInventory != null &&
                pending.isNotEmpty() &&
                pending.keys.all { quantityText[it]?.toIntOrNull()?.let { quantity -> quantity > 0 } == true },
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Text("Confirm")
        }
    }
}

@Composable
private fun ColumnScope.InventoriesScreen(state: InventoryUiState, viewModel: InventoryViewModel) {
    val context = LocalContext.current
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var firstDeleteConfirmation by remember { mutableStateOf<Inventory?>(null) }
    var finalDeleteConfirmation by remember { mutableStateOf<Inventory?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        exportMessage = when {
            uri == null -> "Export canceled."
            state.selectedInventoryId == null -> "Select an inventory before exporting."
            else -> exportInventoryCsv(context, uri, state.selectedStock)
        }
    }
    Text("Inventories", style = MaterialTheme.typography.headlineSmall)
    Text("Inventories are created automatically from an item's storage name and SM number.")
    Spacer(Modifier.height(12.dp))
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
        items(state.inventories.filter {
            !state.cloud.isLocked &&
                (state.cloud.unlockedSmNumber == null || it.name.endsWith(" - ${state.cloud.unlockedSmNumber}")
            )
        }, key = Inventory::id) { inventory ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { viewModel.selectInventory(inventory.id) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(inventory.name, style = MaterialTheme.typography.titleMedium)
                    if (inventory.id == state.selectedInventoryId) {
                        if (state.selectedStock.isEmpty()) {
                            Text("This inventory is empty.")
                        } else {
                            state.selectedStock.forEach { item ->
                                Text("${item.name} - ${item.storage} — ${item.quantity} units")
                            }
                        }
                        TextButton(
                            onClick = {
                                exportLauncher.launch("${safeFileName(inventory.name)}-inventory.csv")
                            },
                        ) {
                            Text("Export for Excel")
                        }
                        if (state.cloud.isMasterUnlocked && state.selectedStock.isEmpty()) {
                            TextButton(onClick = { firstDeleteConfirmation = inventory }) {
                                Text("Delete empty inventory")
                            }
                        }
                    }
                }
            }
        }
    }
    exportMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    firstDeleteConfirmation?.let { inventory ->
        AlertDialog(
            onDismissRequest = { firstDeleteConfirmation = null },
            title = { Text("Delete ${inventory.name}?") },
            text = { Text("This inventory is empty. Continue to the final confirmation?") },
            confirmButton = {
                TextButton(onClick = {
                    firstDeleteConfirmation = null
                    finalDeleteConfirmation = inventory
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { firstDeleteConfirmation = null }) { Text("Cancel") } },
        )
    }
    finalDeleteConfirmation?.let { inventory ->
        AlertDialog(
            onDismissRequest = { finalDeleteConfirmation = null },
            title = { Text("Permanently delete inventory?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEmptyInventory(inventory.id)
                    finalDeleteConfirmation = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { finalDeleteConfirmation = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ColumnScope.SyncScreen(state: InventoryUiState, viewModel: InventoryViewModel) {
    var pin by remember { mutableStateOf("") }
    var smNumber by remember { mutableStateOf("") }
    var smPin by remember { mutableStateOf("") }
    if (state.cloud.isLocked) {
        Spacer(Modifier.weight(1f))
        Text(
            "Lip - DataBase",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(4) },
            label = { Text("PIN") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.unlock(pin); pin = "" },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Log in")
        }
        state.cloud.status.takeIf { it != "Cloud sync active" }?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
    } else {
        Text(
            if (state.cloud.isMasterUnlocked) "Master access is unlocked." else "Restricted to SM ${state.cloud.unlockedSmNumber}.",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(onClick = viewModel::lock) { Text("Lock") }
        if (state.cloud.isMasterUnlocked) {
            Spacer(Modifier.height(16.dp))
            Text("Set SM PIN", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(smNumber, { smNumber = it }, label = { Text("SM number") }, singleLine = true)
            OutlinedTextField(smPin, { smPin = it.filter(Char::isDigit).take(4) }, label = { Text("Four-digit SM PIN") }, singleLine = true)
            Button(onClick = { viewModel.setSmPin(smNumber, smPin); smPin = "" }) { Text("Save SM PIN") }
        }
    }
}

@Composable
private fun ColumnScope.StickersScreen(state: InventoryUiState, viewModel: InventoryViewModel) {
    var addingItem by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<CatalogItem?>(null) }
    Text("Sticker catalog", style = MaterialTheme.typography.headlineSmall)
    Text("Create each box/item once, then generate a QR label from its ID.")
    Spacer(Modifier.height(8.dp))
    Button(onClick = { addingItem = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Filled.AddCircle, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Add catalog item")
    }
    Spacer(Modifier.height(12.dp))
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
        items(state.catalogItems.filter { viewModel.hasAccessTo(it.smNumber) }, key = CatalogItem::id) { item ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text("${item.itemCode} • ${item.storage} • ${item.smNumber} • ${item.weightKg} kg")
                    Row {
                        TextButton(onClick = { selectedItem = item }) { Text("Make QR sticker") }
                        TextButton(onClick = { viewModel.deleteItem(item.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (addingItem) {
        AddItemDialog(
            onDismiss = { addingItem = false },
            onConfirm = { name, itemCode, storage, smNumber, weight ->
                viewModel.createItem(name, itemCode, storage, smNumber, weight)
                addingItem = false
            },
        )
    }
    selectedItem?.let { QrStickerDialog(it, onDismiss = { selectedItem = null }) }
}

@Composable
private fun AddItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, itemCode: String, storage: String, smNumber: String, weight: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var itemCode by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf("") }
    var smNumber by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add catalog item") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    itemCode,
                    { itemCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Šifra (6 digits)") },
                    singleLine = true,
                )
                OutlinedTextField(storage, { storage = it }, label = { Text("Storage name") }, singleLine = true)
                OutlinedTextField(smNumber, { smNumber = it }, label = { Text("SM number") }, singleLine = true)
                OutlinedTextField(weight, { weight = it }, label = { Text("Weight per unit (kg)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, itemCode, storage, smNumber, weight) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QrStickerDialog(item: CatalogItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(item.id) { createStickerBitmap(item) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${item.name} sticker") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Sticker for ${item.name}",
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
                resultMessage?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { resultMessage = saveQrPng(context, item.name, item.smNumber, bitmap) }) {
                Text("Save PNG")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun createStickerBitmap(item: CatalogItem): Bitmap {
    val width = 1200
    val height = 675
    val sticker = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sticker)
    canvas.drawColor(Color.WHITE)
    val centeredPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    val qr = createQrBitmap(item.id, 320)
    canvas.drawBitmap(qr, 70f, 35f, null)

    centeredPaint.textSize = 90f
    canvas.drawText(item.name.uppercase(), 790f, 230f, centeredPaint)
    centeredPaint.textSize = 48f
    canvas.drawText(item.storage.uppercase(), 790f, 310f, centeredPaint)
    centeredPaint.textSize = 68f
    canvas.drawText(item.itemCode, 790f, 400f, centeredPaint)

    val cornerPaint = Paint(centeredPaint).apply {
        textAlign = Paint.Align.LEFT
        textSize = 46f
    }
    if (item.smNumber.isNotBlank()) {
        canvas.drawText("SM ${item.smNumber.uppercase()}", 70f, 655f, cornerPaint)
    }
    cornerPaint.textAlign = Paint.Align.RIGHT
    canvas.drawText("Datum:__________________", width - 70f, 655f, cornerPaint)
    return sticker
}

private fun createQrBitmap(value: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}

private fun saveQrPng(context: Context, itemName: String, smNumber: String, bitmap: Bitmap): String {
    return try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "${safeFileName(itemName)}-$smNumber.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QR Inventory")
    }

    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: return "Could not create the PNG file."
    context.contentResolver.openOutputStream(uri)?.use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    } ?: return "Could not write the PNG file."
    "Saved PNG to Pictures/QR Inventory."
    } catch (_: IOException) {
        "Could not save the PNG file."
    } catch (_: SecurityException) {
        "Storage permission was denied."
    }
}

private fun safeFileName(value: String): String = value.replace(Regex("""[\\/:*?"<>|]"""), "_")

private fun exportInventoryCsv(context: Context, uri: Uri, stock: List<InventoryItem>): String {
    return try {
        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                writer.append('\uFEFF')
                writer.appendLine("sep=;")
                writer.appendLine("Name;Storage;SM number;Quantity;Weight per unit (kg);Last added")
                stock.forEach { item ->
                    writer.appendLine(
                        listOf(
                            item.name,
                            item.storage,
                            item.smNumber,
                            item.quantity.toString(),
                            item.weightKg.toString(),
                            item.lastAddedAt?.let(::formatSpreadsheetTimestamp).orEmpty(),
                        ).joinToString(";") { csvField(it) },
                    )
                }
            }
        } ?: return "Could not create the export file."
        "Excel-ready inventory spreadsheet exported."
    } catch (_: IOException) {
        "Could not export the inventory spreadsheet."
    } catch (_: SecurityException) {
        "Storage access was denied."
    }
}

private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun formatSpreadsheetTimestamp(timestamp: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))

@Composable
private fun CameraScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    showPreview: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val nextScanAllowedAt = remember { AtomicLong(0) }
    val scanBeep = remember { MediaPlayer.create(context, R.raw.scan_beep) }

    DisposableEffect(scanBeep) {
        onDispose { scanBeep.release() }
    }

    DisposableEffect(lifecycleOwner) {
        val setupCamera = Runnable {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val scanner = BarcodeScanning.getClient()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                } else {
                    scanner.process(InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.let { value ->
                                val now = SystemClock.elapsedRealtime()
                                val nextAllowed = nextScanAllowedAt.get()
                                if (now >= nextAllowed &&
                                    nextScanAllowedAt.compareAndSet(nextAllowed, now + SCAN_COOLDOWN_MILLIS)
                                ) {
                                    scanBeep.start()
                                    latestOnCode(value)
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
            }
            cameraProvider.unbindAll()
            if (showPreview) {
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } else {
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }
        }
        cameraProviderFuture.addListener(setupCamera, ContextCompat.getMainExecutor(context))
        onDispose {
            if (cameraProviderFuture.isDone) cameraProviderFuture.get().unbindAll()
            cameraExecutor.shutdown()
        }
    }
    if (showPreview) {
        AndroidView(factory = { previewView }, modifier = modifier.clipToBounds())
    }
}

private const val SCAN_COOLDOWN_MILLIS = 2_000L

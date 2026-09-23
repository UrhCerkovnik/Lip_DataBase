package com.example.lip_database

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppTab(val label: String) {
    ADD("Add"),
    REMOVE("Remove"),
    INVENTORIES("Inventories"),
    STICKERS("Stickers"),
}

private data class InventoryUiState(
    val inventories: List<Inventory> = emptyList(),
    val catalogItems: List<CatalogItem> = emptyList(),
    val selectedInventoryId: Long? = null,
    val selectedStock: List<InventoryItem> = emptyList(),
    val message: String? = null,
)

private class InventoryViewModel(context: Context) : ViewModel() {
    private val database = InventoryDatabase(context.applicationContext)

    var state by mutableStateOf(InventoryUiState())
        private set

    init {
        refresh()
    }

    fun selectInventory(inventoryId: Long?) {
        if (inventoryId == state.selectedInventoryId) return
        state = state.copy(selectedInventoryId = inventoryId)
        refresh()
    }

    fun createInventory(name: String) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { database.addInventory(name) }
            if (error == null) {
                state = state.copy(message = "Inventory created.")
                refresh()
            } else {
                state = state.copy(message = error)
            }
        }
    }

    fun createItem(name: String, storage: String, smNumber: String, weightText: String) {
        val weight = weightText.replace(',', '.').toDoubleOrNull()
        if (weight == null) {
            state = state.copy(message = "Enter a valid weight in kilograms.")
            return
        }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { database.addItem(name, storage, smNumber, weight) }
            if (error == null) {
                state = state.copy(message = "Item added to the sticker catalog.")
                refresh()
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
            } else {
                state = state.copy(message = error)
            }
        }
    }

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

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Text(tab.label.take(1)) },
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
            }
        }
    }
}

@Composable
private fun OperationScreen(state: InventoryUiState, viewModel: InventoryViewModel, isAddition: Boolean) {
    var inventoryMenuOpen by remember { mutableStateOf(false) }
    var scannerVisible by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf("") }
    var cameraMessage by remember { mutableStateOf<String?>(null) }
    val pending = remember { mutableStateMapOf<String, Int>() }
    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scannerVisible = true else cameraMessage = "Camera access is required to scan QR codes."
    }
    val selectedInventory = state.inventories.firstOrNull { it.id == state.selectedInventoryId }

    Text(if (isAddition) "Add items" else "Remove items", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Box {
        OutlinedButton(onClick = { inventoryMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selectedInventory?.name ?: "Select inventory")
        }
        androidx.compose.material3.DropdownMenu(
            expanded = inventoryMenuOpen,
            onDismissRequest = { inventoryMenuOpen = false },
        ) {
            state.inventories.forEach { inventory ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(inventory.name) },
                    onClick = {
                        viewModel.selectInventory(inventory.id)
                        inventoryMenuOpen = false
                        pending.clear()
                    },
                )
            }
        }
    }
    if (state.inventories.isEmpty()) {
        Text("Create an inventory on the Inventories tab first.")
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { requestCameraPermission.launch(Manifest.permission.CAMERA) },
        enabled = selectedInventory != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Scan QR code")
    }
    cameraMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = manualId,
            onValueChange = { manualId = it },
            label = { Text("Item ID (manual test)") },
            modifier = Modifier.width(220.dp),
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            val id = manualId.trim()
            if (id.isNotEmpty()) {
                pending[id] = (pending[id] ?: 0) + 1
                manualId = ""
            }
        }) { Text("Add") }
    }
    Spacer(Modifier.height(12.dp))
    Text("Items waiting for confirmation", style = MaterialTheme.typography.titleMedium)
    LazyColumn(modifier = Modifier.height(260.dp)) {
        items(pending.keys.toList(), key = { it }) { itemId ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(itemId, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = pending[itemId].toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let { pending[itemId] = it } },
                    label = { Text("Qty") },
                    modifier = Modifier.width(96.dp),
                    singleLine = true,
                )
                TextButton(onClick = { pending.remove(itemId) }) { Text("Remove") }
            }
            HorizontalDivider()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { pending.clear() }, modifier = Modifier.width(140.dp)) { Text("Cancel") }
        Button(
            onClick = {
                state.selectedInventoryId?.let { inventoryId ->
                    viewModel.recordChange(inventoryId, pending.toMap(), isAddition) { pending.clear() }
                }
            },
            enabled = selectedInventory != null && pending.isNotEmpty(),
            modifier = Modifier.width(140.dp),
        ) {
            Text("Confirm")
        }
    }
    if (scannerVisible) {
        QrScannerDialog(
            onDismiss = { scannerVisible = false },
            onCode = { code ->
                pending[code] = (pending[code] ?: 0) + 1
                scannerVisible = false
            },
        )
    }
}

@Composable
private fun InventoriesScreen(state: InventoryUiState, viewModel: InventoryViewModel) {
    var creatingInventory by remember { mutableStateOf(false) }
    Text("Inventories", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Button(onClick = { creatingInventory = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Create inventory")
    }
    Spacer(Modifier.height(12.dp))
    LazyColumn(modifier = Modifier.height(440.dp)) {
        items(state.inventories, key = Inventory::id) { inventory ->
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
                                Text("${item.name} (${item.id}) — ${item.quantity} units")
                            }
                        }
                    }
                }
            }
        }
    }
    if (creatingInventory) {
        NameDialog(
            title = "Create inventory",
            label = "Inventory name",
            onDismiss = { creatingInventory = false },
            onConfirm = {
                viewModel.createInventory(it)
                creatingInventory = false
            },
        )
    }
}

@Composable
private fun StickersScreen(state: InventoryUiState, viewModel: InventoryViewModel) {
    var addingItem by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<CatalogItem?>(null) }
    Text("Sticker catalog", style = MaterialTheme.typography.headlineSmall)
    Text("Create each box/item once, then generate a QR label from its ID.")
    Spacer(Modifier.height(8.dp))
    Button(onClick = { addingItem = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Add catalog item")
    }
    Spacer(Modifier.height(12.dp))
    LazyColumn(modifier = Modifier.height(440.dp)) {
        items(state.catalogItems, key = CatalogItem::id) { item ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text("${item.storage} • ${item.smNumber} • ${item.weightKg} kg")
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
            onConfirm = { name, storage, smNumber, weight ->
                viewModel.createItem(name, storage, smNumber, weight)
                addingItem = false
            },
        )
    }
    selectedItem?.let { QrStickerDialog(it, onDismiss = { selectedItem = null }) }
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, storage: String, smNumber: String, weight: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var storage by remember { mutableStateOf("") }
    var smNumber by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add catalog item") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(storage, { storage = it }, label = { Text("Storage name") }, singleLine = true)
                OutlinedTextField(smNumber, { smNumber = it }, label = { Text("SM number") }, singleLine = true)
                OutlinedTextField(weight, { weight = it }, label = { Text("Weight per unit (kg)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, storage, smNumber, weight) }) { Text("Add") } },
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
                Text("QR ID: ${item.id}")
                resultMessage?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { resultMessage = saveQrPng(context, item.id, bitmap) }) {
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
    centeredPaint.textSize = 82f
    canvas.drawText(item.name.uppercase(), width / 2f, 150f, centeredPaint)
    centeredPaint.textSize = 42f
    canvas.drawText(item.storage.uppercase(), width / 2f, 220f, centeredPaint)

    val qr = createQrBitmap(item.id, 250)
    canvas.drawBitmap(qr, 875f, 270f, null)

    val cornerPaint = Paint(centeredPaint).apply {
        textAlign = Paint.Align.LEFT
        textSize = 38f
    }
    canvas.drawText(item.smNumber.uppercase(), 70f, 610f, cornerPaint)
    cornerPaint.textAlign = Paint.Align.RIGHT
    canvas.drawText("Datum:__________________", width - 70f, 610f, cornerPaint)
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

private fun saveQrPng(context: Context, itemId: String, bitmap: Bitmap): String {
    return try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "qr-$itemId.png")
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

@Composable
private fun QrScannerDialog(onDismiss: () -> Unit, onCode: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan an item QR code") },
        text = { CameraScanner(onCode) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CameraScanner(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanned = remember { AtomicBoolean(false) }

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
                                if (scanned.compareAndSet(false, true)) latestOnCode(value)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
            }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
        cameraProviderFuture.addListener(setupCamera, ContextCompat.getMainExecutor(context))
        onDispose {
            if (cameraProviderFuture.isDone) cameraProviderFuture.get().unbindAll()
            cameraExecutor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(360.dp))
}

package com.example.lip_database

import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class CloudUiState(
    val workspaceId: String = "",
    val status: String = "Connecting to cloud…",
    val masterPinConfigured: Boolean = false,
    val isMasterUnlocked: Boolean = false,
    val unlockedSmNumber: String? = null,
) {
    val isLocked: Boolean get() = masterPinConfigured && !isMasterUnlocked && unlockedSmNumber == null
}

/**
 * A deliberately small Firestore replication layer.  The PINs are UX restrictions, not security:
 * Firestore rules permit every authenticated anonymous user in a shared workspace.
 */
class CloudSyncManager(
    private val database: InventoryDatabase,
    private val onStateChanged: (CloudUiState) -> Unit,
) {
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeners = mutableListOf<ListenerRegistration>()
    private var currentState = CloudUiState(workspaceId = workspaceId())
    private var syncJob: Job? = null

    fun start() {
        setState { it }
        scope.launch {
            try {
                if (auth.currentUser == null) auth.signInAnonymously().await()
                refreshSettings()
                synchronize()
                listenForRemoteChanges()
                startPeriodicSync()
                setState { it.copy(status = "Cloud sync active") }
            } catch (error: Exception) {
                setState { it.copy(status = "Cloud sync unavailable: ${error.message ?: "check connection"}") }
            }
        }
    }

    fun syncNow() {
        scope.launch {
            try {
                synchronize()
                setState { it.copy(status = "Cloud sync active") }
            } catch (error: Exception) {
                setState { it.copy(status = "Sync failed: ${error.message ?: "check connection"}") }
            }
        }
    }

    private fun startPeriodicSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (true) {
                delay(PERIODIC_SYNC_MILLIS)
                try {
                    synchronize()
                    setState { it.copy(status = "Cloud sync active") }
                } catch (_: Exception) {
                    setState { it.copy(status = "Cloud sync will retry automatically.") }
                }
            }
        }
    }

    fun setMasterPin(pin: String) {
        if (!isPin(pin)) {
            setState { it.copy(status = "PIN must be exactly four digits.") }
            return
        }
        scope.launch {
            try {
                val settings = settingsDocument()
                firestore.runTransaction { transaction ->
                    val existing = transaction.get(settings).getString("masterPinHash")
                    if (existing == null) transaction.set(settings, mapOf("masterPinHash" to hashPin(pin)))
                }.await()
                refreshSettings()
                setState { it.copy(isMasterUnlocked = true, unlockedSmNumber = null, status = "Master PIN configured.") }
            } catch (error: Exception) {
                setState { it.copy(status = "Could not configure master PIN.") }
            }
        }
    }

    fun unlock(pin: String) {
        if (!isPin(pin)) {
            setState { it.copy(status = "PIN must be exactly four digits.") }
            return
        }
        scope.launch {
            try {
                val settings = settingsDocument().get().await()
                val hash = hashPin(pin)
                val master = settings.getString("masterPinHash")
                val pins = settings.get("smPins") as? Map<*, *> ?: emptyMap<Any, Any>()
                when {
                    master == hash -> setState { it.copy(isMasterUnlocked = true, unlockedSmNumber = null, status = "Master access unlocked.") }
                    else -> {
                        val sm = pins.entries.firstOrNull { it.value == hash }?.key as? String
                        if (sm == null) setState { it.copy(status = "Incorrect PIN.") }
                        else setState { it.copy(isMasterUnlocked = false, unlockedSmNumber = sm, status = "SM $sm unlocked.") }
                    }
                }
            } catch (_: Exception) {
                setState { it.copy(status = "Could not unlock while offline.") }
            }
        }
    }

    fun setSmPin(smNumber: String, pin: String) {
        if (!currentState.isMasterUnlocked) {
            setState { it.copy(status = "Unlock with the master PIN first.") }
            return
        }
        if (smNumber.isBlank() || !isPin(pin)) {
            setState { it.copy(status = "Enter an SM number and exactly four PIN digits.") }
            return
        }
        scope.launch {
            try {
                settingsDocument().update(FieldPath.of("smPins", smNumber.trim()), hashPin(pin)).await()
                setState { it.copy(status = "PIN saved for SM ${smNumber.trim()}.") }
            } catch (_: Exception) {
                setState { it.copy(status = "Could not save SM PIN.") }
            }
        }
    }

    fun lock() = setState { it.copy(isMasterUnlocked = false, unlockedSmNumber = null, status = "Locked.") }

    fun allowedSmNumber(): String? = currentState.unlockedSmNumber

    fun publishLocalChanges(snapshot: InventorySnapshot) {
        scope.launch {
            try {
                uploadSnapshot(snapshot, pruneRemovedDocuments = true)
                setState { it.copy(status = "Cloud sync active") }
            } catch (error: Exception) {
                setState { it.copy(status = "Sync failed: ${error.message ?: "check connection"}") }
            }
        }
    }

    fun deleteCatalogItem(itemId: String, snapshot: InventorySnapshot) {
        scope.launch {
            try {
                workspaceDocument().collection("catalog").document(itemId).delete().await()
                uploadSnapshot(snapshot, pruneRemovedDocuments = true)
                setState { it.copy(status = "Cloud sync active") }
            } catch (error: Exception) {
                setState { it.copy(status = "Could not delete item from cloud: ${error.message ?: "check connection"}") }
            }
        }
    }

    private suspend fun synchronize() {
        val remote = readRemote()
        val local = database.snapshot()
        if (remote.isEmpty) {
            uploadSnapshot(local)
        } else {
            database.mergeRemote(remote.catalog, remote.inventories, remote.stock, remote.movements)
        }
        refreshSettings()
    }

    private suspend fun readRemote(): RemoteSnapshot {
        val root = workspaceDocument()
        val workspaceExists = root.get().await().exists()
        val catalog = root.collection("catalog").get().await().documents.mapNotNull { document ->
            val id = document.getString("id") ?: return@mapNotNull null
            CatalogItem(
                id,
                document.getString("name") ?: return@mapNotNull null,
                document.getString("itemCode") ?: "",
                document.getDouble("weightKg") ?: 0.0,
                document.getString("storage") ?: "",
                document.getString("smNumber") ?: "",
            )
        }
        val inventories = root.collection("inventories").get().await().documents.mapNotNull { it.getString("name") }
        val stock = root.collection("stock").get().await().documents.mapNotNull { document ->
            val inventory = document.getString("inventoryName") ?: return@mapNotNull null
            val itemId = document.getString("itemId") ?: return@mapNotNull null
            StockRecord(inventory, itemId, (document.getLong("quantity") ?: 0).toInt())
        }
        val movements = root.collection("movements").get().await().documents.mapNotNull { document ->
            val inventory = document.getString("inventoryName") ?: return@mapNotNull null
            val itemId = document.getString("itemId") ?: return@mapNotNull null
            MovementRecord(
                document.id, inventory, itemId, (document.getLong("quantity") ?: 0).toInt(),
                document.getString("action") ?: return@mapNotNull null,
                document.getLong("occurredAt") ?: 0L,
            )
        }
        return RemoteSnapshot(workspaceExists, catalog, inventories, stock, movements)
    }

    private suspend fun uploadSnapshot(snapshot: InventorySnapshot, pruneRemovedDocuments: Boolean = false) {
        val root = workspaceDocument()
        val batch = firestore.batch()
        batch.set(root, mapOf("updatedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
        snapshot.catalogItems.forEach { item ->
            batch.set(root.collection("catalog").document(item.id), mapOf(
                "id" to item.id, "name" to item.name, "itemCode" to item.itemCode, "weightKg" to item.weightKg,
                "storage" to item.storage, "smNumber" to item.smNumber,
            ))
        }
        snapshot.inventories.forEach { inventory ->
            batch.set(root.collection("inventories").document(documentId(inventory.name)), mapOf("name" to inventory.name))
        }
        snapshot.stock.forEach { record ->
            batch.set(root.collection("stock").document(documentId("${record.inventoryName}|${record.itemId}")), mapOf(
                "inventoryName" to record.inventoryName, "itemId" to record.itemId, "quantity" to record.quantity,
            ))
        }
        snapshot.movements.forEach { movement ->
            batch.set(root.collection("movements").document(movement.cloudId), mapOf(
                "inventoryName" to movement.inventoryName, "itemId" to movement.itemId,
                "quantity" to movement.quantity, "action" to movement.action, "occurredAt" to movement.occurredAt,
            ))
        }
        if (pruneRemovedDocuments) {
            val catalogIds = snapshot.catalogItems.mapTo(mutableSetOf()) { it.id }
            root.collection("catalog").get().await().documents
                .filter { it.id !in catalogIds }
                .forEach { batch.delete(it.reference) }
            val inventoryIds = snapshot.inventories.mapTo(mutableSetOf()) { documentId(it.name) }
            root.collection("inventories").get().await().documents
                .filter { it.id !in inventoryIds }
                .forEach { batch.delete(it.reference) }
            val stockIds = snapshot.stock.mapTo(mutableSetOf()) { documentId("${it.inventoryName}|${it.itemId}") }
            root.collection("stock").get().await().documents
                .filter { it.id !in stockIds }
                .forEach { batch.delete(it.reference) }
        }
        batch.commit().await()
    }

    private fun listenForRemoteChanges() {
        val root = workspaceDocument()
        listOf("catalog", "inventories", "stock", "movements").forEach { collection ->
            listeners += root.collection(collection).addSnapshotListener { _, error ->
                if (error == null) scheduleRemoteDownload()
            }
        }
    }

    private fun scheduleRemoteDownload() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            try {
                val remote = readRemote()
                database.mergeRemote(remote.catalog, remote.inventories, remote.stock, remote.movements)
                withContext(Dispatchers.Main) { onStateChanged(currentState) }
            } catch (_: Exception) {
                // The next listener event or explicit sync retries the transient network failure.
            }
        }
    }

    private suspend fun refreshSettings() {
        val settings = settingsDocument()
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(settings)
            val existingPins = snapshot.get("smPins") as? Map<*, *> ?: emptyMap<Any, Any>()
            val missingPins = DEFAULT_SM_NUMBERS
                .filterNot(existingPins::containsKey)
                .associateWith { smNumber -> hashPin(smNumber.padStart(4, '0')) }
            if (snapshot.getString("masterPinHash") == null || missingPins.isNotEmpty()) {
                transaction.set(
                    settings,
                    mapOf(
                        "masterPinHash" to (snapshot.getString("masterPinHash") ?: hashPin(DEFAULT_MASTER_PIN)),
                        "smPins" to missingPins,
                    ),
                    com.google.firebase.firestore.SetOptions.merge(),
                )
            }
        }.await()
        val configured = true
        setState { it.copy(masterPinConfigured = configured) }
    }

    private fun workspaceId(): String = COMPANY_WORKSPACE_ID

    private fun workspaceDocument() = firestore.collection("workspaces").document(workspaceId())
    private fun settingsDocument() = workspaceDocument().collection("metadata").document("settings")

    private fun setState(change: (CloudUiState) -> CloudUiState) {
        currentState = change(currentState)
        scope.launch(Dispatchers.Main) { onStateChanged(currentState) }
    }

    private data class RemoteSnapshot(
        val workspaceExists: Boolean,
        val catalog: List<CatalogItem>,
        val inventories: List<String>,
        val stock: List<StockRecord>,
        val movements: List<MovementRecord>,
    ) {
        val isEmpty: Boolean get() = !workspaceExists
    }

    companion object {
        private const val COMPANY_WORKSPACE_ID = "lip-database-company"
        private const val DEFAULT_MASTER_PIN = "0000"
        private const val PERIODIC_SYNC_MILLIS = 10_000L
        private val DEFAULT_SM_NUMBERS = listOf(
            "201", "202", "206", "210", "211", "212", "213", "214", "215", "216",
            "230", "2301", "233", "234", "235", "237", "238", "239", "261", "265",
            "266", "30", "501", "505", "516", "517", "519", "521", "522", "523",
            "60", "601", "61", "63", "68", "81", "86", "87", "98",
        )

        internal fun hashPin(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }

        internal fun documentId(value: String): String =
            Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP)

        private fun isPin(pin: String) = pin.matches(Regex("""\d{4}"""))
    }
}

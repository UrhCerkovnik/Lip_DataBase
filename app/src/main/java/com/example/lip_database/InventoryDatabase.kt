package com.example.lip_database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteConstraintException

data class Inventory(val id: Long, val name: String)

data class InventoryItem(
    val id: String,
    val name: String,
    val weightKg: Double,
    val storage: String,
    val smNumber: String,
    val quantity: Int,
    val lastAddedAt: Long?,
)

data class CatalogItem(
    val id: String,
    val name: String,
    val weightKg: Double,
    val storage: String,
    val smNumber: String,
)

data class StockRecord(val inventoryName: String, val itemId: String, val quantity: Int)

data class MovementRecord(
    val cloudId: String,
    val inventoryName: String,
    val itemId: String,
    val quantity: Int,
    val action: String,
    val occurredAt: Long,
)

data class InventorySnapshot(
    val inventories: List<Inventory>,
    val catalogItems: List<CatalogItem>,
    val stock: List<StockRecord>,
    val movements: List<MovementRecord>,
)

class InventoryDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE inventories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE items (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                weight_kg REAL NOT NULL,
                origin TEXT NOT NULL,
                sm_number TEXT NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE stock (
                inventory_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                quantity INTEGER NOT NULL CHECK (quantity >= 0),
                PRIMARY KEY (inventory_id, item_id),
                FOREIGN KEY (inventory_id) REFERENCES inventories(id),
                FOREIGN KEY (item_id) REFERENCES items(id)
            )
            """.trimIndent(),
        )
        createStockMovementsTable(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            database.execSQL("ALTER TABLE items ADD COLUMN sm_number TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 3) {
            createStockMovementsTable(database)
        }
        if (oldVersion < 4) {
            database.execSQL("ALTER TABLE stock_movements ADD COLUMN cloud_id TEXT")
            database.execSQL("UPDATE stock_movements SET cloud_id = 'legacy-' || id WHERE cloud_id IS NULL")
            database.execSQL("CREATE UNIQUE INDEX stock_movements_cloud_id ON stock_movements(cloud_id)")
        }
    }

    fun inventories(): List<Inventory> = readableDatabase.rawQuery(
        "SELECT id, name FROM inventories ORDER BY name COLLATE NOCASE",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Inventory(cursor.getLong(0), cursor.getString(1)))
        }
    }

    fun catalogItems(): List<CatalogItem> = readableDatabase.rawQuery(
        "SELECT id, name, weight_kg, origin, sm_number FROM items ORDER BY name COLLATE NOCASE",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    CatalogItem(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getDouble(2),
                        cursor.getString(3),
                        cursor.getString(4),
                    ),
                )
            }
        }
    }

    fun stock(inventoryId: Long): List<InventoryItem> = readableDatabase.rawQuery(
        """
        SELECT
            i.id,
            i.name,
            i.weight_kg,
            i.origin,
            i.sm_number,
            s.quantity,
            (
                SELECT MAX(m.occurred_at)
                FROM stock_movements m
                WHERE m.inventory_id = s.inventory_id
                    AND m.item_id = s.item_id
                    AND m.action = 'ADD'
            )
        FROM stock s
        JOIN items i ON i.id = s.item_id
        WHERE s.inventory_id = ? AND s.quantity > 0
        ORDER BY i.name COLLATE NOCASE
        """.trimIndent(),
        arrayOf(inventoryId.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    InventoryItem(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getDouble(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getInt(5),
                        cursor.takeIf { !it.isNull(6) }?.getLong(6),
                    ),
                )
            }
        }
    }

    fun addInventory(name: String): String? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return "Enter an inventory name."
        return try {
            writableDatabase.insertOrThrow("inventories", null, ContentValues().apply {
                put("name", trimmedName)
            })
            null
        } catch (_: SQLiteConstraintException) {
            "An inventory with that name already exists."
        }
    }

    fun addItem(name: String, storage: String, smNumber: String, weightKg: Double): String? {
        if (name.isBlank() || storage.isBlank()) {
            return "Name and storage are required."
        }
        if (weightKg < 0) return "Weight cannot be negative."
        val itemId = ItemIdGenerator.create(name, storage, smNumber, weightKg)
        val inventoryName = inventoryName(storage, smNumber)
        val database = writableDatabase
        database.beginTransaction()
        return try {
            if (smNumber.isNotBlank()) {
                database.insertWithOnConflict(
                    "inventories",
                    null,
                    ContentValues().apply { put("name", inventoryName) },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            database.insertOrThrow("items", null, ContentValues().apply {
                put("id", itemId)
                put("name", name.trim())
                put("weight_kg", weightKg)
                put("origin", storage.trim())
                put("sm_number", smNumber.trim())
            })
            database.setTransactionSuccessful()
            null
        } catch (_: SQLiteConstraintException) {
            "Could not create a unique item ID. Try again."
        } finally {
            database.endTransaction()
        }
    }

    fun deleteItem(id: String): String? {
        val inStock = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM stock WHERE item_id = ? AND quantity > 0",
            arrayOf(id),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) > 0 }
        if (inStock) return "Remove this item from every inventory before deleting it."
        writableDatabase.delete("items", "id = ?", arrayOf(id))
        return null
    }

    fun deleteEmptyInventory(inventoryId: Long): String? {
        val hasStock = readableDatabase.rawQuery(
            "SELECT 1 FROM stock WHERE inventory_id = ? AND quantity > 0 LIMIT 1",
            arrayOf(inventoryId.toString()),
        ).use { it.moveToFirst() }
        if (hasStock) return "Only empty inventories can be deleted."
        writableDatabase.delete("inventories", "id = ?", arrayOf(inventoryId.toString()))
        return null
    }

    fun applyStockChange(inventoryId: Long, quantities: Map<String, Int>, isAddition: Boolean): String? {
        if (quantities.isEmpty()) return "Scan or add at least one item."
        val database = writableDatabase
        database.beginTransaction()
        return try {
            for ((itemId, quantity) in quantities) {
                if (quantity <= 0) return "Each item quantity must be at least one."
                val exists = database.rawQuery(
                    "SELECT 1 FROM items WHERE id = ?",
                    arrayOf(itemId),
                ).use { it.moveToFirst() }
                if (!exists) return "A scanned QR code does not match any catalog item."

                val currentQuantity = database.rawQuery(
                    "SELECT quantity FROM stock WHERE inventory_id = ? AND item_id = ?",
                    arrayOf(inventoryId.toString(), itemId),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                val updatedQuantity = if (isAddition) currentQuantity + quantity else currentQuantity - quantity
                if (updatedQuantity < 0) {
                    return "Cannot remove $quantity item(s); only $currentQuantity are stored."
                }

                if (updatedQuantity == 0) {
                    database.delete(
                        "stock",
                        "inventory_id = ? AND item_id = ?",
                        arrayOf(inventoryId.toString(), itemId),
                    )
                } else {
                    database.insertWithOnConflict(
                        "stock",
                        null,
                        ContentValues().apply {
                            put("inventory_id", inventoryId)
                            put("item_id", itemId)
                            put("quantity", updatedQuantity)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                database.insertOrThrow("stock_movements", null, ContentValues().apply {
                    put("cloud_id", java.util.UUID.randomUUID().toString())
                    put("inventory_id", inventoryId)
                    put("item_id", itemId)
                    put("quantity", quantity)
                    put("action", if (isAddition) ACTION_ADD else ACTION_REMOVE)
                    put("occurred_at", System.currentTimeMillis())
                })
            }
            database.setTransactionSuccessful()
            null
        } finally {
            database.endTransaction()
        }
    }

    fun snapshot(): InventorySnapshot {
        val inventories = inventories()
        val catalog = catalogItems()
        val stock = readableDatabase.rawQuery(
            """
            SELECT inventories.name, stock.item_id, stock.quantity
            FROM stock JOIN inventories ON inventories.id = stock.inventory_id
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(StockRecord(cursor.getString(0), cursor.getString(1), cursor.getInt(2)))
            }
        }
        val movements = readableDatabase.rawQuery(
            """
            SELECT m.cloud_id, inventories.name, m.item_id, m.quantity, m.action, m.occurred_at
            FROM stock_movements m JOIN inventories ON inventories.id = m.inventory_id
            WHERE m.cloud_id IS NOT NULL
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(MovementRecord(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getInt(3),
                        cursor.getString(4),
                        cursor.getLong(5),
                    ))
                }
            }
        }
        return InventorySnapshot(inventories, catalog, stock, movements)
    }

    /** Remote stock is authoritative for records it contains; local-only records stay intact. */
    fun mergeRemote(
        remoteCatalog: List<CatalogItem>,
        remoteInventories: List<String>,
        remoteStock: List<StockRecord>,
        remoteMovements: List<MovementRecord>,
    ) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            remoteInventories.forEach { name ->
                database.insertWithOnConflict(
                    "inventories", null, ContentValues().apply { put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            remoteCatalog.forEach { item ->
                database.insertWithOnConflict("items", null, ContentValues().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("weight_kg", item.weightKg)
                    put("origin", item.storage)
                    put("sm_number", item.smNumber)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            remoteStock.forEach { record ->
                val inventoryId = inventoryId(database, record.inventoryName) ?: return@forEach
                database.insertWithOnConflict("stock", null, ContentValues().apply {
                    put("inventory_id", inventoryId)
                    put("item_id", record.itemId)
                    put("quantity", record.quantity)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            remoteMovements.forEach { movement ->
                val inventoryId = inventoryId(database, movement.inventoryName) ?: return@forEach
                database.insertWithOnConflict("stock_movements", null, ContentValues().apply {
                    put("cloud_id", movement.cloudId)
                    put("inventory_id", inventoryId)
                    put("item_id", movement.itemId)
                    put("quantity", movement.quantity)
                    put("action", movement.action)
                    put("occurred_at", movement.occurredAt)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "inventory.db"
        private const val DATABASE_VERSION = 4
        private const val ACTION_ADD = "ADD"
        private const val ACTION_REMOVE = "REMOVE"

        private fun createStockMovementsTable(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE stock_movements (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    cloud_id TEXT UNIQUE,
                    inventory_id INTEGER NOT NULL,
                    item_id TEXT NOT NULL,
                    quantity INTEGER NOT NULL,
                    action TEXT NOT NULL CHECK (action IN ('ADD', 'REMOVE')),
                    occurred_at INTEGER NOT NULL,
                    FOREIGN KEY (inventory_id) REFERENCES inventories(id),
                    FOREIGN KEY (item_id) REFERENCES items(id)
                )
                """.trimIndent(),
            )
        }

        fun inventoryName(storage: String, smNumber: String): String =
            "${storage.trim()} - ${smNumber.trim()}"

        private fun inventoryId(database: SQLiteDatabase, name: String): Long? =
            database.rawQuery("SELECT id FROM inventories WHERE name = ?", arrayOf(name)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
    }
}

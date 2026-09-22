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
    val origin: String,
    val quantity: Int,
)

data class CatalogItem(
    val id: String,
    val name: String,
    val weightKg: Double,
    val origin: String,
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
                origin TEXT NOT NULL
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
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun inventories(): List<Inventory> = readableDatabase.rawQuery(
        "SELECT id, name FROM inventories ORDER BY name COLLATE NOCASE",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Inventory(cursor.getLong(0), cursor.getString(1)))
        }
    }

    fun catalogItems(): List<CatalogItem> = readableDatabase.rawQuery(
        "SELECT id, name, weight_kg, origin FROM items ORDER BY name COLLATE NOCASE",
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(CatalogItem(cursor.getString(0), cursor.getString(1), cursor.getDouble(2), cursor.getString(3)))
            }
        }
    }

    fun stock(inventoryId: Long): List<InventoryItem> = readableDatabase.rawQuery(
        """
        SELECT i.id, i.name, i.weight_kg, i.origin, s.quantity
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
                        cursor.getInt(4),
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

    fun addItem(id: String, name: String, weightKg: Double, origin: String): String? {
        if (id.isBlank() || name.isBlank() || origin.isBlank()) return "ID, name, and origin are required."
        if (weightKg < 0) return "Weight cannot be negative."
        return try {
            writableDatabase.insertOrThrow("items", null, ContentValues().apply {
                put("id", id.trim())
                put("name", name.trim())
                put("weight_kg", weightKg)
                put("origin", origin.trim())
            })
            null
        } catch (_: SQLiteConstraintException) {
            "An item with ID ${id.trim()} already exists."
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
                if (!exists) return "No catalog item has ID $itemId."

                val currentQuantity = database.rawQuery(
                    "SELECT quantity FROM stock WHERE inventory_id = ? AND item_id = ?",
                    arrayOf(inventoryId.toString(), itemId),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                val updatedQuantity = if (isAddition) currentQuantity + quantity else currentQuantity - quantity
                if (updatedQuantity < 0) return "Cannot remove $quantity of $itemId; only $currentQuantity is stored."

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
            }
            database.setTransactionSuccessful()
            null
        } finally {
            database.endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "inventory.db"
        private const val DATABASE_VERSION = 1
    }
}

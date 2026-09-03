package com.chk.binancebybit

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class OrderBookHunterDb(context: Context) : SQLiteOpenHelper(context.applicationContext, "chk_orderbook_hunter.db", null, 1) {
    data class Watch(val symbol: String, val alerts: Boolean, val restore: Boolean, val updatedAt: Long)
    data class Note(val id: String, val symbol: String, val text: String, val author: String, val createdAt: Long)

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE watch(symbol TEXT PRIMARY KEY, enabled INTEGER NOT NULL, alerts INTEGER NOT NULL, restore INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE event(id TEXT PRIMARY KEY, symbol TEXT NOT NULL, type TEXT NOT NULL, side TEXT, price REAL NOT NULL, qty REAL NOT NULL, old_price REAL NOT NULL, new_price REAL NOT NULL, score INTEGER NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX event_symbol_time ON event(symbol,created_at DESC)")
        db.execSQL("CREATE TABLE wall(id TEXT PRIMARY KEY, symbol TEXT NOT NULL, side TEXT NOT NULL, initial_price REAL NOT NULL, current_price REAL NOT NULL, initial_qty REAL NOT NULL, current_qty REAL NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, move_count INTEGER NOT NULL, cancel_count INTEGER NOT NULL, executed_estimate REAL NOT NULL, min_qty REAL NOT NULL, refill_count INTEGER NOT NULL, last_missing_at INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX wall_symbol_time ON wall(symbol,last_seen DESC)")
        db.execSQL("CREATE TABLE score(id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL, score INTEGER NOT NULL, classification TEXT NOT NULL, last_price REAL NOT NULL, spread REAL NOT NULL, buy_pressure REAL NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX score_symbol_time ON score(symbol,created_at DESC)")
        db.execSQL("CREATE TABLE note(id TEXT PRIMARY KEY, symbol TEXT NOT NULL, text TEXT NOT NULL, author TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX note_symbol_time ON note(symbol,created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun watch(symbolValue: String, enabled: Boolean, alerts: Boolean = true, restore: Boolean = true) {
        val symbol = OrderBookHunterStore.normalizeSymbol(symbolValue)
        val v = ContentValues().apply {
            put("symbol", symbol); put("enabled", if (enabled) 1 else 0); put("alerts", if (alerts) 1 else 0)
            put("restore", if (restore) 1 else 0); put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("watch", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun setAlerts(symbolValue: String, enabled: Boolean) {
        val v = ContentValues().apply { put("alerts", if (enabled) 1 else 0); put("updated_at", System.currentTimeMillis()) }
        writableDatabase.update("watch", v, "symbol=?", arrayOf(OrderBookHunterStore.normalizeSymbol(symbolValue)))
    }

    fun watches(restoreOnly: Boolean = false): List<Watch> {
        val out = ArrayList<Watch>()
        val where = if (restoreOnly) "enabled=1 AND restore=1" else "enabled=1"
        readableDatabase.query("watch", null, where, null, null, null, "updated_at DESC").use { c ->
            while (c.moveToNext()) out += Watch(
                c.getString(c.getColumnIndexOrThrow("symbol")), c.getInt(c.getColumnIndexOrThrow("alerts")) == 1,
                c.getInt(c.getColumnIndexOrThrow("restore")) == 1, c.getLong(c.getColumnIndexOrThrow("updated_at"))
            )
        }
        return out
    }

    fun isWatching(symbolValue: String): Boolean {
        readableDatabase.query("watch", arrayOf("enabled"), "symbol=?", arrayOf(OrderBookHunterStore.normalizeSymbol(symbolValue)), null, null, null, "1").use { c ->
            return c.moveToFirst() && c.getInt(0) == 1
        }
    }

    fun alertsEnabled(symbolValue: String): Boolean {
        readableDatabase.query("watch", arrayOf("alerts"), "symbol=?", arrayOf(OrderBookHunterStore.normalizeSymbol(symbolValue)), null, null, null, "1").use { c ->
            return !c.moveToFirst() || c.getInt(0) == 1
        }
    }

    fun saveWall(w: HunterWallTrack) {
        val v = ContentValues().apply {
            put("id", w.id); put("symbol", w.symbol); put("side", w.side.name); put("initial_price", w.initialPrice); put("current_price", w.currentPrice)
            put("initial_qty", w.initialQty); put("current_qty", w.currentQty); put("first_seen", w.firstSeen); put("last_seen", w.lastSeen)
            put("move_count", w.moveCount); put("cancel_count", w.cancelCount); put("executed_estimate", w.executedEstimate); put("min_qty", w.minObservedQty)
            put("refill_count", w.refillCount); put("last_missing_at", w.lastMissingAt); put("status", w.status.name)
        }
        writableDatabase.insertWithOnConflict("wall", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addEvent(e: HunterEvent) {
        val v = ContentValues().apply {
            put("id", e.id); put("symbol", e.symbol); put("type", e.type.name); put("side", e.side?.name); put("price", e.price); put("qty", e.qty)
            put("old_price", e.oldPrice); put("new_price", e.newPrice); put("score", e.score); put("detail", e.detail); put("created_at", e.createdAt)
        }
        writableDatabase.insertWithOnConflict("event", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun events(symbolValue: String, limit: Int = 100, since: Long = 0L): List<HunterEvent> {
        val symbol = OrderBookHunterStore.normalizeSymbol(symbolValue)
        val where = if (since > 0) "symbol=? AND created_at>=?" else "symbol=?"
        val args = if (since > 0) arrayOf(symbol, since.toString()) else arrayOf(symbol)
        val out = ArrayList<HunterEvent>()
        readableDatabase.query("event", null, where, args, null, null, "created_at DESC", limit.coerceIn(1, 1000).toString()).use { c ->
            while (c.moveToNext()) out += HunterEvent(
                id = c.getString(c.getColumnIndexOrThrow("id")), symbol = symbol,
                type = runCatching { HunterEventType.valueOf(c.getString(c.getColumnIndexOrThrow("type"))) }.getOrDefault(HunterEventType.LARGE_WALL),
                side = c.getString(c.getColumnIndexOrThrow("side"))?.let { runCatching { HunterWallSide.valueOf(it) }.getOrNull() },
                price = c.getDouble(c.getColumnIndexOrThrow("price")), qty = c.getDouble(c.getColumnIndexOrThrow("qty")),
                oldPrice = c.getDouble(c.getColumnIndexOrThrow("old_price")), newPrice = c.getDouble(c.getColumnIndexOrThrow("new_price")),
                score = c.getInt(c.getColumnIndexOrThrow("score")), detail = c.getString(c.getColumnIndexOrThrow("detail")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
            )
        }
        return out
    }

    fun addScore(s: HunterStatus) {
        val pressure = s.imbalances.minByOrNull { kotlin.math.abs(it.distancePercent - 1.0) }?.buyPressure ?: 50.0
        val v = ContentValues().apply {
            put("symbol", s.symbol); put("score", s.anomalyScore); put("classification", s.classification); put("last_price", s.lastPrice)
            put("spread", s.spreadPercent); put("buy_pressure", pressure); put("created_at", s.updatedAt)
        }
        writableDatabase.insert("score", null, v)
    }

    fun note(symbolValue: String, text: String, author: String = "USER"): Note {
        val n = Note(UUID.randomUUID().toString(), OrderBookHunterStore.normalizeSymbol(symbolValue), text.trim().take(4000), author.take(40), System.currentTimeMillis())
        val v = ContentValues().apply { put("id", n.id); put("symbol", n.symbol); put("text", n.text); put("author", n.author); put("created_at", n.createdAt) }
        writableDatabase.insert("note", null, v)
        addEvent(HunterEvent("note-${n.id}", n.symbol, HunterEventType.USER_NOTE, detail = "${n.author} : ${n.text}", createdAt = n.createdAt))
        return n
    }

    fun notes(symbolValue: String, limit: Int = 100): List<Note> {
        val symbol = OrderBookHunterStore.normalizeSymbol(symbolValue)
        val out = ArrayList<Note>()
        readableDatabase.query("note", null, "symbol=?", arrayOf(symbol), null, null, "created_at DESC", limit.coerceIn(1, 500).toString()).use { c ->
            while (c.moveToNext()) out += Note(c.getString(0), symbol, c.getString(2), c.getString(3), c.getLong(4))
        }
        return out
    }

    fun clear(symbolValue: String, includeNotes: Boolean = false) {
        val symbol = OrderBookHunterStore.normalizeSymbol(symbolValue)
        writableDatabase.delete("wall", "symbol=?", arrayOf(symbol)); writableDatabase.delete("event", "symbol=?", arrayOf(symbol)); writableDatabase.delete("score", "symbol=?", arrayOf(symbol))
        if (includeNotes) writableDatabase.delete("note", "symbol=?", arrayOf(symbol))
    }

    fun cleanup() {
        val now = System.currentTimeMillis()
        writableDatabase.delete("event", "created_at<?", arrayOf((now - 7L * 86400000L).toString()))
        writableDatabase.delete("wall", "last_seen<? AND status<>'ACTIVE'", arrayOf((now - 3L * 86400000L).toString()))
        writableDatabase.delete("score", "created_at<?", arrayOf((now - 7L * 86400000L).toString()))
    }
}

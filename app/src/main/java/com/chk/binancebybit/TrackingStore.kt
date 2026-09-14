package com.chk.binancebybit

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/**
 * Local-first persistence for Wall Tracker.
 * Raw order-book traffic never leaves the phone and is not persisted. Only useful wall events,
 * wall-related trades, cross-exchange matches, connection gaps and user notes are stored.
 */
data class TrackingWall(
    val id: String,
    val asset: String,
    val exchange: String,
    val symbol: String,
    val side: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val initialPrice: Double,
    val currentPrice: Double,
    val initialQty: Double,
    val currentQty: Double,
    val executedQty: Double,
    val cancelledQty: Double,
    val strength: Double,
    val moves: Int,
    val replenishments: Int,
    val status: String,
    val singleActorProbability: Int,
    val multiTraderProbability: Int,
    val indeterminateProbability: Int,
    val lastEvent: String
) {
    fun toJson(now: Long = System.currentTimeMillis()): JSONObject = JSONObject().apply {
        put("id", id)
        put("asset", asset)
        put("exchange", exchange)
        put("symbol", symbol)
        put("side", side)
        put("firstSeen", firstSeen)
        put("lastSeen", lastSeen)
        put("ageMs", max(0L, now - firstSeen))
        put("initialPrice", initialPrice)
        put("price", currentPrice)
        put("initialQuantity", initialQty)
        put("quantity", currentQty)
        put("executedQuantity", executedQty)
        put("cancelledQuantity", cancelledQty)
        put("strength", strength)
        put("moves", moves)
        put("replenishments", replenishments)
        put("status", status)
        put("singleActorProbability", singleActorProbability)
        put("multiTraderProbability", multiTraderProbability)
        put("indeterminateProbability", indeterminateProbability)
        put("lastEvent", lastEvent)
    }
}

data class TrackingNote(
    val id: Long,
    val asset: String,
    val wallId: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("asset", asset)
        put("wallId", wallId)
        put("content", content)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }
}

class TrackingStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val workspace = app.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE walls(
              id TEXT PRIMARY KEY,
              asset TEXT NOT NULL,
              exchange TEXT NOT NULL,
              symbol TEXT NOT NULL,
              side TEXT NOT NULL,
              first_seen INTEGER NOT NULL,
              last_seen INTEGER NOT NULL,
              initial_price REAL NOT NULL,
              current_price REAL NOT NULL,
              initial_qty REAL NOT NULL,
              current_qty REAL NOT NULL,
              executed_qty REAL NOT NULL DEFAULT 0,
              cancelled_qty REAL NOT NULL DEFAULT 0,
              strength REAL NOT NULL DEFAULT 0,
              moves INTEGER NOT NULL DEFAULT 0,
              replenishments INTEGER NOT NULL DEFAULT 0,
              status TEXT NOT NULL,
              single_score INTEGER NOT NULL DEFAULT 0,
              multi_score INTEGER NOT NULL DEFAULT 0,
              indeterminate_score INTEGER NOT NULL DEFAULT 100,
              last_event TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_walls_active ON walls(status,last_seen)")
        db.execSQL("CREATE INDEX idx_walls_asset ON walls(asset,last_seen)")
        db.execSQL("""
            CREATE TABLE wall_events(
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              wall_id TEXT NOT NULL,
              asset TEXT NOT NULL,
              exchange TEXT NOT NULL,
              side TEXT NOT NULL,
              event_type TEXT NOT NULL,
              price REAL NOT NULL,
              qty REAL NOT NULL,
              detail TEXT NOT NULL DEFAULT '',
              at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_wall_events_at ON wall_events(at DESC)")
        db.execSQL("CREATE INDEX idx_wall_events_wall ON wall_events(wall_id,at DESC)")
        db.execSQL("""
            CREATE TABLE wall_trades(
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              wall_id TEXT NOT NULL,
              exchange TEXT NOT NULL,
              symbol TEXT NOT NULL,
              side TEXT NOT NULL,
              price REAL NOT NULL,
              qty REAL NOT NULL,
              at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_wall_trades_wall ON wall_trades(wall_id,at DESC)")
        db.execSQL("""
            CREATE TABLE cross_matches(
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              asset TEXT NOT NULL,
              wall_a TEXT NOT NULL,
              wall_b TEXT NOT NULL,
              exchange_a TEXT NOT NULL,
              exchange_b TEXT NOT NULL,
              side TEXT NOT NULL,
              quantity_similarity REAL NOT NULL,
              price_similarity REAL NOT NULL,
              score INTEGER NOT NULL,
              at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_cross_matches_at ON cross_matches(at DESC)")
        db.execSQL("""
            CREATE TABLE connection_gaps(
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              started_at INTEGER NOT NULL,
              ended_at INTEGER NOT NULL DEFAULT 0,
              reason TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_connection_gaps_at ON connection_gaps(started_at DESC)")
        db.execSQL("""
            CREATE TABLE tracking_notes(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              asset TEXT NOT NULL DEFAULT '',
              wall_id TEXT NOT NULL DEFAULT '',
              content TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_tracking_notes_at ON tracking_notes(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First version of the dedicated tracking DB. Future migrations remain additive.
    }

    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun setEnabled(value: Boolean) = prefs.edit().putBoolean("enabled", value).apply()
    fun minWallNotional(): Double = prefs.getFloat("min_wall_notional", 5_000f).toDouble().coerceIn(250.0, 1_000_000.0)
    fun minWallStrength(): Double = prefs.getFloat("min_wall_strength", 4f).toDouble().coerceIn(1.5, 50.0)
    fun setMinWallNotional(value: Double) = prefs.edit().putFloat("min_wall_notional", value.coerceIn(250.0, 1_000_000.0).toFloat()).apply()
    fun setMinWallStrength(value: Double) = prefs.edit().putFloat("min_wall_strength", value.coerceIn(1.5, 50.0).toFloat()).apply()
    fun lastRemotePushAt(): Long = prefs.getLong("last_remote_push_at", 0L)
    fun setLastRemotePushAt(value: Long) = prefs.edit().putLong("last_remote_push_at", value).apply()

    /** Only non-stable assets actually present in either cached portfolio are tracked. */
    fun heldAssets(): Set<String> {
        val out = linkedSetOf<String>()
        listOf("BINANCE" to "v4_snapshot_binance", "BYBIT" to "v4_snapshot_bybit").forEach { (exchange, key) ->
            val raw = workspace.getString(key, null)
                ?: if (exchange == "BINANCE") workspace.getString("last_snapshot", null) else workspace.getString("bybit_last_snapshot", null)
                ?: return@forEach
            runCatching {
                val a = JSONObject(raw).optJSONArray("holdings") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val h = a.optJSONObject(i) ?: continue
                    val asset = h.optString("asset").uppercase(Locale.US).trim()
                    val amount = h.optDouble("amount", 0.0)
                    if (amount > 0.0 && asset.matches(Regex("^[A-Z0-9]{2,16}$")) && asset !in STABLES) out += asset
                }
            }
        }
        return out
    }

    fun upsertWall(w: TrackingWall) {
        writableDatabase.insertWithOnConflict("walls", null, ContentValues().apply {
            put("id", w.id); put("asset", w.asset); put("exchange", w.exchange); put("symbol", w.symbol); put("side", w.side)
            put("first_seen", w.firstSeen); put("last_seen", w.lastSeen); put("initial_price", w.initialPrice); put("current_price", w.currentPrice)
            put("initial_qty", w.initialQty); put("current_qty", w.currentQty); put("executed_qty", w.executedQty); put("cancelled_qty", w.cancelledQty)
            put("strength", w.strength); put("moves", w.moves); put("replenishments", w.replenishments); put("status", w.status)
            put("single_score", w.singleActorProbability); put("multi_score", w.multiTraderProbability); put("indeterminate_score", w.indeterminateProbability)
            put("last_event", w.lastEvent)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun wallEvent(w: TrackingWall, type: String, detail: String = "", at: Long = System.currentTimeMillis()) {
        writableDatabase.insert("wall_events", null, ContentValues().apply {
            put("wall_id", w.id); put("asset", w.asset); put("exchange", w.exchange); put("side", w.side)
            put("event_type", type); put("price", w.currentPrice); put("qty", w.currentQty); put("detail", detail.take(500)); put("at", at)
        })
    }

    fun wallTrade(w: TrackingWall, price: Double, qty: Double, at: Long) {
        writableDatabase.insert("wall_trades", null, ContentValues().apply {
            put("wall_id", w.id); put("exchange", w.exchange); put("symbol", w.symbol); put("side", w.side)
            put("price", price); put("qty", qty); put("at", at)
        })
    }

    fun addCrossMatch(a: TrackingWall, b: TrackingWall, qtySimilarity: Double, priceSimilarity: Double, score: Int, at: Long = System.currentTimeMillis()) {
        writableDatabase.insert("cross_matches", null, ContentValues().apply {
            put("asset", a.asset); put("wall_a", a.id); put("wall_b", b.id); put("exchange_a", a.exchange); put("exchange_b", b.exchange)
            put("side", a.side); put("quantity_similarity", qtySimilarity); put("price_similarity", priceSimilarity); put("score", score); put("at", at)
        })
    }

    fun openGap(reason: String): Long {
        val last = readableDatabase.rawQuery("SELECT seq FROM connection_gaps WHERE ended_at=0 ORDER BY seq DESC LIMIT 1", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        if (last > 0L) return last
        return writableDatabase.insert("connection_gaps", null, ContentValues().apply {
            put("started_at", System.currentTimeMillis()); put("ended_at", 0L); put("reason", reason.take(200))
        })
    }

    fun closeGap() {
        writableDatabase.execSQL("UPDATE connection_gaps SET ended_at=? WHERE ended_at=0", arrayOf(System.currentTimeMillis()))
    }

    fun addNote(asset: String, wallId: String, content: String): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert("tracking_notes", null, ContentValues().apply {
            put("asset", asset.uppercase(Locale.US).take(16)); put("wall_id", wallId.take(80)); put("content", content.take(4000)); put("created_at", now); put("updated_at", now)
        })
    }

    fun updateNote(id: Long, content: String): Boolean {
        return writableDatabase.update("tracking_notes", ContentValues().apply {
            put("content", content.take(4000)); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString())) > 0
    }

    fun deleteNote(id: Long): Boolean = writableDatabase.delete("tracking_notes", "id=?", arrayOf(id.toString())) > 0

    fun notes(limit: Int = 50): List<TrackingNote> = readableDatabase.rawQuery(
        "SELECT id,asset,wall_id,content,created_at,updated_at FROM tracking_notes ORDER BY updated_at DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 200).toString())
    ).use { c -> buildList { while (c.moveToNext()) add(noteFrom(c)) } }

    fun activeWalls(limit: Int = 100): List<TrackingWall> = readableDatabase.rawQuery(
        "SELECT * FROM walls WHERE status='ACTIVE' ORDER BY strength DESC,last_seen DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 300).toString())
    ).use { c -> buildList { while (c.moveToNext()) add(wallFrom(c)) } }

    fun recentWalls(limit: Int = 100): List<TrackingWall> = readableDatabase.rawQuery(
        "SELECT * FROM walls ORDER BY last_seen DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 300).toString())
    ).use { c -> buildList { while (c.moveToNext()) add(wallFrom(c)) } }

    fun recentEvents(limit: Int = 80): JSONArray = JSONArray().apply {
        readableDatabase.rawQuery(
            "SELECT wall_id,asset,exchange,side,event_type,price,qty,detail,at FROM wall_events ORDER BY at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 250).toString())
        ).use { c ->
            while (c.moveToNext()) put(JSONObject().apply {
                put("wallId", c.getString(0)); put("asset", c.getString(1)); put("exchange", c.getString(2)); put("side", c.getString(3))
                put("event", c.getString(4)); put("price", c.getDouble(5)); put("quantity", c.getDouble(6)); put("detail", c.getString(7)); put("at", c.getLong(8))
            })
        }
    }

    fun recentMatches(limit: Int = 40): JSONArray = JSONArray().apply {
        readableDatabase.rawQuery(
            "SELECT asset,wall_a,wall_b,exchange_a,exchange_b,side,quantity_similarity,price_similarity,score,at FROM cross_matches ORDER BY at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100).toString())
        ).use { c ->
            while (c.moveToNext()) put(JSONObject().apply {
                put("asset", c.getString(0)); put("wallA", c.getString(1)); put("wallB", c.getString(2)); put("exchangeA", c.getString(3)); put("exchangeB", c.getString(4))
                put("side", c.getString(5)); put("quantitySimilarity", c.getDouble(6)); put("priceSimilarity", c.getDouble(7)); put("score", c.getInt(8)); put("at", c.getLong(9))
            })
        }
    }

    fun recentGaps(limit: Int = 20): JSONArray = JSONArray().apply {
        readableDatabase.rawQuery(
            "SELECT started_at,ended_at,reason FROM connection_gaps ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100).toString())
        ).use { c -> while (c.moveToNext()) put(JSONObject().apply {
            put("startedAt", c.getLong(0)); put("endedAt", c.getLong(1)); put("reason", c.getString(2))
        }) }
    }

    fun cleanup(now: Long = System.currentTimeMillis()) {
        val eventsCut = now - 90L * 24 * 60 * 60 * 1000
        val tradesCut = now - 7L * 24 * 60 * 60 * 1000
        writableDatabase.delete("wall_events", "at<?", arrayOf(eventsCut.toString()))
        writableDatabase.delete("wall_trades", "at<?", arrayOf(tradesCut.toString()))
        writableDatabase.delete("cross_matches", "at<?", arrayOf(eventsCut.toString()))
        writableDatabase.delete("connection_gaps", "ended_at>0 AND ended_at<?", arrayOf(eventsCut.toString()))
        writableDatabase.delete("walls", "status<>'ACTIVE' AND last_seen<?", arrayOf(eventsCut.toString()))
    }

    private fun wallFrom(c: Cursor): TrackingWall {
        fun i(name: String) = c.getColumnIndexOrThrow(name)
        return TrackingWall(
            c.getString(i("id")), c.getString(i("asset")), c.getString(i("exchange")), c.getString(i("symbol")), c.getString(i("side")),
            c.getLong(i("first_seen")), c.getLong(i("last_seen")), c.getDouble(i("initial_price")), c.getDouble(i("current_price")),
            c.getDouble(i("initial_qty")), c.getDouble(i("current_qty")), c.getDouble(i("executed_qty")), c.getDouble(i("cancelled_qty")),
            c.getDouble(i("strength")), c.getInt(i("moves")), c.getInt(i("replenishments")), c.getString(i("status")),
            c.getInt(i("single_score")), c.getInt(i("multi_score")), c.getInt(i("indeterminate_score")), c.getString(i("last_event"))
        )
    }

    private fun noteFrom(c: Cursor) = TrackingNote(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5))

    companion object {
        private const val DB_NAME = "chk_tracking.db"
        private const val DB_VERSION = 1
        private const val PREFS = "chk_tracking_settings"
        private val STABLES = setOf("USDC", "USDT", "USD", "EUR", "FDUSD", "TUSD", "DAI", "USDE", "EURC")
    }
}

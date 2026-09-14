package com.chk.binancebybit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device Wall Tracker / Wall Fingerprint engine.
 *
 * Heavy market data never goes through Render or Supabase. Binance/Bybit public WebSockets feed
 * this engine directly; only compact derived state is relayed for MCP access.
 */
class WallTrackerEngine(context: Context) : TrackingMarketListener {
    private val app = context.applicationContext
    private val store = TrackingStore(app)
    private val resolver = TrackingPairResolver(app)
    private val remote = TrackingRemoteClient(app)
    private val runtime = app.getSharedPreferences("chk_tracking_runtime", Context.MODE_PRIVATE)
    private val running = AtomicBoolean(false)
    private val lock = Any()

    private var sockets: TrackingSocketManager? = null
    private var worker: Thread? = null
    private var heldAssets: Set<String> = emptySet()
    private var binancePairs: Map<String, String> = emptyMap() // asset -> pair
    private var bybitPairs: Map<String, String> = emptyMap()
    private var reverseBinance: Map<String, String> = emptyMap() // pair -> asset
    private var reverseBybit: Map<String, String> = emptyMap()
    private val connections = ConcurrentHashMap<String, Boolean>()
    private val connectionDetails = ConcurrentHashMap<String, String>()
    private val prices = ConcurrentHashMap<String, Double>()
    private val active = linkedMapOf<String, MutableWall>()
    private val matchCooldown = HashMap<String, Long>()
    @Volatile private var dirty = true
    @Volatile private var forceRefreshAssets = false
    private var lastRemotePullAt = 0L
    private var lastRemotePushAt = 0L
    private var lastAssetRefreshAt = 0L
    private var lastCleanupAt = 0L
    private var seq = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        runtime.edit().putBoolean("engine_running", true).putLong("engine_started_at", System.currentTimeMillis()).apply()
        synchronized(lock) {
            active.clear()
            store.activeWalls(150).forEach { active[it.id] = MutableWall.from(it) }
        }
        refreshAssets(true)
        worker = Thread { maintenanceLoop() }.apply {
            name = "CHK-WallTracker"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        worker?.interrupt(); worker = null
        sockets?.stop(); sockets = null
        runtime.edit().putBoolean("engine_running", false).apply()
    }

    fun trackedAssetCount(): Int = heldAssets.size
    fun isTrackingEnabled(): Boolean = store.enabled()

    override fun onConnection(exchange: String, connected: Boolean, detail: String) {
        connections[exchange] = connected
        connectionDetails[exchange] = detail.take(160)
        runtime.edit()
            .putBoolean("${exchange.lowercase(Locale.US)}_connected", connected)
            .putString("${exchange.lowercase(Locale.US)}_detail", detail.take(160))
            .putLong("connection_updated_at", System.currentTimeMillis())
            .apply()
        updateGapState()
        dirty = true
    }

    override fun onTicker(exchange: String, symbol: String, lastPrice: Double, timestamp: Long) {
        if (lastPrice > 0.0) prices["$exchange:$symbol"] = lastPrice
    }

    override fun onTrade(exchange: String, symbol: String, price: Double, quantity: Double, side: String, timestamp: Long) {
        if (price <= 0.0 || quantity <= 0.0) return
        synchronized(lock) {
            val candidates = active.values.filter {
                it.status == "ACTIVE" && it.exchange == exchange && it.symbol == symbol &&
                    ((it.side == "SELL" && side == "BUY") || (it.side == "BUY" && side == "SELL")) &&
                    nearPrice(it.price, price)
            }
            candidates.forEach { w ->
                w.executed += quantity
                w.lastSeen = max(w.lastSeen, timestamp)
                store.wallTrade(w.snapshot(), price, quantity, timestamp)
                if (System.currentTimeMillis() - w.lastPersistAt >= 2_000L) persist(w, "TRADE")
            }
        }
        dirty = true
    }

    override fun onBook(
        exchange: String,
        symbol: String,
        bids: List<TrackingBookLevel>,
        asks: List<TrackingBookLevel>,
        timestamp: Long
    ) {
        val asset = if (exchange == "BINANCE") reverseBinance[symbol] else reverseBybit[symbol] ?: return
        if (asset !in heldAssets) return
        synchronized(lock) {
            processSide(asset, exchange, symbol, "BUY", bids, timestamp)
            processSide(asset, exchange, symbol, "SELL", asks, timestamp)
        }
        dirty = true
    }

    private fun processSide(asset: String, exchange: String, symbol: String, side: String, levels: List<TrackingBookLevel>, at: Long) {
        if (levels.isEmpty()) return
        val quantities = levels.map { it.quantity }.filter { it > 0.0 }.sorted()
        if (quantities.isEmpty()) return
        val median = if (quantities.size % 2 == 1) quantities[quantities.size / 2]
        else (quantities[quantities.size / 2 - 1] + quantities[quantities.size / 2]) / 2.0
        if (median <= 0.0) return

        val minNotional = store.minWallNotional()
        val minStrength = store.minWallStrength()
        val candidates = levels.mapNotNull { l ->
            if (l.price <= 0.0 || l.quantity <= 0.0) return@mapNotNull null
            val strength = l.quantity / median
            val notional = l.price * l.quantity
            if (notional >= minNotional && (strength >= minStrength || (notional >= minNotional * 10.0 && strength >= 2.0))) {
                Candidate(l.price, l.quantity, strength, notional)
            } else null
        }.toMutableList()
        val used = HashSet<Int>()
        val same = active.values.filter { it.status == "ACTIVE" && it.exchange == exchange && it.symbol == symbol && it.side == side }.toList()

        for (wall in same) {
            val exact = levels.firstOrNull { nearPrice(it.price, wall.price) }
            if (exact != null && exact.quantity > 0.0) {
                val strength = exact.quantity / median
                updateExisting(wall, exact.price, exact.quantity, strength, at)
                val idx = candidates.indexOfFirst { nearPrice(it.price, exact.price) }
                if (idx >= 0) used += idx
                continue
            }

            var bestIndex = -1
            var bestScore = 0.0
            for (i in candidates.indices) {
                if (i in used) continue
                val c = candidates[i]
                val qSim = similarity(wall.qty, c.quantity)
                val pDist = abs(c.price - wall.price) / max(wall.price, c.price)
                val score = qSim * (1.0 - min(1.0, pDist / 0.03))
                if (qSim >= 0.82 && pDist <= 0.03 && score > bestScore) {
                    bestScore = score
                    bestIndex = i
                }
            }
            if (bestIndex >= 0) {
                used += bestIndex
                moveWall(wall, candidates[bestIndex], at)
            } else {
                closeWall(wall, at)
            }
        }

        for (i in candidates.indices) {
            if (i in used) continue
            val c = candidates[i]
            if (active.values.any { it.status == "ACTIVE" && it.exchange == exchange && it.symbol == symbol && it.side == side && nearPrice(it.price, c.price) }) continue
            val id = createWallId(asset, exchange, at)
            val wall = MutableWall(
                id = id, asset = asset, exchange = exchange, symbol = symbol, side = side,
                firstSeen = at, lastSeen = at, initialPrice = c.price, price = c.price,
                initialQty = c.quantity, qty = c.quantity, executed = 0.0, accountedExecution = 0.0,
                cancelled = 0.0, strength = c.strength, moves = 0, replenishments = 0,
                status = "ACTIVE", lastEvent = "APPEARED", lastPersistAt = 0L
            )
            active[id] = wall
            persist(wall, "APPEARED", "Mur détecté • ${formatStrength(c.strength)}× la quantité médiane")
            checkCrossExchange(wall, at)
        }
    }

    private fun updateExisting(w: MutableWall, price: Double, quantity: Double, strength: Double, at: Long) {
        val previous = w.qty
        if (quantity < previous) {
            val reduction = previous - quantity
            val executionAvailable = max(0.0, w.executed - w.accountedExecution)
            val absorbed = min(reduction, executionAvailable)
            w.accountedExecution += absorbed
            val cancelled = max(0.0, reduction - absorbed)
            w.cancelled += cancelled
            if (reduction >= max(w.initialQty * 0.10, 1e-9)) {
                w.lastEvent = if (absorbed >= reduction * 0.65) "ABSORBING" else if (cancelled >= reduction * 0.65) "REDUCED" else "MIXED_REDUCTION"
            }
        } else if (quantity > previous) {
            val increase = quantity - previous
            if (at - w.firstSeen >= 1_500L && increase >= max(w.initialQty * 0.08, previous * 0.15)) {
                w.replenishments++
                w.lastEvent = "REPLENISHED"
                w.qty = quantity; w.price = price; w.strength = strength; w.lastSeen = at
                persist(w, "REPLENISHED", "+${fmt(increase)} ${w.asset} au même niveau")
                checkCrossExchange(w, at)
                return
            }
        }
        w.qty = quantity
        w.price = price
        w.strength = strength
        w.lastSeen = at
        if (System.currentTimeMillis() - w.lastPersistAt >= 3_000L) persist(w, w.lastEvent)
    }

    private fun moveWall(w: MutableWall, c: Candidate, at: Long) {
        val oldPrice = w.price
        w.price = c.price
        w.qty = c.quantity
        w.strength = c.strength
        w.lastSeen = at
        w.moves++
        w.lastEvent = "MOVED"
        persist(w, "MOVED", "${fmt(oldPrice)} → ${fmt(c.price)} • quantité similaire ${Math.round(similarity(w.initialQty, c.quantity) * 100)} %")
        checkCrossExchange(w, at)
    }

    private fun closeWall(w: MutableWall, at: Long) {
        val reduction = max(0.0, w.qty)
        val executionAvailable = max(0.0, w.executed - w.accountedExecution)
        val absorbed = min(reduction, executionAvailable)
        w.accountedExecution += absorbed
        val cancelled = max(0.0, reduction - absorbed)
        w.cancelled += cancelled
        val status = when {
            reduction > 0.0 && absorbed >= reduction * 0.65 -> "ABSORBED"
            reduction > 0.0 && cancelled >= reduction * 0.65 -> "CANCELLED"
            else -> "DISAPPEARED"
        }
        w.qty = 0.0
        w.lastSeen = at
        w.status = status
        w.lastEvent = status
        persist(w, status, "Exécuté estimé ${fmt(w.executed)} • annulé estimé ${fmt(w.cancelled)}")
    }

    private fun persist(w: MutableWall, event: String, detail: String = "") {
        val snap = w.snapshot()
        store.upsertWall(snap)
        if (event in setOf("APPEARED", "MOVED", "REPLENISHED", "ABSORBED", "CANCELLED", "DISAPPEARED")) {
            store.wallEvent(snap, event, detail)
        }
        w.lastPersistAt = System.currentTimeMillis()
        dirty = true
    }

    private fun checkCrossExchange(w: MutableWall, at: Long) {
        val others = active.values.filter {
            it.status == "ACTIVE" && it.asset == w.asset && it.side == w.side && it.exchange != w.exchange && abs(it.lastSeen - w.lastSeen) <= 15_000L
        }
        var best: MutableWall? = null
        var bestScore = 0
        var bestQ = 0.0
        var bestP = 0.0
        for (o in others) {
            val q = similarity(w.qty, o.qty)
            val pDistance = abs(w.price - o.price) / max(w.price, o.price)
            val p = (1.0 - min(1.0, pDistance / 0.02)).coerceIn(0.0, 1.0)
            val time = (1.0 - min(1.0, abs(w.lastSeen - o.lastSeen) / 15_000.0)).coerceIn(0.0, 1.0)
            val behavior = (1.0 - min(1.0, (abs(w.moves - o.moves) + abs(w.replenishments - o.replenishments)) / 6.0)).coerceIn(0.0, 1.0)
            val score = (q * 45 + p * 25 + time * 20 + behavior * 10).toInt().coerceIn(0, 99)
            if (score > bestScore) { best = o; bestScore = score; bestQ = q; bestP = p }
        }
        val other = best ?: return
        if (bestScore < 72) return
        val pair = listOf(w.id, other.id).sorted().joinToString("|")
        val last = matchCooldown[pair] ?: 0L
        if (at - last < 30_000L) return
        matchCooldown[pair] = at
        store.addCrossMatch(w.snapshot(), other.snapshot(), bestQ, bestP, bestScore, at)
    }

    private fun maintenanceLoop() {
        while (running.get()) {
            try {
                val now = System.currentTimeMillis()
                if (forceRefreshAssets || now - lastAssetRefreshAt >= 60_000L) refreshAssets(false)
                if (now - lastRemotePullAt >= 15_000L) {
                    lastRemotePullAt = now
                    runCatching { remote.pullCommand() }.getOrNull()?.let { executeCommand(it) }
                }
                if ((dirty && now - lastRemotePushAt >= 10_000L) || now - lastRemotePushAt >= 60_000L) {
                    runCatching { remote.pushState(stateJson()) }.onSuccess {
                        lastRemotePushAt = now
                        store.setLastRemotePushAt(now)
                        dirty = false
                    }
                }
                if (now - lastCleanupAt >= 6 * 60 * 60_000L) {
                    store.cleanup(now)
                    lastCleanupAt = now
                }
                Thread.sleep(3_000L)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                try { Thread.sleep(5_000L) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun refreshAssets(force: Boolean) {
        lastAssetRefreshAt = System.currentTimeMillis()
        forceRefreshAssets = false
        val assets = store.heldAssets()
        if (!force && assets == heldAssets) return
        heldAssets = assets
        runtime.edit().putString("held_assets", assets.sorted().joinToString(",")).putInt("tracked_asset_count", assets.size).apply()
        if (assets.isEmpty() || !store.enabled()) {
            sockets?.stop(); sockets = null
            binancePairs = emptyMap(); bybitPairs = emptyMap(); reverseBinance = emptyMap(); reverseBybit = emptyMap()
            dirty = true
            return
        }
        val pairs = resolver.resolve(assets)
        binancePairs = pairs.binance
        bybitPairs = pairs.bybit
        reverseBinance = binancePairs.entries.associate { it.value to it.key }
        reverseBybit = bybitPairs.entries.associate { it.value to it.key }
        val b = binancePairs.values.toSet()
        val y = bybitPairs.values.toSet()
        if (sockets == null) sockets = TrackingSocketManager(this).also { it.start(b, y) }
        else sockets?.update(b, y)
        dirty = true
    }

    private fun executeCommand(pending: TrackingRemoteClient.PendingCommand) {
        val c = pending.command
        val op = c.optString("op").uppercase(Locale.US)
        val result = JSONObject().apply { put("ok", true); put("op", op); put("appliedAt", System.currentTimeMillis()) }
        try {
            when (op) {
                "SET_TRACKING_ENABLED" -> {
                    val enabled = c.optBoolean("enabled", true)
                    store.setEnabled(enabled)
                    result.put("enabled", enabled)
                    forceRefreshAssets = true
                    if (!enabled) { sockets?.stop(); sockets = null }
                }
                "SET_THRESHOLDS" -> {
                    if (c.has("minWallNotional")) store.setMinWallNotional(c.optDouble("minWallNotional"))
                    if (c.has("minWallStrength")) store.setMinWallStrength(c.optDouble("minWallStrength"))
                    result.put("minWallNotional", store.minWallNotional())
                    result.put("minWallStrength", store.minWallStrength())
                }
                "CREATE_NOTE" -> {
                    val asset = c.optString("asset").uppercase(Locale.US)
                    val wallId = c.optString("wallId")
                    val content = c.optString("content").trim()
                    require(content.isNotBlank()) { "Note vide" }
                    if (asset.isNotBlank()) require(asset in heldAssets) { "Actif non détenu" }
                    result.put("noteId", store.addNote(asset, wallId, content))
                }
                "UPDATE_NOTE" -> {
                    val id = c.optLong("id", 0L); require(id > 0L) { "ID note invalide" }
                    result.put("updated", store.updateNote(id, c.optString("content")))
                }
                "DELETE_NOTE" -> {
                    val id = c.optLong("id", 0L); require(id > 0L) { "ID note invalide" }
                    result.put("deleted", store.deleteNote(id))
                }
                "REFRESH_ASSETS" -> forceRefreshAssets = true
                else -> throw IllegalArgumentException("Commande tracking non supportée: $op")
            }
        } catch (e: Throwable) {
            result.put("ok", false); result.put("error", (e.message ?: e.javaClass.simpleName).take(220))
        }
        dirty = true
        runCatching { remote.ackCommand(pending.seq, result) }
    }

    fun stateJson(): JSONObject {
        val now = System.currentTimeMillis()
        val walls = synchronized(lock) { active.values.filter { it.status == "ACTIVE" }.map { it.snapshot().toJson(now) } }
        val notes = store.notes(50)
        return JSONObject().apply {
            put("trackingAvailable", true)
            put("trackingRunsOnDevice", true)
            put("rawOrderbookStoredLocally", false)
            put("rawOrderbookSentToRender", false)
            put("trackedAssetsMode", "PORTFOLIO_HOLDINGS_ONLY")
            put("requiresInternet", true)
            put("worksScreenOff", true)
            put("enabled", store.enabled())
            put("updatedAt", now)
            put("connections", JSONObject().apply {
                put("BINANCE", JSONObject().apply { put("connected", connections["BINANCE"] == true); put("detail", connectionDetails["BINANCE"] ?: "") })
                put("BYBIT", JSONObject().apply { put("connected", connections["BYBIT"] == true); put("detail", connectionDetails["BYBIT"] ?: "") })
            })
            put("settings", JSONObject().apply { put("minWallNotional", store.minWallNotional()); put("minWallStrength", store.minWallStrength()) })
            put("assets", JSONArray().apply {
                heldAssets.sorted().forEach { asset -> put(JSONObject().apply {
                    put("asset", asset)
                    put("binanceSymbol", binancePairs[asset] ?: JSONObject.NULL)
                    put("bybitSymbol", bybitPairs[asset] ?: JSONObject.NULL)
                    put("binancePrice", binancePairs[asset]?.let { prices["BINANCE:$it"] } ?: JSONObject.NULL)
                    put("bybitPrice", bybitPairs[asset]?.let { prices["BYBIT:$it"] } ?: JSONObject.NULL)
                    put("activeWalls", walls.count { it.optString("asset") == asset })
                }) }
            })
            put("walls", JSONArray(walls))
            put("events", store.recentEvents(80))
            put("crossExchangeMatches", store.recentMatches(40))
            put("connectionGaps", store.recentGaps(20))
            put("notes", JSONArray().apply { notes.forEach { put(it.toJson()) } })
        }
    }

    private fun updateGapState() {
        val expectedBinance = binancePairs.isNotEmpty()
        val expectedBybit = bybitPairs.isNotEmpty()
        val complete = (!expectedBinance || connections["BINANCE"] == true) && (!expectedBybit || connections["BYBIT"] == true)
        if (heldAssets.isEmpty() || !store.enabled()) return
        if (complete) store.closeGap() else {
            val missing = buildList {
                if (expectedBinance && connections["BINANCE"] != true) add("Binance")
                if (expectedBybit && connections["BYBIT"] != true) add("Bybit")
            }.joinToString(" + ")
            store.openGap("Flux indisponible: $missing")
        }
    }

    private fun createWallId(asset: String, exchange: String, at: Long): String {
        seq = (seq + 1) % 10_000
        return "WALL-${asset}-${exchange.take(3)}-${at.toString().takeLast(9)}-${seq.toString().padStart(4, '0')}"
    }

    private fun nearPrice(a: Double, b: Double): Boolean = abs(a - b) <= max(1e-10, max(abs(a), abs(b)) * 1e-8)
    private fun similarity(a: Double, b: Double): Double = if (a <= 0.0 || b <= 0.0) 0.0 else (1.0 - abs(a - b) / max(a, b)).coerceIn(0.0, 1.0)
    private fun formatStrength(value: Double) = String.format(Locale.FRANCE, "%.1f", value)
    private fun fmt(value: Double): String = when {
        abs(value) >= 1000 -> String.format(Locale.FRANCE, "%.0f", value)
        abs(value) >= 1 -> String.format(Locale.FRANCE, "%.4f", value)
        else -> String.format(Locale.FRANCE, "%.8f", value)
    }

    private data class Candidate(val price: Double, val quantity: Double, val strength: Double, val notional: Double)

    private data class MutableWall(
        val id: String,
        val asset: String,
        val exchange: String,
        val symbol: String,
        val side: String,
        val firstSeen: Long,
        var lastSeen: Long,
        val initialPrice: Double,
        var price: Double,
        val initialQty: Double,
        var qty: Double,
        var executed: Double,
        var accountedExecution: Double,
        var cancelled: Double,
        var strength: Double,
        var moves: Int,
        var replenishments: Int,
        var status: String,
        var lastEvent: String,
        var lastPersistAt: Long
    ) {
        fun snapshot(): TrackingWall {
            val age = max(0L, lastSeen - firstSeen)
            var single = 30 + min(24, moves * 7) + min(30, replenishments * 9)
            if (age >= 60_000L) single += 5
            if (age >= 5 * 60_000L) single += 5
            if (strength >= 8.0) single += 5
            if (moves >= 2 && replenishments >= 1) single += 5
            single = single.coerceIn(15, 90)
            var multi = 10
            if (moves == 0) multi += 12
            if (replenishments == 0) multi += 10
            if (cancelled > initialQty * 0.6 && moves == 0) multi += 8
            multi = multi.coerceIn(8, 55)
            if (single + multi > 95) multi = max(5, 95 - single)
            val indeterminate = max(5, 100 - single - multi)
            val total = single + multi + indeterminate
            val normalizedSingle = (single * 100.0 / total).toInt()
            val normalizedMulti = (multi * 100.0 / total).toInt()
            val normalizedUnknown = 100 - normalizedSingle - normalizedMulti
            return TrackingWall(
                id, asset, exchange, symbol, side, firstSeen, lastSeen, initialPrice, price, initialQty, qty,
                executed, cancelled, strength, moves, replenishments, status,
                normalizedSingle, normalizedMulti, normalizedUnknown, lastEvent
            )
        }

        companion object {
            fun from(w: TrackingWall) = MutableWall(
                w.id, w.asset, w.exchange, w.symbol, w.side, w.firstSeen, w.lastSeen, w.initialPrice,
                w.currentPrice, w.initialQty, w.currentQty, w.executedQty, w.executedQty, w.cancelledQty,
                w.strength, w.moves, w.replenishments, w.status, w.lastEvent, 0L
            )
        }
    }
}

package com.chk.binancebybit

import android.content.Context
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device Wall Tracker / Wall Fingerprint engine.
 *
 * Raw Binance/Bybit market streams stay on this phone. The engine processes aggregated WebSocket
 * events, persists only useful wall history and sends only compact derived state to the MCP relay.
 */
class WallTrackerEngine(context: Context) : TrackingMarketListener {
    private val app = context.applicationContext
    private val store = TrackingStore(app)
    private val resolver = TrackingPairResolver(app)
    private val remote = TrackingRemoteClient(app)
    private val notifier = TrackingWallNotifier(app)
    private val secureStore = SecureStore(app)
    private val proposalClient by lazy { TradeProposalClient(app, secureStore) }
    private val runtime = app.getSharedPreferences("chk_tracking_runtime", Context.MODE_PRIVATE)
    private val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val running = AtomicBoolean(false)
    private val lock = Any()

    @Volatile private var profile = store.powerProfile()
    private var sockets: TrackingSocketManager? = null
    private var worker: Thread? = null
    private var heldAssets: Set<String> = emptySet()
    private var portfolioValuesUsdc: Map<String, Double> = emptyMap()
    private var eligibleHeldAssets: Set<String> = emptySet()
    private var priorityAssets: Set<String> = emptySet()
    private var openOrderAssets: Set<String> = emptySet()
    private var binancePairs: Map<String, String> = emptyMap()
    private var bybitPairs: Map<String, String> = emptyMap()
    private var reverseBinance: Map<String, String> = emptyMap()
    private var reverseBybit: Map<String, String> = emptyMap()
    private val connections = ConcurrentHashMap<String, Boolean>()
    private val connectionDetails = ConcurrentHashMap<String, String>()
    private val connectionChangedAt = ConcurrentHashMap<String, Long>()
    private val freshness = ConcurrentHashMap<String, Long>()
    private val quotes = ConcurrentHashMap<String, TrackingQuote>()
    private val books = ConcurrentHashMap<String, BookSnapshot>()
    private val active = linkedMapOf<String, MutableWall>()
    private val matchCooldown = HashMap<String, Long>()
    @Volatile private var chkOrders: List<ChkOrderZone> = emptyList()
    @Volatile private var dirty = true
    @Volatile private var forceRefreshAssets = false
    private var lastRemotePullAt = 0L
    private var lastRemotePushAt = 0L
    private var lastAssetRefreshAt = 0L
    private var lastOrderRefreshAt = 0L
    private var lastCleanupAt = 0L
    private var lastRuntimeFreshWrite = 0L
    private var seq = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        profile = store.powerProfile()
        runtime.edit()
            .putBoolean("engine_running", true)
            .putString("power_mode", profile.mode.name)
            .putLong("engine_started_at", System.currentTimeMillis())
            .apply()
        synchronized(lock) {
            active.clear()
            store.activeWalls(180).forEach { active[it.id] = MutableWall.from(it) }
        }
        runCatching { refreshChkOrders(true) }
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

    fun trackedAssetCount(): Int = priorityAssets.size
    fun isTrackingEnabled(): Boolean = store.enabled()

    override fun onConnection(exchange: String, connected: Boolean, detail: String) {
        connections[exchange] = connected
        connectionDetails[exchange] = detail.take(180)
        connectionChangedAt[exchange] = System.currentTimeMillis()
        if (!connected) {
            runtime.edit().putBoolean("${exchange.lowercase(Locale.US)}_connected", false).apply()
        }
        updateGapState()
        dirty = true
    }

    override fun onQuote(exchange: String, symbol: String, quote: TrackingQuote) {
        if (quote.lastPrice <= 0.0 && quote.bid <= 0.0 && quote.ask <= 0.0) return
        quotes["$exchange:$symbol"] = quote
        markFresh(exchange, "quote", quote.timestamp)
        maybeWriteRuntimeFresh(exchange, quote)
        dirty = true
    }

    override fun onTicker(exchange: String, symbol: String, lastPrice: Double, timestamp: Long) {
        if (lastPrice > 0.0) markFresh(exchange, "ticker", timestamp)
    }

    override fun onTrade(exchange: String, symbol: String, price: Double, quantity: Double, side: String, timestamp: Long) {
        if (price <= 0.0 || quantity <= 0.0) return
        markFresh(exchange, "trade", timestamp)
        val interactive = powerManager.isInteractive
        val tradePersist = if (interactive) profile.tradePersistOnMs else profile.tradePersistOffMs
        synchronized(lock) {
            val candidates = active.values.filter {
                it.status == "ACTIVE" && it.exchange == exchange && it.symbol == symbol &&
                    ((it.side == "SELL" && side == "BUY") || (it.side == "BUY" && side == "SELL")) &&
                    nearPrice(it.price, price)
            }
            candidates.forEach { w ->
                w.executed += quantity
                w.pendingTradeQty += quantity
                w.pendingTradeNotional += price * quantity
                w.lastSeen = max(w.lastSeen, timestamp)
                if (System.currentTimeMillis() - w.lastTradePersistAt >= tradePersist) {
                    flushTradeSample(w, timestamp)
                }
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
        if (bids.isEmpty() && asks.isEmpty()) return
        val asset = (if (exchange == "BINANCE") reverseBinance[symbol] else reverseBybit[symbol]) ?: return
        if (asset !in priorityAssets) return
        books["$exchange:$symbol"] = BookSnapshot(bids.take(12), asks.take(12), timestamp)
        markFresh(exchange, "book", timestamp)
        synchronized(lock) {
            processSide(asset, exchange, symbol, "BUY", bids, timestamp)
            processSide(asset, exchange, symbol, "SELL", asks, timestamp)
        }
        dirty = true
    }

    private fun processSide(asset: String, exchange: String, symbol: String, side: String, levels: List<TrackingBookLevel>, at: Long) {
        if (levels.isEmpty()) return
        val quantities = levels.asSequence().map { it.quantity }.filter { it > 0.0 }.sorted().toList()
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
            val fingerprint = findFingerprint(asset, exchange, side, c, at)
            val wall = MutableWall(
                id = id, asset = asset, exchange = exchange, symbol = symbol, side = side,
                firstSeen = at, lastSeen = at, initialPrice = c.price, price = c.price,
                initialQty = c.quantity, qty = c.quantity, executed = 0.0, accountedExecution = 0.0,
                cancelled = 0.0, strength = c.strength, moves = 0, replenishments = 0,
                status = "ACTIVE", lastEvent = if (fingerprint != null) "REAPPEARED" else "APPEARED", lastPersistAt = 0L,
                fingerprintId = fingerprint?.fingerprintId ?: id,
                fingerprintConfidence = fingerprint?.score ?: 0,
                reappearances = fingerprint?.reappearances ?: 0
            )
            active[id] = wall
            if (fingerprint != null) {
                persist(wall, "REAPPEARED", "Fingerprint ${fingerprint.score}% • probablement le même mur que ${fingerprint.previousId}")
            } else {
                persist(wall, "APPEARED", "Mur détecté • ${formatStrength(c.strength)}× la quantité médiane")
            }
            checkCrossExchange(wall, at)
        }
    }

    private fun updateExisting(w: MutableWall, price: Double, quantity: Double, strength: Double, at: Long) {
        val previous = w.qty
        var meaningful: String? = null
        var detail = ""
        if (quantity < previous) {
            val reduction = previous - quantity
            val executionAvailable = max(0.0, w.executed - w.accountedExecution)
            val absorbed = min(reduction, executionAvailable)
            w.accountedExecution += absorbed
            val cancelled = max(0.0, reduction - absorbed)
            w.cancelled += cancelled
            if (reduction >= max(w.initialQty * 0.10, 1e-9)) {
                meaningful = if (absorbed >= reduction * 0.65) "ABSORBING" else if (cancelled >= reduction * 0.65) "REDUCED" else "MIXED_REDUCTION"
                w.lastEvent = meaningful
                detail = "Δ-${fmt(reduction)} • exécuté estimé ${fmt(absorbed)} • annulé estimé ${fmt(cancelled)}"
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
        val interval = if (powerManager.isInteractive) profile.activePersistOnMs else profile.activePersistOffMs
        if (meaningful != null) persist(w, meaningful, detail)
        else if (System.currentTimeMillis() - w.lastPersistAt >= interval) persist(w, "TRACK")
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
        flushTradeSample(w, at)
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
        persist(w, status, "Durée ${durationText(at - w.firstSeen)} • exécuté estimé ${fmt(w.executed)} • annulé estimé ${fmt(w.cancelled)}")
    }

    private fun flushTradeSample(w: MutableWall, at: Long) {
        if (w.pendingTradeQty <= 0.0) return
        val avg = if (w.pendingTradeQty > 0.0) w.pendingTradeNotional / w.pendingTradeQty else w.price
        store.wallTrade(w.snapshot(), avg, w.pendingTradeQty, at)
        w.pendingTradeQty = 0.0
        w.pendingTradeNotional = 0.0
        w.lastTradePersistAt = System.currentTimeMillis()
    }

    private fun persist(w: MutableWall, event: String, detail: String = "") {
        val snap = w.snapshot()
        store.upsertWall(snap)
        if (event in WALL_HISTORY_EVENTS) store.wallEvent(snap, event, detail)
        if (event in ALERT_EVENTS) notifier.maybeNotify(snap, event, detail, store.minWallNotional(), store.minWallStrength())
        if (!w.spoofAlerted && snap.spoofingProbability >= 75) {
            w.spoofAlerted = true
            val warning = "Spoofing probable ${snap.spoofingProbability}% • signal comportemental, aucune attribution institutionnelle"
            store.wallEvent(snap, "SPOOFING_PROBABLE", warning)
            notifier.maybeNotify(snap, "SPOOFING_PROBABLE", warning, store.minWallNotional(), store.minWallStrength())
        }
        w.lastPersistAt = System.currentTimeMillis()
        dirty = true
    }

    private fun findFingerprint(asset: String, exchange: String, side: String, c: Candidate, at: Long): FingerprintMatch? {
        val recent = store.recentClosedWalls(asset, exchange, side, at - 15 * 60_000L, 24)
        var best: FingerprintMatch? = null
        for (old in recent) {
            val priceDistance = abs(old.currentPrice - c.price) / max(old.currentPrice, c.price)
            if (priceDistance > 0.035) continue
            val priceScore = (1.0 - min(1.0, priceDistance / 0.035)).coerceIn(0.0, 1.0)
            val qtyScore = similarity(max(old.initialQty, old.currentQty), c.quantity)
            val gap = max(0L, at - old.lastSeen)
            val timeScore = (1.0 - min(1.0, gap / (15 * 60_000.0))).coerceIn(0.0, 1.0)
            val behavior = when {
                old.replenishments > 0 && old.moves > 0 -> 1.0
                old.replenishments > 0 || old.moves > 0 -> 0.75
                else -> 0.5
            }
            val score = (priceScore * 40 + qtyScore * 35 + timeScore * 15 + behavior * 10).toInt().coerceIn(0, 99)
            if (score >= 68 && (best == null || score > best.score)) {
                best = FingerprintMatch(
                    old.fingerprintId.ifBlank { old.id },
                    score,
                    old.reappearances + 1,
                    old.id
                )
            }
        }
        return best
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
                val selected = store.powerProfile()
                if (selected.mode != profile.mode) {
                    profile = selected
                    runtime.edit().putString("power_mode", profile.mode.name).apply()
                    sockets?.setProfile(profile)
                    forceRefreshAssets = true
                    dirty = true
                }
                if (now - lastOrderRefreshAt >= profile.orderRefreshMs) runCatching { refreshChkOrders(false) }
                if (forceRefreshAssets || now - lastAssetRefreshAt >= profile.assetRefreshMs) refreshAssets(false)
                if (now - lastRemotePullAt >= profile.remotePullMs) {
                    lastRemotePullAt = now
                    runCatching { remote.pullCommand() }.getOrNull()?.let { executeCommand(it) }
                }
                if ((dirty && now - lastRemotePushAt >= profile.remoteDirtyPushMs) || now - lastRemotePushAt >= profile.remoteHeartbeatMs) {
                    runCatching { remote.pushState(stateJson()) }.onSuccess {
                        lastRemotePushAt = now
                        store.setLastRemotePushAt(now)
                        dirty = false
                    }
                }
                if (now - lastCleanupAt >= 6 * 60 * 60_000L) {
                    store.cleanup(now)
                    synchronized(lock) { active.entries.removeAll { it.value.status != "ACTIVE" && now - it.value.lastSeen > 30 * 60_000L } }
                    lastCleanupAt = now
                }
                Thread.sleep(profile.maintenanceSleepMs)
            } catch (_: InterruptedException) {
                break
            } catch (_: Throwable) {
                try { Thread.sleep(max(2_000L, profile.maintenanceSleepMs)) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun refreshChkOrders(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastOrderRefreshAt < profile.orderRefreshMs) return
        lastOrderRefreshAt = now
        val bundle = proposalClient.list()
        val rows = mutableListOf<ChkOrderZone>()
        bundle.pending.forEach { p ->
            p.limitPrice?.takeIf { it > 0.0 }?.let { rows += ChkOrderZone(p.id, p.baseAsset, p.symbol, p.side, it, "PROPOSAL", p.createdAt ?: "") }
        }
        bundle.processing.forEach { p ->
            p.limitPrice?.takeIf { it > 0.0 }?.let { rows += ChkOrderZone(p.id, p.baseAsset, p.symbol, p.side, it, "PLACED", p.createdAt ?: "") }
        }
        for (i in 0 until bundle.recent.length()) {
            val o = bundle.recent.optJSONObject(i) ?: continue
            val result = o.optJSONObject("result") ?: JSONObject()
            val symbol = o.optString("symbol", result.optString("symbol")).uppercase(Locale.US)
            if (!symbol.endsWith("USDC")) continue
            val side = o.optString("side", result.optString("side")).uppercase(Locale.US)
            if (side != "BUY" && side != "SELL") continue
            val orderId = o.optString("bybit_order_id", result.optString("orderId"))
            val rawStatus = result.optString("orderStatus")
            val state = OrderLifecycle.fromBybit(rawStatus, orderId, result.optDouble("executedQty", 0.0))
            if (state !in setOf(CanonicalOrderState.PLACED, CanonicalOrderState.OPEN, CanonicalOrderState.PARTIAL)) continue
            val price = o.optDouble("limit_price", 0.0).takeIf { it > 0.0 }
                ?: result.optString("requestedPrice").toDoubleOrNull()?.takeIf { it > 0.0 }
                ?: continue
            rows += ChkOrderZone(o.optString("id", orderId), symbol.removeSuffix("USDC"), symbol, side, price, state.name, o.optString("created_at"))
        }
        chkOrders = rows.distinctBy { "${it.id}:${it.state}" }.take(80)
        openOrderAssets = chkOrders.filter { it.state in setOf("PLACED", "OPEN", "PARTIAL") }.map { it.asset }.toSet()
        forceRefreshAssets = true
        dirty = true
    }

    private fun refreshAssets(force: Boolean) {
        lastAssetRefreshAt = System.currentTimeMillis()
        forceRefreshAssets = false
        heldAssets = store.heldAssets()
        portfolioValuesUsdc = loadPortfolioValuesUsdc()
        val eligible = TrackingAssetPolicy.eligibleHoldings(portfolioValuesUsdc)
        eligibleHeldAssets = eligible.map { it.first }.toSet()
        val assets = TrackingAssetPolicy.select(portfolioValuesUsdc, profile.maxTrackedAssets).toSet()
        if (!force && assets == priorityAssets) return
        priorityAssets = assets
        runtime.edit()
            .putString("held_assets", heldAssets.sorted().joinToString(","))
            .putString("eligible_assets", eligible.map { it.first }.joinToString(","))
            .putString("priority_assets", priorityAssets.joinToString(","))
            .putInt("tracked_asset_count", priorityAssets.size)
            .putFloat("auto_tracking_min_holding_usdc", TrackingAssetPolicy.MIN_HOLDING_USDC.toFloat())
            .putInt("auto_tracking_max_assets", profile.maxTrackedAssets)
            .apply()
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
        if (sockets == null) sockets = TrackingSocketManager(this, profile).also { it.start(b, y) }
        else sockets?.update(b, y, profile)
        dirty = true
    }

    private fun loadPortfolioValuesUsdc(): Map<String, Double> {
        val workspace = app.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE)
        val totals = linkedMapOf<String, Double>()
        listOf("BINANCE" to "v4_snapshot_binance", "BYBIT" to "v4_snapshot_bybit").forEach { (exchange, key) ->
            val raw = workspace.getString(key, null)
                ?: if (exchange == "BINANCE") workspace.getString("last_snapshot", null) else workspace.getString("bybit_last_snapshot", null)
                ?: return@forEach
            runCatching {
                val root = JSONObject(raw)
                val array = root.optJSONArray("holdings") ?: root.optJSONArray("assets") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val holding = array.optJSONObject(i) ?: continue
                    val asset = holding.optString("asset").uppercase(Locale.US).trim()
                    if (!asset.matches(Regex("^[A-Z0-9]{2,16}$"))) continue
                    val amount = holding.optDouble("amount", holding.optDouble("free", 0.0))
                    if (amount <= 0.0) continue
                    var value = holding.optDouble("valueUsdt", 0.0)
                    if (!value.isFinite() || value <= 0.0) {
                        val price = holding.optDouble("priceUsdt", holding.optDouble("currentPriceUsdt", 0.0))
                        if (price.isFinite() && price > 0.0) value = amount * price
                    }
                    if (value.isFinite() && value > 0.0) totals[asset] = (totals[asset] ?: 0.0) + value
                }
            }
        }
        return totals
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
                "SET_POWER_MODE" -> {
                    val mode = TrackingPowerMode.parse(c.optString("mode"))
                    store.setPowerMode(mode)
                    profile = store.powerProfile()
                    sockets?.setProfile(profile)
                    forceRefreshAssets = true
                    result.put("mode", mode.name)
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
                    if (asset.isNotBlank()) require(asset in priorityAssets) { "Actif non suivi" }
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
                "REFRESH_ASSETS" -> {
                    runCatching { refreshChkOrders(true) }
                    forceRefreshAssets = true
                }
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
        val links = buildChkOrderWallLinks(walls)
        return JSONObject().apply {
            put("trackingAvailable", true)
            put("trackingRunsOnDevice", true)
            put("rawOrderbookStoredLocally", false)
            put("rawOrderbookSentToRender", false)
            put("trackedAssetsMode", "BTC_ETH_PLUS_TOP_HOLDINGS_OVER_10_USDC")
            put("requiresInternet", true)
            put("worksScreenOff", true)
            put("enabled", store.enabled())
            put("screenInteractive", powerManager.isInteractive)
            put("updatedAt", now)
            put("connections", JSONObject().apply {
                put("BINANCE", exchangeState("BINANCE", now))
                put("BYBIT", exchangeState("BYBIT", now))
            })
            put("settings", JSONObject().apply {
                put("powerMode", profile.mode.name)
                put("defaultPowerMode", TrackingPowerMode.BALANCED.name)
                put("minWallNotional", store.minWallNotional())
                put("minWallStrength", store.minWallStrength())
                put("minTrackedHoldingUsdc", TrackingAssetPolicy.MIN_HOLDING_USDC)
                put("maxTrackedAssets", profile.maxTrackedAssets)
                put("bookDispatchMs", profile.bookDispatchMs)
                put("remoteDirtyPushMs", profile.remoteDirtyPushMs)
                put("screenOffWriteReduction", true)
            })
            put("orderStatePolicy", JSONObject().apply {
                put("states", JSONArray(listOf("PLACED", "OPEN", "PARTIAL", "FILLED")))
                put("pnlAndTransactionsRequire", "FILLED")
                put("bybitIsSourceOfTruth", true)
            })
            put("institutionalInferencePolicy", "Aucun mur n'est qualifié d'institutionnel sans preuve externe vérifiable. Le score spoofing est seulement comportemental/probabiliste.")
            put("assets", JSONArray().apply {
                priorityAssets.forEach { asset -> put(assetJson(asset, walls, now)) }
            })
            put("walls", JSONArray(walls))
            put("events", store.recentEvents(100))
            put("crossExchangeMatches", store.recentMatches(50))
            put("chkOrderWallLinks", links)
            put("openChkOrders", JSONArray().apply { chkOrders.forEach { put(it.toJson()) } })
            put("connectionGaps", store.recentGaps(20))
            put("notes", JSONArray().apply { notes.forEach { put(it.toJson()) } })
        }
    }

    private fun assetJson(asset: String, walls: List<JSONObject>, now: Long): JSONObject = JSONObject().apply {
        put("asset", asset)
        put("held", asset in heldAssets)
        put("holdingValueUsdc", portfolioValuesUsdc[asset] ?: 0.0)
        put("eligibleByHoldingValue", asset in eligibleHeldAssets)
        put("openOrder", asset in openOrderAssets)
        put("alwaysPriority", asset == "BTC" || asset == "ETH")
        val bSymbol = binancePairs[asset]
        val ySymbol = bybitPairs[asset]
        put("binanceSymbol", bSymbol ?: JSONObject.NULL)
        put("bybitSymbol", ySymbol ?: JSONObject.NULL)
        val bQuote = bSymbol?.let { quotes["BINANCE:$it"] }
        val yQuote = ySymbol?.let { quotes["BYBIT:$it"] }
        val bBook = bSymbol?.let { books["BINANCE:$it"] }
        val yBook = ySymbol?.let { books["BYBIT:$it"] }
        put("binancePrice", bQuote?.lastPrice ?: 0.0)
        put("bybitPrice", yQuote?.lastPrice ?: 0.0)
        put("binanceFeed", feedJson(bQuote, bBook, now))
        put("bybitFeed", feedJson(yQuote, yBook, now))
        put("activeWalls", walls.count { it.optString("asset") == asset })
    }

    private fun feedJson(quote: TrackingQuote?, book: BookSnapshot?, now: Long): JSONObject = JSONObject().apply {
        val latest = max(quote?.timestamp ?: 0L, book?.timestamp ?: 0L)
        val age = if (latest > 0L) max(0L, now - latest) else Long.MAX_VALUE
        put("ready", quote != null || book != null)
        put("fresh", latest > 0L && age <= profile.freshnessMs)
        put("ageMs", if (latest > 0L) age else -1L)
        put("last", quote?.lastPrice ?: bestMid(book))
        put("bid", quote?.bid?.takeIf { it > 0.0 } ?: book?.bids?.firstOrNull()?.price ?: 0.0)
        put("ask", quote?.ask?.takeIf { it > 0.0 } ?: book?.asks?.firstOrNull()?.price ?: 0.0)
        put("quoteAt", quote?.timestamp ?: 0L)
        put("bookAt", book?.timestamp ?: 0L)
        put("source", quote?.source ?: if (book != null) "orderbook" else "warming")
        put("book", JSONObject().apply {
            put("bids", JSONArray().apply { book?.bids?.forEach { put(JSONArray().put(it.price).put(it.quantity)) } })
            put("asks", JSONArray().apply { book?.asks?.forEach { put(JSONArray().put(it.price).put(it.quantity)) } })
        })
    }

    private fun bestMid(book: BookSnapshot?): Double {
        val bid = book?.bids?.firstOrNull()?.price ?: 0.0
        val ask = book?.asks?.firstOrNull()?.price ?: 0.0
        return when {
            bid > 0.0 && ask > 0.0 -> (bid + ask) / 2.0
            bid > 0.0 -> bid
            ask > 0.0 -> ask
            else -> 0.0
        }
    }

    private fun exchangeState(exchange: String, now: Long): JSONObject {
        val quoteAt = freshness["$exchange:quote"] ?: 0L
        val bookAt = freshness["$exchange:book"] ?: 0L
        val tradeAt = freshness["$exchange:trade"] ?: 0L
        val latest = max(quoteAt, max(bookAt, tradeAt))
        val age = if (latest > 0L) max(0L, now - latest) else -1L
        return JSONObject().apply {
            put("transportConnected", connections[exchange] == true)
            put("ready", latest > 0L)
            put("fresh", latest > 0L && age <= profile.freshnessMs)
            put("ageMs", age)
            put("lastQuoteAt", quoteAt)
            put("lastBookAt", bookAt)
            put("lastTradeAt", tradeAt)
            put("connectionChangedAt", connectionChangedAt[exchange] ?: 0L)
            put("detail", connectionDetails[exchange] ?: "")
        }
    }

    private fun buildChkOrderWallLinks(walls: List<JSONObject>): JSONArray = JSONArray().apply {
        for (order in chkOrders) {
            for (wall in walls) {
                if (wall.optString("asset") != order.asset) continue
                val wp = wall.optDouble("price", 0.0)
                if (wp <= 0.0 || order.price <= 0.0) continue
                val distance = abs(wp - order.price) / max(wp, order.price)
                if (distance > 0.015) continue
                val wallSide = wall.optString("side")
                val relation = when {
                    order.side == "BUY" && wallSide == "BUY" -> "BUY_NEAR_BUY_WALL"
                    order.side == "BUY" && wallSide == "SELL" -> "BUY_BELOW_SELL_WALL"
                    order.side == "SELL" && wallSide == "SELL" -> "SELL_NEAR_SELL_WALL"
                    else -> "SELL_INTO_BUY_WALL"
                }
                put(JSONObject().apply {
                    put("orderId", order.id); put("orderState", order.state); put("asset", order.asset); put("orderSide", order.side)
                    put("orderPrice", order.price); put("wallId", wall.optString("id")); put("wallExchange", wall.optString("exchange"))
                    put("wallSide", wallSide); put("wallPrice", wp); put("distancePercent", distance * 100.0); put("relation", relation)
                })
            }
        }
    }

    private fun markFresh(exchange: String, type: String, timestamp: Long) {
        freshness["$exchange:$type"] = max(freshness["$exchange:$type"] ?: 0L, timestamp)
    }

    private fun maybeWriteRuntimeFresh(exchange: String, quote: TrackingQuote) {
        val now = System.currentTimeMillis()
        if (now - lastRuntimeFreshWrite < 5_000L && runtime.getBoolean("${exchange.lowercase(Locale.US)}_connected", false)) return
        lastRuntimeFreshWrite = now
        runtime.edit()
            .putBoolean("${exchange.lowercase(Locale.US)}_connected", true)
            .putBoolean("${exchange.lowercase(Locale.US)}_feed_ready", true)
            .putLong("${exchange.lowercase(Locale.US)}_last_data_at", quote.timestamp)
            .putLong("connection_updated_at", now)
            .apply()
    }

    private fun updateGapState() {
        val expectedBinance = binancePairs.isNotEmpty()
        val expectedBybit = bybitPairs.isNotEmpty()
        val complete = (!expectedBinance || connections["BINANCE"] == true) && (!expectedBybit || connections["BYBIT"] == true)
        if (priorityAssets.isEmpty() || !store.enabled()) return
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

    private fun durationText(ms: Long): String = when {
        ms < 60_000L -> "${max(0L, ms) / 1000}s"
        ms < 3_600_000L -> "${ms / 60_000L}min"
        else -> String.format(Locale.FRANCE, "%.1fh", ms / 3_600_000.0)
    }

    private data class BookSnapshot(val bids: List<TrackingBookLevel>, val asks: List<TrackingBookLevel>, val timestamp: Long)
    private data class Candidate(val price: Double, val quantity: Double, val strength: Double, val notional: Double)
    private data class FingerprintMatch(val fingerprintId: String, val score: Int, val reappearances: Int, val previousId: String)

    private data class ChkOrderZone(
        val id: String,
        val asset: String,
        val symbol: String,
        val side: String,
        val price: Double,
        val state: String,
        val createdAt: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("asset", asset); put("symbol", symbol); put("side", side); put("price", price)
            put("state", state); put("createdAt", createdAt)
        }
    }

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
        var lastPersistAt: Long,
        var fingerprintId: String,
        var fingerprintConfidence: Int,
        var reappearances: Int,
        var pendingTradeQty: Double = 0.0,
        var pendingTradeNotional: Double = 0.0,
        var lastTradePersistAt: Long = 0L,
        var spoofAlerted: Boolean = false
    ) {
        fun snapshot(): TrackingWall {
            val age = max(0L, lastSeen - firstSeen)
            var single = 25 + min(24, moves * 7) + min(28, replenishments * 9) + min(12, reappearances * 4)
            if (fingerprintConfidence >= 80) single += 8 else if (fingerprintConfidence >= 68) single += 4
            if (age >= 60_000L) single += 5
            if (age >= 5 * 60_000L) single += 5
            if (strength >= 8.0) single += 5
            single = single.coerceIn(12, 92)
            var multi = 12
            if (moves == 0) multi += 10
            if (replenishments == 0) multi += 8
            if (cancelled > initialQty * 0.6 && moves == 0) multi += 8
            multi = multi.coerceIn(8, 55)
            if (single + multi > 95) multi = max(5, 95 - single)
            val indeterminate = max(5, 100 - single - multi)
            val total = single + multi + indeterminate
            val normalizedSingle = (single * 100.0 / total).toInt()
            val normalizedMulti = (multi * 100.0 / total).toInt()
            val normalizedUnknown = 100 - normalizedSingle - normalizedMulti

            val cancellationRatio = if (initialQty > 0.0) (cancelled / initialQty).coerceIn(0.0, 2.0) else 0.0
            val executionRatio = if (initialQty > 0.0) (executed / initialQty).coerceIn(0.0, 2.0) else 0.0
            var spoof = 0
            if (age < 20_000L && cancellationRatio > 0.65) spoof += 38
            if (age < 60_000L && cancellationRatio > 0.80) spoof += 20
            if (moves >= 2 && cancellationRatio > 0.45) spoof += 14
            if (reappearances >= 2 && cancellationRatio > 0.45) spoof += 16
            if (executionRatio < 0.10 && cancellationRatio > 0.70) spoof += 18
            if (replenishments > 0 && executionRatio > 0.35) spoof -= 18
            if (status == "ABSORBED") spoof -= 25
            spoof = spoof.coerceIn(0, 95)

            return TrackingWall(
                id, asset, exchange, symbol, side, firstSeen, lastSeen, initialPrice, price, initialQty, qty,
                executed, cancelled, strength, moves, replenishments, status,
                normalizedSingle, normalizedMulti, normalizedUnknown, lastEvent,
                fingerprintId, fingerprintConfidence, reappearances, spoof
            )
        }

        companion object {
            fun from(w: TrackingWall) = MutableWall(
                w.id, w.asset, w.exchange, w.symbol, w.side, w.firstSeen, w.lastSeen, w.initialPrice,
                w.currentPrice, w.initialQty, w.currentQty, w.executedQty, w.executedQty, w.cancelledQty,
                w.strength, w.moves, w.replenishments, w.status, w.lastEvent, 0L,
                w.fingerprintId.ifBlank { w.id }, w.fingerprintConfidence, w.reappearances,
                spoofAlerted = w.spoofingProbability >= 75
            )
        }
    }

    companion object {
        private val WALL_HISTORY_EVENTS = setOf(
            "APPEARED", "REAPPEARED", "MOVED", "REPLENISHED", "ABSORBING", "REDUCED", "MIXED_REDUCTION",
            "ABSORBED", "CANCELLED", "DISAPPEARED"
        )
        private val ALERT_EVENTS = setOf("REAPPEARED", "MOVED", "REPLENISHED", "ABSORBED", "CANCELLED", "DISAPPEARED")
    }
}

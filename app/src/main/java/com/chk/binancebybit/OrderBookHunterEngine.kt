package com.chk.binancebybit

import android.content.Context
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OrderBookHunterEngine(
    context: Context,
    private val eventSink: (HunterEvent) -> Unit = {}
) : OrderBookHunterSocketListener {
    private val app = context.applicationContext
    private val db = OrderBookHunterDb(app)
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val started = AtomicBoolean(false)
    private val tickers = ConcurrentHashMap<String, HunterTicker>()
    private val trades = ConcurrentHashMap<String, ArrayDeque<HunterTrade>>()
    private val tracks = HashMap<String, MutableList<HunterWallTrack>>()
    private val recentEvents = HashMap<String, ArrayDeque<HunterEvent>>()
    private val lastScores = HashMap<String, Int>()
    private val lastScorePersistAt = HashMap<String, Long>()
    private val lastWallPersistAt = HashMap<String, Long>()
    private val lastMismatchAt = HashMap<String, Long>()
    private val lastImbalanceAt = HashMap<String, Long>()
    private val lastSweepAt = HashMap<String, Long>()
    private val desynced = HashSet<String>()
    @Volatile private var symbols: List<String> = emptyList()
    @Volatile private var socket: OrderBookHunterWebSocket? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        symbols = db.watches().map { it.symbol }.take(OrderBookHunterWebSocket.MAX_SYMBOLS)
        if (symbols.isNotEmpty()) socket = OrderBookHunterWebSocket(symbols, this).also { it.start() }
        executor.scheduleAtFixedRate({ safeAnalyzeAll() }, 1000, 1000, TimeUnit.MILLISECONDS)
        executor.scheduleAtFixedRate({ runCatching { db.cleanup() } }, 5, 60, TimeUnit.MINUTES)
    }

    fun reload() {
        if (!started.get()) return
        executor.execute {
            val next = db.watches().map { it.symbol }.take(OrderBookHunterWebSocket.MAX_SYMBOLS)
            val old = symbols
            symbols = next
            (old - next.toSet()).forEach { symbol -> OrderBookHunterStore.remove(symbol) }
            when {
                next.isEmpty() -> { socket?.stop(); socket = null }
                socket == null -> socket = OrderBookHunterWebSocket(next, this).also { it.start() }
                old != next -> socket?.replaceSymbols(next)
            }
        }
    }

    fun shutdown() {
        if (!started.getAndSet(false)) return
        socket?.stop(); socket = null
        executor.shutdownNow()
        db.close()
    }

    override fun onTicker(ticker: HunterTicker) {
        tickers[ticker.symbol] = ticker
    }

    override fun onTrade(trade: HunterTrade) {
        val queue = trades.getOrPut(trade.symbol) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(trade)
            val cutoff = System.currentTimeMillis() - 120_000L
            while (queue.isNotEmpty() && queue.first.timestamp < cutoff) queue.removeFirst()
            while (queue.size > 4000) queue.removeFirst()
        }
    }

    override fun onDesync(symbol: String, reason: String) {
        executor.execute {
            desynced += symbol
            emit(HunterEvent(
                id = id(), symbol = symbol, type = HunterEventType.BOOK_DESYNC,
                detail = "Carnet local invalidé : $reason. Analyse suspendue jusqu'au prochain snapshot propre."
            ))
            socket?.replaceSymbols(symbols)
        }
    }

    override fun onConnected(endpoint: String) = Unit
    override fun onDisconnected(reason: String) = Unit
    override fun onBookUpdated(symbol: String, timestamp: Long) = Unit

    private fun safeAnalyzeAll() {
        if (!started.get()) return
        val now = System.currentTimeMillis()
        symbols.forEach { symbol -> runCatching { analyze(symbol, now) } }
    }

    private fun analyze(symbol: String, now: Long) {
        val book = socket?.snapshot(symbol) ?: return
        if (!book.synchronized || book.midPrice <= 0.0) return
        if (desynced.remove(symbol)) {
            emit(HunterEvent(id(), symbol, HunterEventType.BOOK_RESYNC, detail = "Snapshot Bybit propre reçu. Analyse temporelle reprise."))
        }
        val ticker = tickers[symbol]
        val tradeList = tradesSnapshot(symbol, now - 120_000L)
        val (bidCandidates, askCandidates) = OrderBookHunterMath.candidateWalls(book, ticker, now)
        val currentWalls = reconcile(symbol, book, bidCandidates + askCandidates, tradeList, now)
        val imbalances = OrderBookHunterMath.imbalances(book)
        val mismatch = detectLiquidityMismatch(symbol, book, ticker, tradeList, now)
        detectExtremeImbalance(symbol, imbalances, now)
        detectSweep(symbol, book, tradeList, now)
        val recent = recentEvents(symbol, now - 10L * 60L * 1000L)
        val score = OrderBookHunterMath.anomalyScore(
            maxWallSignificance = currentWalls.maxOfOrNull { it.significanceScore } ?: 0.0,
            recentEvents = recent,
            imbalances = imbalances,
            spreadPercent = book.spreadPercent,
            liquidityMismatch = mismatch
        )
        val previous = lastScores[symbol] ?: score
        val status = HunterStatus(
            symbol = symbol,
            watching = db.isWatching(symbol),
            lastPrice = ticker?.lastPrice?.takeIf { it > 0.0 } ?: book.midPrice,
            change24hPct = ticker?.change24hPct ?: 0.0,
            spreadPercent = book.spreadPercent,
            turnover24h = ticker?.turnover24h ?: 0.0,
            volume24h = ticker?.volume24h ?: 0.0,
            anomalyScore = score,
            classification = HunterClassification.label(score),
            synchronized = true,
            bidWalls = currentWalls.filter { it.side == HunterWallSide.BUY }.sortedByDescending { it.significanceScore }.take(8),
            askWalls = currentWalls.filter { it.side == HunterWallSide.SELL }.sortedByDescending { it.significanceScore }.take(8),
            imbalances = imbalances,
            updatedAt = now
        )
        OrderBookHunterStore.put(status)
        lastScores[symbol] = score
        if (scoreBand(score) != scoreBand(previous) || abs(score - previous) >= 15) {
            emit(HunterEvent(
                id(), symbol, HunterEventType.SCORE_CHANGED, score = score,
                detail = "Score anomalie $previous → $score • ${HunterClassification.label(score)}. ${HunterClassification.safeExplanation(score)}"
            ))
        }
        if (now - (lastScorePersistAt[symbol] ?: 0L) >= 30_000L) {
            db.addScore(status)
            lastScorePersistAt[symbol] = now
        }
    }

    private fun reconcile(
        symbol: String,
        book: HunterBookSnapshot,
        candidates: List<HunterWallView>,
        tradeList: List<HunterTrade>,
        now: Long
    ): List<HunterWallView> {
        val list = tracks.getOrPut(symbol) { mutableListOf() }
        val unmatched = candidates.toMutableList()
        val activeViews = ArrayList<HunterWallView>()
        val considered = list.filter { it.status == HunterWallStatus.ACTIVE || (it.lastMissingAt > 0L && now - it.lastMissingAt <= REPOSITION_WINDOW_MS) }

        for (track in considered) {
            val rawQty = rawQty(book, track.side, track.currentPrice)
            if (rawQty != null) {
                unmatched.removeAll { it.side == track.side && samePrice(it.price, track.currentPrice) }
                updateExisting(track, rawQty, tradeList, now)
                val source = candidates.firstOrNull { it.side == track.side && samePrice(it.price, track.currentPrice) }
                activeViews += viewFor(track, source, book.midPrice, now)
                persistWall(track, now)
                continue
            }

            val moved = unmatched.asSequence()
                .filter { it.side == track.side }
                .filter { OrderBookHunterMath.similarQty(track.currentQty, it.qty) >= 0.68 }
                .filter { OrderBookHunterMath.distancePct(it.price, track.currentPrice) <= 1.5 }
                .maxByOrNull { OrderBookHunterMath.similarQty(track.currentQty, it.qty) * 100.0 - OrderBookHunterMath.distancePct(it.price, track.currentPrice) * 10.0 }

            if (moved != null) {
                unmatched.remove(moved)
                moveTrack(track, moved, now)
                activeViews += moved.copy(trackId = track.id, ageSeconds = (now - track.firstSeen) / 1000L)
                persistWall(track, now, force = true)
            } else if (track.status == HunterWallStatus.ACTIVE) {
                disappearTrack(track, book, tradeList, now)
                persistWall(track, now, force = true)
            }
        }

        unmatched.forEach { wall ->
            val track = HunterWallTrack(
                id = id(), symbol = symbol, side = wall.side,
                initialPrice = wall.price, currentPrice = wall.price,
                initialQty = wall.qty, currentQty = wall.qty,
                firstSeen = now, lastSeen = now
            )
            list += track
            activeViews += wall.copy(trackId = track.id, ageSeconds = 0)
            persistWall(track, now, force = true)
            emit(HunterEvent(
                id(), symbol, HunterEventType.LARGE_WALL, side = wall.side, price = wall.price, qty = wall.qty,
                detail = "Gros mur ${wall.side.name} relatif détecté à ${fmt(wall.price)} • ${fmtQty(wall.qty)} • ~${fmtMoney(wall.notionalUsdc)} USDC • score taille ${wall.significanceScore.toInt()}/100"
            ))
        }

        list.removeAll { it.status != HunterWallStatus.ACTIVE && it.lastMissingAt > 0L && now - it.lastMissingAt > 60_000L }
        return activeViews
    }

    private fun updateExisting(track: HunterWallTrack, newQty: Double, tradeList: List<HunterTrade>, now: Long) {
        val oldQty = track.currentQty
        if (newQty < oldQty * 0.90) {
            val decrease = oldQty - newQty
            val traded = OrderBookHunterMath.tradeVolumeAtWall(tradeList, track.side, track.currentPrice, track.lastSeen - 300L)
            if (traded >= decrease * 0.25) {
                track.executedEstimate += min(decrease, traded)
                emit(HunterEvent(
                    id(), track.symbol, HunterEventType.WALL_ABSORPTION, track.side, track.currentPrice, min(decrease, traded),
                    detail = "${track.side.name} absorption probable : le mur a diminué de ${fmtQty(decrease)} pendant que des trades opposés ont réellement exécuté ce niveau."
                ))
            }
        }
        track.minObservedQty = min(track.minObservedQty, newQty)
        if (track.minObservedQty < track.initialQty * 0.70 && newQty >= track.initialQty * 0.75 && newQty >= track.minObservedQty * 1.50 && oldQty < newQty * 0.90) {
            track.refillCount++
            emit(HunterEvent(
                id(), track.symbol, HunterEventType.WALL_REFILL, track.side, track.currentPrice, newQty,
                detail = "REFILL / ICEBERG POTENTIEL : quantité remontée de ${fmtQty(track.minObservedQty)} à ${fmtQty(newQty)} après consommation. Iceberg non confirmé."
            ))
            track.minObservedQty = newQty
        }
        track.currentQty = newQty
        track.lastSeen = now
        track.status = HunterWallStatus.ACTIVE
        track.lastMissingAt = 0L
    }

    private fun moveTrack(track: HunterWallTrack, wall: HunterWallView, now: Long) {
        val oldPrice = track.currentPrice
        val retreat = when (track.side) {
            HunterWallSide.BUY -> wall.price < oldPrice
            HunterWallSide.SELL -> wall.price > oldPrice
        }
        track.currentPrice = wall.price
        track.currentQty = wall.qty
        track.lastSeen = now
        track.lastMissingAt = 0L
        track.moveCount++
        track.status = HunterWallStatus.ACTIVE
        val type = if (retreat) HunterEventType.WALL_RETREAT else HunterEventType.WALL_CHASING_PRICE
        emit(HunterEvent(
            id(), track.symbol, type, track.side, wall.price, wall.qty, oldPrice = oldPrice, newPrice = wall.price,
            detail = if (retreat) {
                "WALL_RETREAT probable : mur ${track.side.name} similaire déplacé ${fmt(oldPrice)} → ${fmt(wall.price)} en quelques secondes."
            } else {
                "WALL_CHASING_PRICE : mur ${track.side.name} similaire suit le prix ${fmt(oldPrice)} → ${fmt(wall.price)}. Cela peut être du market making normal."
            }
        ))
        if (track.moveCount >= 3 && track.moveCount % 3 == 0) {
            emit(HunterEvent(
                id(), track.symbol, HunterEventType.REPEATED_WALL_REPOSITIONING, track.side, wall.price, wall.qty,
                detail = "Même profil de mur repositionné ${track.moveCount} fois. Spoofing potentiel / market-making agressif. Manipulation non confirmée."
            ))
        }
    }

    private fun disappearTrack(track: HunterWallTrack, book: HunterBookSnapshot, tradeList: List<HunterTrade>, now: Long) {
        val executed = OrderBookHunterMath.tradeVolumeAtWall(tradeList, track.side, track.currentPrice, track.lastSeen - 500L)
        val distance = OrderBookHunterMath.distancePct(track.currentPrice, book.midPrice)
        track.lastMissingAt = now
        track.lastSeen = now
        when {
            executed >= track.currentQty * 0.25 -> {
                track.executedEstimate += min(track.currentQty, executed)
                track.status = HunterWallStatus.ABSORBED
                emit(HunterEvent(
                    id(), track.symbol, HunterEventType.WALL_ABSORPTION, track.side, track.currentPrice, min(track.currentQty, executed),
                    detail = "Mur disparu avec trades réels au même niveau : absorption/exécution probable, pas simple annulation."
                ))
            }
            distance <= 0.50 -> {
                track.cancelCount++
                track.status = HunterWallStatus.CANCELLED
                emit(HunterEvent(
                    id(), track.symbol, HunterEventType.WALL_CANCELLED_NEAR_TOUCH, track.side, track.currentPrice, track.currentQty,
                    detail = "Mur ${track.side.name} supprimé à ${String.format(java.util.Locale.US, "%.3f", distance)} % du prix sans volume exécuté suffisant."
                ))
            }
            else -> {
                track.status = HunterWallStatus.DISAPPEARED
                emit(HunterEvent(
                    id(), track.symbol, HunterEventType.WALL_DISAPPEARED, track.side, track.currentPrice, track.currentQty,
                    detail = "Mur ${track.side.name} disparu à ${fmt(track.currentPrice)} • distance prix ${String.format(java.util.Locale.US, "%.2f", distance)} %."
                ))
            }
        }
    }

    private fun detectLiquidityMismatch(symbol: String, book: HunterBookSnapshot, ticker: HunterTicker?, trades: List<HunterTrade>, now: Long): Boolean {
        val turnover = ticker?.turnover24h ?: 0.0
        if (turnover <= 0.0 || book.midPrice <= 0.0) return false
        val visible = (book.bids + book.asks).filter { OrderBookHunterMath.distancePct(it.price, book.midPrice) <= 2.0 }.sumOf { it.notionalUsdc }
        val recent = trades.filter { it.timestamp >= now - 60_000L }.sumOf { it.notionalUsdc }
        val mismatch = visible >= turnover * 0.25 && recent <= visible * 0.03
        if (mismatch && now - (lastMismatchAt[symbol] ?: 0L) >= 60_000L) {
            lastMismatchAt[symbol] = now
            emit(HunterEvent(
                id(), symbol, HunterEventType.ORDERBOOK_LIQUIDITY_MISMATCH,
                detail = "Carnet visible très lourd par rapport au turnover et aux trades réels récents. Liquidité affichée potentiellement trompeuse."
            ))
        }
        return mismatch
    }

    private fun detectExtremeImbalance(symbol: String, imbalances: List<HunterImbalance>, now: Long) {
        val one = imbalances.minByOrNull { abs(it.distancePercent - 1.0) } ?: return
        if ((one.buyPressure >= 85.0 || one.buyPressure <= 15.0) && now - (lastImbalanceAt[symbol] ?: 0L) >= 30_000L) {
            lastImbalanceAt[symbol] = now
            emit(HunterEvent(
                id(), symbol, HunterEventType.ORDERBOOK_IMBALANCE,
                detail = "Ordres visibles ±1 % : BUY ${one.buyPressure.toInt()} % / SELL ${one.sellPressure.toInt()} %. Ce n'est pas une garantie directionnelle."
            ))
        }
    }

    private fun detectSweep(symbol: String, book: HunterBookSnapshot, trades: List<HunterTrade>, now: Long) {
        if (now - (lastSweepAt[symbol] ?: 0L) < 15_000L) return
        val recent = trades.filter { it.timestamp >= now - 1_200L }
        if (recent.size < 5) return
        val uniquePrices = recent.map { it.price }.distinct().size
        val buyCount = recent.count { it.side.equals("Buy", true) }
        val sellCount = recent.size - buyCount
        val dominant = max(buyCount, sellCount).toDouble() / recent.size.toDouble()
        val medianVisible = OrderBookHunterMath.median((book.bids + book.asks).map { it.notionalUsdc })
        val tradedNotional = recent.sumOf { it.notionalUsdc }
        if (uniquePrices >= 5 && dominant >= 0.75 && tradedNotional >= medianVisible * 2.0) {
            lastSweepAt[symbol] = now
            emit(HunterEvent(
                id(), symbol, HunterEventType.ORDERBOOK_SWEEP,
                detail = "Sweep probable : ${recent.size} trades ont traversé $uniquePrices niveaux en ~1 s avec un côté dominant."
            ))
        }
    }

    private fun emit(event: HunterEvent) {
        db.addEvent(event)
        val q = recentEvents.getOrPut(event.symbol) { ArrayDeque() }
        q.addLast(event)
        val cutoff = System.currentTimeMillis() - 30L * 60L * 1000L
        while (q.isNotEmpty() && q.first.createdAt < cutoff) q.removeFirst()
        while (q.size > 500) q.removeFirst()
        eventSink(event)
    }

    private fun recentEvents(symbol: String, since: Long): List<HunterEvent> {
        val q = recentEvents[symbol] ?: return emptyList()
        while (q.isNotEmpty() && q.first.createdAt < since - 20L * 60L * 1000L) q.removeFirst()
        return q.filter { it.createdAt >= since }
    }

    private fun tradesSnapshot(symbol: String, since: Long): List<HunterTrade> {
        val q = trades[symbol] ?: return emptyList()
        synchronized(q) { return q.filter { it.timestamp >= since } }
    }

    private fun rawQty(book: HunterBookSnapshot, side: HunterWallSide, price: Double): Double? {
        val levels = if (side == HunterWallSide.BUY) book.bids else book.asks
        return levels.firstOrNull { samePrice(it.price, price) }?.qty
    }

    private fun viewFor(track: HunterWallTrack, source: HunterWallView?, mid: Double, now: Long): HunterWallView {
        return source?.copy(trackId = track.id, qty = track.currentQty, notionalUsdc = track.currentPrice * track.currentQty, ageSeconds = (now - track.firstSeen) / 1000L)
            ?: HunterWallView(
                trackId = track.id, side = track.side, price = track.currentPrice, qty = track.currentQty,
                notionalUsdc = track.currentPrice * track.currentQty, ageSeconds = (now - track.firstSeen) / 1000L,
                distanceFromMidPercent = OrderBookHunterMath.distancePct(track.currentPrice, mid),
                wallQtyRatio = 0.0, wallVsMedianDepth = 0.0, wallVsTurnover = 0.0, significanceScore = 42.0
            )
    }

    private fun persistWall(track: HunterWallTrack, now: Long, force: Boolean = false) {
        val last = lastWallPersistAt[track.id] ?: 0L
        if (force || now - last >= 10_000L) {
            db.saveWall(track)
            lastWallPersistAt[track.id] = now
        }
    }

    private fun samePrice(a: Double, b: Double): Boolean = a > 0.0 && b > 0.0 && abs(a - b) / max(a, b) <= 0.00005
    private fun scoreBand(score: Int): Int = when (score) { in 0..39 -> 0; in 40..59 -> 1; in 60..74 -> 2; in 75..89 -> 3; else -> 4 }
    private fun id(): String = UUID.randomUUID().toString()
    private fun fmt(v: Double): String = if (v >= 1.0) String.format(java.util.Locale.US, "%.4f", v) else String.format(java.util.Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
    private fun fmtQty(v: Double): String = when { v >= 1_000_000 -> String.format(java.util.Locale.US, "%.2fM", v / 1_000_000.0); v >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", v / 1_000.0); else -> String.format(java.util.Locale.US, "%.2f", v) }
    private fun fmtMoney(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    companion object { private const val REPOSITION_WINDOW_MS = 5_000L }
}

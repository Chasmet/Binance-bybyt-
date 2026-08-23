package com.chk.binancebybit

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BybitExecutionUncertainException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BybitTradeClient(
    private val apiKey: String,
    private val apiSecret: String,
    private val baseUrl: String = "https://api.bybit.eu"
) {
    private val recvWindow = "5000"
    private var serverOffsetMs = 0L

    /**
     * Exécute uniquement une proposition déjà réservée côté Supabase (status=processing).
     * Aucun appel à /v5/order/create n'est possible depuis ce chemin sans claim préalable.
     */
    fun execute(proposal: TradeProposal): TradeExecutionResult {
        require(apiKey.isNotBlank() && apiSecret.isNotBlank()) { "Clés Bybit manquantes" }
        require(proposal.status == "processing") { "La proposition n'est pas réservée pour exécution" }
        require(proposal.symbol.matches(Regex("^[A-Z0-9]{2,20}USDC$"))) { "Seules les paires Spot */USDC sont autorisées" }
        require(proposal.side == "BUY" || proposal.side == "SELL") { "Sens d'ordre invalide" }
        require(proposal.orderType == "MARKET" || proposal.orderType == "LIMIT") { "Type d'ordre invalide" }
        require(proposal.quoteAmountUsdc > MIN_ORDER_USDC && proposal.quoteAmountUsdc <= MAX_ORDER_USDC + 1e-9) {
            "Montant autorisé : plus de ${fmt(MIN_ORDER_USDC)} USDC et au maximum ${MAX_ORDER_USDC.toInt()} USDC"
        }

        syncServerTime()
        ensureNotExpired(proposal)
        verifySpotTradePermission()

        val instrument = instrumentInfo(proposal.symbol)
        val lotSizeFilter = instrument.optJSONObject("lotSizeFilter")
        val qtyStepRaw = lotSizeFilter?.optString("qtyStep", "0") ?: "0"
        val basePrecision = lotSizeFilter?.optString("basePrecision", "0") ?: "0"
        val qtyStep = firstPositiveStep(qtyStepRaw, basePrecision)
        val tickSize = instrument.optJSONObject("priceFilter")?.optString("tickSize", "0") ?: "0"
        val baseAsset = proposal.baseAsset
        val balances = walletBalances()
        val deterministicLinkId = orderLinkId(proposal.id)

        val body = JSONObject().apply {
            put("category", "spot")
            put("symbol", proposal.symbol)
            put("side", if (proposal.side == "BUY") "Buy" else "Sell")
            put("orderType", if (proposal.orderType == "MARKET") "Market" else "Limit")
            put("isLeverage", 0)
            put("orderFilter", "Order")
            put("orderLinkId", deterministicLinkId)
        }

        var requestedQty: String
        var requestedPrice: String? = null
        var estimatedNotional = proposal.quoteAmountUsdc

        if (proposal.orderType == "MARKET" && proposal.side == "BUY") {
            val availableUsdc = usableBalance(balances.optJSONObject("USDC"))
            if (availableUsdc + 1e-9 < proposal.quoteAmountUsdc) {
                throw IllegalStateException("Solde USDC insuffisant : ${fmt(availableUsdc)} USDC disponibles")
            }
            ensureLocalMinimum(proposal.quoteAmountUsdc)
            requestedQty = decimal(proposal.quoteAmountUsdc)
            body.put("qty", requestedQty)
            body.put("marketUnit", "quoteCoin")
            body.put("timeInForce", "IOC")
        } else {
            val baseQty = when {
                proposal.side == "SELL" -> proposal.baseQuantity
                    ?: throw IllegalStateException("Quantité de token manquante pour la vente")
                proposal.orderType == "LIMIT" && proposal.baseQuantity != null -> proposal.baseQuantity
                else -> {
                    val price = proposal.limitPrice ?: throw IllegalStateException("Prix LIMIT manquant")
                    proposal.quoteAmountUsdc / price
                }
            }

            requestedQty = floorToStep(baseQty, qtyStep)
            val qtyNum = requestedQty.toDoubleOrNull() ?: 0.0
            if (qtyNum <= 0.0) throw IllegalStateException("Quantité trop petite pour le pas Bybit $qtyStep")

            if (proposal.side == "SELL") {
                val availableBase = usableBalance(balances.optJSONObject(baseAsset))
                if (availableBase + 1e-12 < qtyNum) {
                    throw IllegalStateException("Solde $baseAsset insuffisant : ${fmt(availableBase)} disponible")
                }
            }

            if (proposal.orderType == "MARKET") {
                val px = currentPrice(proposal.symbol)
                estimatedNotional = qtyNum * px
                ensureWithinSafetyCap(estimatedNotional, "Valeur de vente estimée")
                ensureLocalMinimum(estimatedNotional)
                body.put("qty", requestedQty)
                body.put("marketUnit", "baseCoin")
                body.put("timeInForce", "IOC")
            } else {
                val rawPrice = proposal.limitPrice ?: throw IllegalStateException("Prix LIMIT manquant")
                requestedPrice = floorToStep(rawPrice, tickSize)
                val priceNum = requestedPrice.toDoubleOrNull() ?: 0.0
                if (priceNum <= 0.0) throw IllegalStateException("Prix LIMIT invalide")
                estimatedNotional = qtyNum * priceNum
                ensureWithinSafetyCap(estimatedNotional, "Valeur LIMIT")
                ensureLocalMinimum(estimatedNotional)
                if (proposal.side == "BUY") {
                    val availableUsdc = usableBalance(balances.optJSONObject("USDC"))
                    if (availableUsdc + 1e-9 < estimatedNotional) {
                        throw IllegalStateException("Solde USDC insuffisant : ${fmt(availableUsdc)} USDC disponibles")
                    }
                }
                body.put("qty", requestedQty)
                body.put("price", requestedPrice)
                body.put("timeInForce", "GTC")
            }
        }

        // La règle locale reste > 1 USDC et <= 10 USDC. Les contraintes spécifiques
        // d'une paire sont laissées à l'API Bybit EU, qui reste l'autorité finale.
        var recoveredState: JSONObject? = null
        val created = try {
            signedPost("/v5/order/create", body)
        } catch (createError: Exception) {
            val recovery = recoverByLinkId(proposal.symbol, deterministicLinkId)
            if (recovery.state != null) {
                recoveredState = recovery.state
                null
            } else if (recovery.lookupSucceeded) {
                throw IllegalStateException("Ordre refusé par Bybit : ${createError.message ?: "raison inconnue"}", createError)
            } else {
                throw BybitExecutionUncertainException(
                    "État Bybit incertain après l'envoi. Ne renvoie pas l'ordre : CHK Crypto doit d'abord vérifier orderLinkId $deterministicLinkId.",
                    createError
                )
            }
        }

        val createdResult = created?.optJSONObject("result")
        val orderId = createdResult?.optString("orderId").orEmpty()
            .ifBlank { recoveredState?.optString("orderId").orEmpty() }
        val linkId = createdResult?.optString("orderLinkId").takeUnless { it.isNullOrBlank() }
            ?: recoveredState?.optString("orderLinkId").takeUnless { it.isNullOrBlank() }
            ?: deterministicLinkId

        val state = recoveredState ?: pollOrderState(proposal.symbol, orderId, linkId)
        val status = state?.optString("orderStatus").takeUnless { it.isNullOrBlank() } ?: "SENT"
        val execQty = state?.optString("cumExecQty")?.toDoubleOrNull() ?: 0.0
        val execValue = state?.optString("cumExecValue")?.toDoubleOrNull() ?: 0.0
        val avg = state?.optString("avgPrice")?.toDoubleOrNull() ?: 0.0

        return TradeExecutionResult(
            orderId = orderId,
            orderLinkId = linkId,
            orderStatus = status,
            symbol = proposal.symbol,
            side = proposal.side,
            orderType = proposal.orderType,
            requestedQty = requestedQty,
            requestedPrice = requestedPrice,
            executedQty = execQty,
            executedValueUsdc = if (execValue > 0.0) execValue else if (status.equals("Filled", true)) estimatedNotional else 0.0,
            averagePrice = avg
        )
    }

    /** Vérifie un ordre processing sans jamais créer un nouvel ordre. */
    fun reconcile(proposal: TradeProposal): TradeExecutionResult? {
        require(apiKey.isNotBlank() && apiSecret.isNotBlank()) { "Clés Bybit manquantes" }
        syncServerTime()
        val linkId = orderLinkId(proposal.id)
        val state = loadOrderState(proposal.symbol, "", linkId) ?: return null
        return TradeExecutionResult(
            orderId = state.optString("orderId"),
            orderLinkId = state.optString("orderLinkId").ifBlank { linkId },
            orderStatus = state.optString("orderStatus").ifBlank { "SENT" },
            symbol = proposal.symbol,
            side = proposal.side,
            orderType = proposal.orderType,
            requestedQty = state.optString("qty").ifBlank { proposal.baseQuantity?.let(::decimal) ?: "" },
            requestedPrice = state.optString("price").takeIf { it.isNotBlank() },
            executedQty = state.optString("cumExecQty").toDoubleOrNull() ?: 0.0,
            executedValueUsdc = state.optString("cumExecValue").toDoubleOrNull() ?: 0.0,
            averagePrice = state.optString("avgPrice").toDoubleOrNull() ?: 0.0
        )
    }

    private fun ensureNotExpired(proposal: TradeProposal) {
        val raw = proposal.expiresAt ?: return
        val expiry = runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() ?: return
        val now = System.currentTimeMillis() + serverOffsetMs
        if (expiry <= now) throw IllegalStateException("Cette proposition a expiré. Aucun ordre n'a été envoyé.")
    }

    private fun ensureLocalMinimum(notionalUsdc: Double) {
        if (notionalUsdc <= MIN_ORDER_USDC + 1e-9) {
            throw IllegalStateException("La valeur totale doit être supérieure à ${fmt(MIN_ORDER_USDC)} USDC")
        }
    }

    private fun ensureWithinSafetyCap(valueUsdc: Double, label: String) {
        if (valueUsdc > MAX_ORDER_USDC + 1e-9) {
            throw IllegalStateException("$label ${fmt(valueUsdc)} USDC > plafond ${MAX_ORDER_USDC.toInt()} USDC")
        }
    }

    private fun verifySpotTradePermission() {
        val root = signedGet("/v5/user/query-api", linkedMapOf())
        val result = root.optJSONObject("result") ?: throw IllegalStateException("Permissions Bybit indisponibles")
        val readOnly = result.optInt("readOnly", -1)
        val spot = result.optJSONObject("permissions")?.optJSONArray("Spot") ?: JSONArray()
        val permissions = (0 until spot.length()).map { spot.optString(it) }
        if (readOnly != 0 || !permissions.contains("SpotTrade")) {
            throw IllegalStateException("La clé Bybit doit être en lecture-écriture avec permission SpotTrade")
        }
    }

    private fun instrumentInfo(symbol: String): JSONObject {
        val root = publicGet("/v5/market/instruments-info?category=spot&symbol=${encode(symbol)}")
        val item = root.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
            ?: throw IllegalStateException("Paire $symbol introuvable sur Bybit EU")
        if (!item.optString("status").equals("Trading", true)) throw IllegalStateException("$symbol n'est pas tradable actuellement")
        return item
    }

    private fun currentPrice(symbol: String): Double {
        val root = publicGet("/v5/market/tickers?category=spot&symbol=${encode(symbol)}")
        val ticker = root.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
            ?: throw IllegalStateException("Prix $symbol indisponible")
        val last = ticker.optString("lastPrice").toDoubleOrNull() ?: 0.0
        val bid = ticker.optString("bid1Price").toDoubleOrNull() ?: 0.0
        val ask = ticker.optString("ask1Price").toDoubleOrNull() ?: 0.0
        val px = if (last > 0.0) last else if (bid > 0.0 && ask > 0.0) (bid + ask) / 2.0 else maxOf(bid, ask)
        if (px <= 0.0) throw IllegalStateException("Prix $symbol invalide")
        return px
    }

    private fun walletBalances(): JSONObject {
        val root = signedGet("/v5/account/wallet-balance", linkedMapOf("accountType" to "UNIFIED"))
        val coins = root.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)?.optJSONArray("coin") ?: JSONArray()
        return JSONObject().apply {
            for (i in 0 until coins.length()) {
                val c = coins.optJSONObject(i) ?: continue
                put(c.optString("coin").uppercase(), c)
            }
        }
    }

    private fun usableBalance(coin: JSONObject?): Double {
        if (coin == null) return 0.0
        val wallet = coin.optString("walletBalance").toDoubleOrNull() ?: 0.0
        val locked = coin.optString("locked").toDoubleOrNull() ?: 0.0
        val borrowed = coin.optString("spotBorrow").toDoubleOrNull() ?: 0.0
        return maxOf(0.0, wallet - locked - borrowed)
    }

    private data class Recovery(val state: JSONObject?, val lookupSucceeded: Boolean)

    private fun recoverByLinkId(symbol: String, linkId: String): Recovery {
        var lookupSucceeded = false
        val delays = longArrayOf(250L, 700L, 1_500L)
        for (delay in delays) {
            Thread.sleep(delay)
            try {
                val state = loadOrderState(symbol, "", linkId)
                lookupSucceeded = true
                if (state != null) return Recovery(state, true)
            } catch (_: Exception) {
            }
        }
        return Recovery(null, lookupSucceeded)
    }

    private fun pollOrderState(symbol: String, orderId: String, orderLinkId: String): JSONObject? {
        val delays = longArrayOf(250L, 700L, 1_500L, 3_000L)
        var last: JSONObject? = null
        for (delay in delays) {
            Thread.sleep(delay)
            val state = runCatching { loadOrderState(symbol, orderId, orderLinkId) }.getOrNull()
            if (state != null) {
                last = state
                val status = state.optString("orderStatus")
                if (status.equals("Filled", true) || status.equals("Cancelled", true) || status.equals("Rejected", true)) break
            }
        }
        return last
    }

    private fun loadOrderState(symbol: String, orderId: String, orderLinkId: String): JSONObject? {
        fun find(path: String): JSONObject? {
            val params = linkedMapOf("category" to "spot", "symbol" to symbol, "limit" to "20")
            if (orderId.isNotBlank()) params["orderId"] = orderId else params["orderLinkId"] = orderLinkId
            val root = signedGet(path, params)
            return root.optJSONObject("result")?.optJSONArray("list")?.optJSONObject(0)
        }
        return find("/v5/order/realtime") ?: find("/v5/order/history")
    }

    private fun syncServerTime() {
        val root = publicGet("/v5/market/time")
        val server = root.optLong("time", 0L).takeIf { it > 0L }
            ?: root.optJSONObject("result")?.optString("timeSecond")?.toLongOrNull()?.times(1000L)
            ?: System.currentTimeMillis()
        serverOffsetMs = server - System.currentTimeMillis()
    }

    private fun signedGet(path: String, params: LinkedHashMap<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val timestamp = (System.currentTimeMillis() + serverOffsetMs).toString()
        val signature = hmac(apiSecret, timestamp + apiKey + recvWindow + query)
        val url = baseUrl + path + if (query.isBlank()) "" else "?$query"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-BAPI-API-KEY", apiKey)
            setRequestProperty("X-BAPI-TIMESTAMP", timestamp)
            setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
            setRequestProperty("X-BAPI-SIGN", signature)
        }
        return readRoot(connection)
    }

    private fun signedPost(path: String, bodyJson: JSONObject): JSONObject {
        val body = bodyJson.toString()
        val timestamp = (System.currentTimeMillis() + serverOffsetMs).toString()
        val signature = hmac(apiSecret, timestamp + apiKey + recvWindow + body)
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-BAPI-API-KEY", apiKey)
            setRequestProperty("X-BAPI-TIMESTAMP", timestamp)
            setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
            setRequestProperty("X-BAPI-SIGN", signature)
        }
        return try {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            readRoot(connection, disconnect = false)
        } finally {
            connection.disconnect()
        }
    }

    private fun publicGet(pathAndQuery: String): JSONObject {
        val connection = (URL(baseUrl + pathAndQuery).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        return readRoot(connection)
    }

    private fun readRoot(connection: HttpURLConnection, disconnect: Boolean = true): JSONObject {
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Bybit HTTP $code • ${text.take(300)}")
            val root = JSONObject(text)
            val retCode = root.optLong("retCode", 0L)
            if (retCode != 0L) throw IllegalStateException("Bybit $retCode • ${root.optString("retMsg").take(250)}")
            root
        } finally {
            if (disconnect) connection.disconnect()
        }
    }

    private fun floorToStep(value: Double, step: String): String {
        val v = BigDecimal.valueOf(value)
        val s = step.toBigDecimalOrNull() ?: BigDecimal.ZERO
        if (s <= BigDecimal.ZERO) return v.stripTrailingZeros().toPlainString()
        val units = v.divide(s, 0, RoundingMode.FLOOR)
        return units.multiply(s).stripTrailingZeros().toPlainString()
    }

    private fun firstPositiveStep(vararg candidates: String): String {
        return candidates.firstOrNull {
            val value = it.toBigDecimalOrNull()
            value != null && value > BigDecimal.ZERO
        } ?: "0"
    }

    private fun decimal(value: Double): String = BigDecimal.valueOf(value)
        .setScale(8, RoundingMode.DOWN)
        .stripTrailingZeros()
        .toPlainString()

    private fun orderLinkId(id: String): String = "chk-${id.replace("-", "").take(28)}"

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')

    companion object {
        const val MIN_ORDER_USDC = 1.0
        const val MAX_ORDER_USDC = 10.0
    }
}

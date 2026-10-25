package com.chk.binancebybit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Public Bybit EU Spot/USDC MCP tools. No private keys, no order endpoints.
 * All network work must run on the MCP worker thread, never the UI thread.
 */
public final class CryptoMarketMcpTools {
    private static final String BASE = "https://api.bybit.eu";
    private static final int MAX_BYTES = 1_500_000;
    private static final long CACHE_MS = 30_000L;
    private static String instrumentsCache, tickersCache;
    private static long instrumentsAt, tickersAt;

    private CryptoMarketMcpTools() {}

    public static JSONObject execute(String action, JSONObject args) throws Exception {
        if (args == null) args = new JSONObject();
        switch (action) {
            case "bybit_spot_universe": return universe();
            case "bybit_spot_tickers": return tickers();
            case "bybit_market_scan": return marketScan();
            case "bybit_kline": return kline(args);
            case "bybit_orderbook": return orderbook(args);
            case "bybit_indicators": return indicators(args);
            case "bybit_exchange_time": return new JSONObject().put("ok", true)
                    .put("exchange", "Bybit EU")
                    .put("response", request("/v5/market/time", 0));
            default: throw new IllegalArgumentException("unsupported_public_market_action");
        }
    }

    /** Bybit spot instruments do NOT support limit or cursor pagination. */
    private static JSONObject universe() throws Exception {
        JSONArray list = cached("/v5/market/instruments-info?category=spot", true)
                .getJSONObject("result").getJSONArray("list");
        JSONArray markets = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null || !"USDC".equalsIgnoreCase(p.optString("quoteCoin"))
                    || !"Trading".equalsIgnoreCase(p.optString("status"))) continue;
            markets.put(new JSONObject()
                    .put("symbol", p.optString("symbol"))
                    .put("baseCoin", p.optString("baseCoin"))
                    .put("quoteCoin", "USDC")
                    .put("status", p.optString("status")));
        }
        return new JSONObject().put("ok", true).put("exchange", "Bybit EU")
                .put("market", "spot").put("quote", "USDC")
                .put("total", markets.length()).put("allPairs", true)
                .put("retrievedAt", System.currentTimeMillis()).put("pairs", markets);
    }

    private static JSONObject tickers() throws Exception {
        JSONArray list = cached("/v5/market/tickers?category=spot", false)
                .getJSONObject("result").getJSONArray("list");
        JSONArray values = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null || !p.optString("symbol").endsWith("USDC")) continue;
            values.put(new JSONObject()
                    .put("symbol", p.optString("symbol"))
                    .put("lastPrice", p.optString("lastPrice"))
                    .put("price24hPcnt", p.optString("price24hPcnt"))
                    .put("turnover24h", p.optString("turnover24h"))
                    .put("volume24h", p.optString("volume24h"))
                    .put("bid1Price", p.optString("bid1Price"))
                    .put("ask1Price", p.optString("ask1Price")));
        }
        return new JSONObject().put("ok", true).put("source", "Bybit EU public tickers")
                .put("total", values.length()).put("allPairs", true)
                .put("retrievedAt", System.currentTimeMillis()).put("tickers", values);
    }

    private static JSONObject marketScan() throws Exception {
        JSONArray all = tickers().getJSONArray("tickers");
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) list.add(all.getJSONObject(i));
        Collections.sort(list, (a, b) -> Double.compare(
                number(b.optString("turnover24h")), number(a.optString("turnover24h"))));
        JSONArray ranked = new JSONArray();
        for (JSONObject item : list) {
            ranked.put(new JSONObject(item.toString())
                    .put("change24hPercent", number(item.optString("price24hPcnt")) * 100.0));
        }
        return new JSONObject().put("ok", true).put("source", "Bybit EU Spot USDC")
                .put("total", ranked.length()).put("scannedAll", true)
                .put("sort", "turnover24h_desc").put("tickers", ranked)
                .put("retrievedAt", System.currentTimeMillis());
    }

    private static String symbol(JSONObject args) {
        String s = args.optString("symbol", "").trim().toUpperCase(Locale.ROOT);
        if (!s.matches("^[A-Z0-9]{2,24}USDC$") || "USDCUSDC".equals(s))
            throw new IllegalArgumentException("symbol_must_be_spot_USDC");
        return s;
    }

    private static String interval(JSONObject args) {
        String v = args.optString("interval", "60").trim();
        if (!(v.equals("1") || v.equals("5") || v.equals("15") ||
                v.equals("60") || v.equals("240") || v.equals("D")))
            throw new IllegalArgumentException("invalid_interval_1_5_15_60_240_D");
        return v;
    }

    private static JSONObject kline(JSONObject args) throws Exception {
        String s = symbol(args), t = interval(args);
        int n = Math.max(30, Math.min(200, args.optInt("limit", 100)));
        JSONObject response = request("/v5/market/kline?category=spot&symbol=" + s +
                "&interval=" + t + "&limit=" + n, 0);
        JSONArray candles = response.getJSONObject("result").getJSONArray("list");
        return new JSONObject().put("ok", true).put("symbol", s).put("interval", t)
                .put("exchange", "Bybit EU").put("count", candles.length())
                .put("newestFirst", true).put("candles", candles);
    }

    private static JSONObject orderbook(JSONObject args) throws Exception {
        String s = symbol(args);
        JSONObject book = request("/v5/market/orderbook?category=spot&symbol=" + s +
                "&limit=25", 0).getJSONObject("result");
        return new JSONObject().put("ok", true).put("symbol", s)
                .put("exchange", "Bybit EU").put("depth", 25)
                .put("bids", book.optJSONArray("b"))
                .put("asks", book.optJSONArray("a"))
                .put("exchangeTimestamp", book.optLong("ts"));
    }

    /**
     * Last CLOSED candle only. Indicators are historical measurements, not trading instructions.
     * Bybit returns newest-first, including a potentially still-open candle at index 0.
     */
    private static JSONObject indicators(JSONObject args) throws Exception {
        String s = symbol(args), t = interval(args);
        JSONArray raw = request("/v5/market/kline?category=spot&symbol=" + s +
                "&interval=" + t + "&limit=130", 0)
                .getJSONObject("result").getJSONArray("list");
        if (raw.length() < 40) throw new IllegalStateException("not_enough_candles");
        int n = raw.length() - 1; // exclude newest (open) candle
        double[] closes = new double[n], highs = new double[n], lows = new double[n];
        long candleTime = 0;
        for (int i = 0; i < n; i++) {
            JSONArray row = raw.getJSONArray(raw.length() - 1 - i);
            closes[i] = number(row.optString(4));
            highs[i] = number(row.optString(2));
            lows[i] = number(row.optString(3));
            if (closes[i] <= 0 || highs[i] < lows[i]) throw new IllegalStateException("invalid_candle_data");
            if (i == n - 1) candleTime = Long.parseLong(row.optString(0));
        }
        double ema12 = ema(closes, 12), ema26 = ema(closes, 26);
        double[] macd = new double[n];
        double e12 = closes[0], e26 = closes[0];
        for (int i = 0; i < n; i++) {
            e12 = (2.0 / 13.0) * closes[i] + (11.0 / 13.0) * e12;
            e26 = (2.0 / 27.0) * closes[i] + (25.0 / 27.0) * e26;
            macd[i] = e12 - e26;
        }
        double signal = ema(macd, 9);
        double gains = 0, losses = 0, trueRange = 0;
        for (int i = n - 14; i < n; i++) {
            double change = closes[i] - closes[i - 1];
            gains += Math.max(0, change);
            losses += Math.max(0, -change);
            trueRange += Math.max(highs[i] - lows[i],
                    Math.max(Math.abs(highs[i] - closes[i - 1]),
                            Math.abs(lows[i] - closes[i - 1])));
        }
        double rsi = losses == 0 ? (gains == 0 ? 50 : 100) : 100 - 100 / (1 + gains / losses);
        double sum = 0;
        for (int i = n - 20; i < n; i++) sum += closes[i];
        double sma20 = sum / 20.0, variance = 0;
        for (int i = n - 20; i < n; i++) variance += Math.pow(closes[i] - sma20, 2);
        double sd = Math.sqrt(variance / 20.0);
        return new JSONObject().put("ok", true).put("symbol", s).put("interval", t)
                .put("source", "Bybit EU public kline").put("readOnly", true)
                .put("candlesUsed", n).put("closedCandleStartMs", candleTime)
                .put("lastClose", closes[n - 1]).put("ema12", ema12).put("ema26", ema26)
                .put("rsi14", rsi).put("macd", macd[n - 1])
                .put("macdSignal9", signal).put("macdHistogram", macd[n - 1] - signal)
                .put("atr14Approx", trueRange / 14.0)
                .put("bollingerMiddle20", sma20)
                .put("bollingerUpper20", sma20 + 2 * sd)
                .put("bollingerLower20", sma20 - 2 * sd)
                .put("note", "Indicateurs indicatifs basés sur bougies clôturées, sans ordre de trading.");
    }

    private static double ema(double[] data, int period) {
        double alpha = 2.0 / (period + 1.0), current = data[0];
        for (int i = 1; i < data.length; i++) current += alpha * (data[i] - current);
        return current;
    }

    private static double number(String value) {
        try { return Double.parseDouble(value); }
        catch (Exception ignored) { return 0.0; }
    }

    private static synchronized JSONObject cached(String path, boolean instruments) throws Exception {
        long now = System.currentTimeMillis();
        String cache = instruments ? instrumentsCache : tickersCache;
        long cachedAt = instruments ? instrumentsAt : tickersAt;
        if (cache != null && now - cachedAt < CACHE_MS) return new JSONObject(cache);
        JSONObject fresh = request(path, 0);
        if (instruments) { instrumentsCache = fresh.toString(); instrumentsAt = now; }
        else { tickersCache = fresh.toString(); tickersAt = now; }
        return fresh;
    }

    private static JSONObject request(String path, int ignored) throws Exception {
        if (!path.startsWith("/v5/market/") || path.contains("..") || path.contains("#"))
            throw new SecurityException("forbidden_bybit_endpoint");
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(12_000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept", "application/json");
        try {
            int status = conn.getResponseCode();
            if (status != 200) throw new java.io.IOException("Bybit EU HTTP " + status);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (out.size() + read > MAX_BYTES) throw new java.io.IOException("bybit_response_too_large");
                    out.write(chunk, 0, read);
                }
                JSONObject result = new JSONObject(out.toString("UTF-8"));
                if (result.optInt("retCode", -1) != 0)
                    throw new java.io.IOException("Bybit EU API code " + result.optInt("retCode", -1));
                return result;
            }
        } finally {
            conn.disconnect();
        }
    }
}

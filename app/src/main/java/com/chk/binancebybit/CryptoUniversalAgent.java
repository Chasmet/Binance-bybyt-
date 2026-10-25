package com.chk.binancebybit;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Isolated, opt-in CHK Crypto agent for the user's own universal MCP PC.
 * Private Binance/Bybit API keys NEVER leave SecureStore.
 * Supported remote actions are strictly read-only; orders, cancellation,
 * transfer, withdrawal, and signing are impossible through this agent.
 */
public final class CryptoUniversalAgent {
    public static final String BASE_URL = "https://chk-universal-1.taild9e4de.ts.net";
    public static final String MCP_URL = BASE_URL + "/mcp";
    public static final String APP_NAME = "CHK Crypto Analyse 2";
    private static final String PREFS = "chk_universal_crypto";
    private static final String SECRET_KEY = "universal_crypto_app_secret";
    private static final int MAX_HOLDINGS = 35;

    private CryptoUniversalAgent() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isPaired(Context context) {
        String id = prefs(context).getString("app_id", "");
        String secret = new SecureStore(context).get(SECRET_KEY);
        return id != null && id.matches("^[a-f0-9]{36}$")
                && secret != null && secret.startsWith("chkapp_");
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean("enabled", false) && isPaired(context);
    }

    public static String status(Context context) {
        SharedPreferences p = prefs(context);
        if (!isPaired(context)) return "Non appairé • mot de passe du serveur PC requis une seule fois";
        long seen = p.getLong("last_poll_ms", 0);
        String state = isEnabled(context) ? "Surveillance activée" : "Surveillance arrêtée";
        if (seen > 0) state += " • Dernier contact : " + new java.text.SimpleDateFormat(
                "dd/MM HH:mm", java.util.Locale.FRANCE).format(new java.util.Date(seen));
        String error = p.getString("last_error", "");
        if (error != null && !error.isEmpty()) state += "\nDernière erreur : " + error;
        return state;
    }

    /** Password is used transiently; only the app-scoped secret is stored. */
    public static void pair(Context context, String password) throws Exception {
        if (password == null || password.length() < 16)
            throw new IllegalArgumentException("Copie le mot de passe administrateur depuis CHK SERVEUR PC UNIVERSEL.");
        JSONObject login = request("POST", "/api/login",
                new JSONObject().put("password", password), null);
        String ownerToken = login.optString("access_token");
        if (ownerToken.isEmpty()) throw new IllegalStateException("Authentification PC refusée.");
        JSONArray abilities = new JSONArray()
                .put(new JSONObject().put("name", "portfolio_snapshot").put("readOnly", true)
                        .put("description", "Lecture des derniers portefeuilles locaux, sans clés API"))
                .put(new JSONObject().put("name", "tracking_status").put("readOnly", true)
                        .put("description", "État du Tracking local Binance et Bybit, sans trading"));
        JSONObject registration = request("POST", "/api/apps",
                new JSONObject().put("name", APP_NAME).put("capabilities", abilities), ownerToken);
        String id = registration.getString("id");
        String secret = registration.getString("secret");
        if (!id.matches("^[a-f0-9]{36}$") || !secret.startsWith("chkapp_"))
            throw new IllegalStateException("L'identité Android fournie par le serveur est invalide.");
        new SecureStore(context).put(SECRET_KEY, secret);
        prefs(context).edit().putString("app_id", id).putString("last_error", "").apply();
    }

    public static void enable(Context context, boolean enabled) {
        if (enabled && !isPaired(context)) throw new IllegalStateException("Appairage nécessaire.");
        prefs(context).edit().putBoolean("enabled", enabled).apply();
    }

    /** Runs from the service's dedicated background worker, never UI thread. */
    public static void pollOnce(Context context) throws Exception {
        if (!isEnabled(context)) return;
        String id = prefs(context).getString("app_id", "");
        String secret = new SecureStore(context).get(SECRET_KEY);
        if (id == null || !id.matches("^[a-f0-9]{36}$") || !secret.startsWith("chkapp_")) {
            throw new IllegalStateException("Identité Android non appairée.");
        }
        JSONObject received = request("GET", "/api/agent/" + id + "/poll", null, secret);
        prefs(context).edit().putLong("last_poll_ms", System.currentTimeMillis())
                .remove("last_error").apply();
        JSONObject task = received.optJSONObject("task");
        if (task == null) return;
        String taskId = task.optString("id");
        if (!taskId.matches("^[a-f0-9]{36}$")) throw new IllegalStateException("Identifiant de tâche invalide.");
        String action = task.optString("action");
        JSONObject result;
        try {
            switch (action) {
                case "portfolio_snapshot":
                    result = portfolioSnapshot(context);
                    break;
                case "tracking_status":
                    result = trackingStatus(context);
                    break;
                default:
                    result = new JSONObject().put("ok", false).put("error", "crypto_action_forbidden");
            }
        } catch (Exception error) {
            result = new JSONObject().put("ok", false).put("error", "crypto_read_failed");
        }
        // All actions only READ existing Android values; none can trade.
        request("POST", "/api/agent/" + id + "/result",
                new JSONObject().put("task_id", taskId).put("result", result), secret);
    }

    static JSONObject portfolioSnapshot(Context context) throws Exception {
        SharedPreferences p = context.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE);
        JSONObject response = new JSONObject().put("ok", true)
                .put("kind", "cached_portfolio_snapshot")
                .put("readOnly", true)
                .put("capturedAt", System.currentTimeMillis())
                .put("note", "Dernières données enregistrées sur Android, pas un portefeuille live. Aucun secret API transmis.");
        for (String exchange : new String[]{"binance", "bybit"}) {
            String raw = p.getString("v4_snapshot_" + exchange, "");
            JSONObject summary = new JSONObject().put("available", false);
            if (raw != null && !raw.isEmpty()) {
                JSONObject local = new JSONObject(raw);
                JSONArray original = local.optJSONArray("holdings");
                JSONArray items = new JSONArray();
                int count = original == null ? 0 : original.length();
                for (int i = 0; i < count && i < MAX_HOLDINGS; i++) {
                    JSONObject h = original.optJSONObject(i);
                    if (h == null) continue;
                    items.put(new JSONObject().put("asset", h.optString("asset"))
                            .put("amount", h.optDouble("amount"))
                            .put("priceUsdt", h.optDouble("priceUsdt"))
                            .put("valueUsdt", h.optDouble("valueUsdt")));
                }
                summary = new JSONObject().put("available", true)
                        .put("capturedAt", local.optLong("capturedAt"))
                        .put("totalUsdt", local.optDouble("totalUsdt"))
                        .put("totalEur", local.optDouble("totalEur"))
                        .put("holdingsTotal", count)
                        .put("holdingsIncluded", items.length())
                        .put("holdingsTruncated", count > MAX_HOLDINGS)
                        .put("holdings", items);
            }
            response.put(exchange, summary);
        }
        return response;
    }

    static JSONObject trackingStatus(Context context) throws Exception {
        SharedPreferences runtime = context.getSharedPreferences("chk_tracking_runtime", Context.MODE_PRIVATE);
        boolean enabled;
        TrackingStore store = new TrackingStore(context);
        try { enabled = store.enabled(); }
        finally { store.close(); }
        return new JSONObject().put("ok", true).put("readOnly", true)
                .put("kind", "tracking_status")
                .put("trackingEnabled", enabled)
                .put("trackedAssetCount", runtime.getInt("tracked_asset_count", 0))
                .put("binanceConnected", runtime.getBoolean("binance_connected", false))
                .put("bybitConnected", runtime.getBoolean("bybit_connected", false))
                .put("observedAt", System.currentTimeMillis());
    }

    private static JSONObject request(String method, String path, JSONObject body, String bearer) throws Exception {
        if (!path.startsWith("/api/") || path.contains("..") || path.contains("?"))
            throw new SecurityException("Chemin MCP Android interdit.");
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = conn.getOutputStream()) { out.write(bytes); }
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300)
                throw new java.io.IOException("Serveur universel : HTTP " + status + " (vérifie connexion et mot de passe).");
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > 1_000_000) throw new java.io.IOException("Réponse trop volumineuse.");
                    out.write(buf, 0, n);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { conn.disconnect(); }
    }

    public static void recordFailure(Context context, Throwable error) {
        String detail = error.getMessage() == null ? "Échec réseau" : error.getMessage();
        prefs(context).edit().putString("last_error", detail.length() > 120 ? detail.substring(0, 120) : detail).apply();
    }
}

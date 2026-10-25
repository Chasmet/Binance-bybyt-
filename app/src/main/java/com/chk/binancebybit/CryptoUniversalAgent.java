package com.chk.binancebybit;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in, read-only Android worker for CHK Universal Control on the owner's PC.
 * Never exports exchange API keys, Android Keystore secrets, or trade operations.
 * The pairing invite is one-time and is NOT the ChatGPT OAuth admin password.
 */
public final class CryptoUniversalAgent {
    public static final String BASE = "https://chk-universal-1.taild9e4de.ts.net";
    public static final String MCP = BASE + "/mcp";
    private static final String PREFS = "chk_crypto_universal_agent";
    private static final String APP_ID = "app_id";
    private static final String SECRET = "universal_agent_secret";
    private static ScheduledExecutorService worker;
    private static final Object LOCK = new Object();
    private CryptoUniversalAgent() {}

    public static boolean paired(Context context) {
        return !prefs(context).getString(APP_ID, "").isEmpty()
            && !new SecureStore(context).get(SECRET).isEmpty();
    }

    public static String status(Context context) {
        SharedPreferences p = prefs(context);
        if (!paired(context)) return "Non connecté — invitation à saisir";
        long last = p.getLong("last_contact", 0);
        String detail = p.getString("last_error", "");
        if (!detail.isEmpty()) return "Connecté, serveur indisponible : " + detail;
        if (last == 0) return "Jumelé — attente du premier contact";
        return "Jumelé — dernier contact il y a " + (System.currentTimeMillis() - last) / 1000 + " s";
    }

    public static void pair(Context context, String invite) throws Exception {
        if (invite == null || !invite.trim().startsWith("chkinvite_") ||
                invite.trim().length() > 160) throw new IllegalArgumentException("Invitation CHK invalide");
        JSONObject payload = new JSONObject();
        payload.put("name", "CHK Crypto Android");
        payload.put("invite", invite.trim());
        JSONArray caps = new JSONArray();
        caps.put(new JSONObject().put("name", "crypto_portfolio").put("readOnly", true)
                .put("description", "Portefeuille local mis en cache, jamais les identifiants API"));
        caps.put(new JSONObject().put("name", "crypto_tracking").put("readOnly", true)
                .put("description", "État du suivi crypto local"));
        payload.put("capabilities", caps);
        JSONObject result = request("POST", "/api/onboard", payload, "");
        String id = result.optString("id", "");
        String secret = result.optString("secret", "");
        if (!id.matches("[a-f0-9]{36}") || !secret.startsWith("chkapp_"))
            throw new IllegalStateException("Enregistrement refusé par le serveur");
        new SecureStore(context).put(SECRET, secret);
        prefs(context).edit().putString(APP_ID, id).putBoolean("active", true)
                .remove("last_error").apply();
        start(context);
    }

    public static void disconnect(Context context) {
        prefs(context).edit().remove(APP_ID).putBoolean("active", false)
                .remove("last_error").apply();
        new SecureStore(context).put(SECRET, "");
        // Registration on the PC persists: server-side revoke if device is lost.
    }

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        if (!paired(app) || !prefs(app).getBoolean("active", true)) return;
        synchronized (LOCK) {
            if (worker != null && !worker.isShutdown()) return;
            worker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CHK-Crypto-Universal-Agent");
                t.setDaemon(true);
                return t;
            });
            worker.scheduleWithFixedDelay(() -> {
                if (!paired(app) || !prefs(app).getBoolean("active", true)) return;
                try {
                    pollOnce(app);
                    prefs(app).edit().putLong("last_contact", System.currentTimeMillis())
                            .remove("last_error").apply();
                } catch (Exception e) {
                    prefs(app).edit().putString("last_error", "connexion ou réseau").apply();
                }
            }, 0, 30, TimeUnit.SECONDS);
        }
    }

    public static void checkNow(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            if (!paired(app)) return;
            try {
                pollOnce(app);
                prefs(app).edit().putLong("last_contact", System.currentTimeMillis())
                        .remove("last_error").apply();
            } catch (Exception e) {
                prefs(app).edit().putString("last_error", "connexion ou réseau").apply();
            }
        }, "CHK-Crypto-MCP-Check").start();
    }

    private static void pollOnce(Context context) throws Exception {
        String id = prefs(context).getString(APP_ID, "");
        String secret = new SecureStore(context).get(SECRET);
        if (!id.matches("[a-f0-9]{36}") || !secret.startsWith("chkapp_")) return;
        JSONObject poll = request("GET", "/api/agent/" + id + "/poll", null, secret);
        JSONObject job = poll.optJSONObject("task");
        if (job == null) return;
        String action = job.optString("action");
        JSONObject result;
        // An agent cannot execute trades even if a malicious job is enqueued.
        if ("crypto_portfolio".equals(action)) result = portfolio(context);
        else if ("crypto_tracking".equals(action)) result = tracking(context);
        else result = new JSONObject().put("ok", false).put("error", "read_only_action_not_allowed");
        JSONObject reply = new JSONObject().put("task_id", job.optString("id")).put("result", result);
        request("POST", "/api/agent/" + id + "/result", reply, secret);
    }

    private static JSONObject portfolio(Context context) throws Exception {
        SharedPreferences p = context.getSharedPreferences("chk_workspace", Context.MODE_PRIVATE);
        JSONObject root = new JSONObject().put("ok", true).put("source", "device_cached_wallet_only")
                .put("read_only", true);
        for (String venue : new String[]{"binance", "bybit"}) {
            String snapshot = p.getString("v4_snapshot_" + venue, "");
            if (snapshot.isEmpty()) snapshot = p.getString(venue.equals("binance") ? "last_snapshot" : "bybit_last_snapshot", "");
            JSONObject safe = new JSONObject().put("connected_snapshot", false);
            if (!snapshot.isEmpty()) {
                JSONObject cached = new JSONObject(snapshot);
                safe.put("connected_snapshot", true);
                safe.put("capturedAt", cached.optLong("capturedAt"));
                safe.put("totalEur", cached.optDouble("totalEur", 0));
                safe.put("totalUsdt", cached.optDouble("totalUsdt", 0));
                JSONArray holdings = new JSONArray();
                JSONArray source = cached.optJSONArray("holdings");
                if (source != null) for (int i = 0; i < Math.min(120, source.length()); i++) {
                    JSONObject row = source.optJSONObject(i);
                    if (row == null) continue;
                    holdings.put(new JSONObject().put("asset", row.optString("asset"))
                            .put("amount", row.optDouble("amount"))
                            .put("valueUsdt", row.optDouble("valueUsdt")));
                }
                safe.put("holdings", holdings);
            }
            root.put(venue, safe);
        }
        return root;
    }

    private static JSONObject tracking(Context context) throws Exception {
        SharedPreferences p = context.getSharedPreferences("chk_tracking_runtime", Context.MODE_PRIVATE);
        return new JSONObject().put("ok", true).put("source", "device_tracking_summary")
                .put("read_only", true)
                .put("tracking_enabled", new TrackingStore(context).enabled())
                .put("tracked_assets", p.getInt("tracked_asset_count", 0))
                .put("binance_connected", p.getBoolean("binance_connected", false))
                .put("bybit_connected", p.getBoolean("bybit_connected", false))
                .put("capturedAt", System.currentTimeMillis());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject request(String method, String path, JSONObject body, String bearer) throws Exception {
        // Fixed origin and server routes: no user-supplied URL, no insecure HTTP.
        if (!path.startsWith("/api/agent/") && !path.equals("/api/onboard"))
            throw new IllegalArgumentException("Route non autorisée");
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = "";
            if (stream != null) try (InputStream in = stream) {
                byte[] bytes = new byte[32768];
                int n = in.read(bytes);
                response = n > 0 ? new String(bytes, 0, n, StandardCharsets.UTF_8) : "";
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("Serveur HTTP " + status);
            return new JSONObject(response);
        } finally { connection.disconnect(); }
    }
}

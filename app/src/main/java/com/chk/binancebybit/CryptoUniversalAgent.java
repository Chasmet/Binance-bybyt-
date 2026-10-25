package com.chk.binancebybit;

import android.content.Context;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.Build;
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

    public static boolean enabled(Context context) {
        return prefs(context).getBoolean("active", false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("active", enabled).apply();
        if (enabled) {
            scheduleBackground(context);
            start(context);
        } else {
            JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if(scheduler!=null)scheduler.cancel(9861);
            synchronized (LOCK) {
                if(worker!=null){worker.shutdownNow();worker=null;}
            }
        }
    }

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
        caps.put(new JSONObject().put("name", "crypto_chart_state").put("readOnly", true)
                .put("description", "Lecture du graphique CHK Crypto actuel"));
        caps.put(new JSONObject().put("name", "crypto_device_status").put("readOnly", true)
                .put("description", "Etat de la connexion et de l'agent Android"));
        caps.put(new JSONObject().put("name", "crypto_chart_control").put("readOnly", false)
                .put("description", "Pilotage non financier du graphique soumis à approbation locale"));
        caps.put(new JSONObject().put("name", "crypto_alerts").put("readOnly", true)
                .put("description", "Lecture des alertes de prix déjà configurées"));
        caps.put(new JSONObject().put("name", "crypto_bot_status").put("readOnly", true)
                .put("description", "Lecture de l'état du bot et des règles locales"));
        caps.put(new JSONObject().put("name", "crypto_app_status").put("readOnly", true)
                .put("description", "Version de l'APK et état de connexion Internet"));
        payload.put("capabilities", caps);
        JSONObject result = request("POST", "/api/onboard", payload, "");
        String id = result.optString("id", "");
        String secret = result.optString("secret", "");
        if (!id.matches("[a-f0-9]{36}") || !secret.startsWith("chkapp_"))
            throw new IllegalStateException("Enregistrement refusé par le serveur");
        new SecureStore(context).put(SECRET, secret);
        prefs(context).edit().putString(APP_ID, id).putBoolean("active", true)
                .remove("last_error").apply();
        scheduleBackground(context);
        start(context);
    }

    public static void disconnect(Context context) {
        prefs(context).edit().remove(APP_ID).putBoolean("active", false)
                .remove("last_error").apply();
        new SecureStore(context).put(SECRET, "");
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler!=null)scheduler.cancel(9861);
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
        new Thread(() -> runOnce(app), "CHK-Crypto-MCP-Check").start();
    }
    static void runOnce(Context app) {
        if (!paired(app)) return;
        try {
            pollOnce(app);
            prefs(app).edit().putLong("last_contact", System.currentTimeMillis())
                    .remove("last_error").apply();
        } catch (Exception e) {
            prefs(app).edit().putString("last_error", "connexion ou réseau").apply();
        }
    }
    /**
     * Android JobScheduler is opportunistic: no exact 24/7 guarantee. The
     * foreground Tracking service keeps the app process alive when enabled.
     */
    public static void scheduleBackground(Context context) {
        if (!paired(context)) return;
        try {
            JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if(scheduler==null)return;
            JobInfo job=new JobInfo.Builder(9861,new ComponentName(context,CryptoUniversalJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(15L*60*1000)
                    .build();
            scheduler.schedule(job);
        }catch(Exception ignored) { /* Background limits are not fatal */ }
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
        // A command-specific validation error is acknowledged, not left forever leased.
        try {
            if ("crypto_portfolio".equals(action)) result = portfolio(context);
            else if ("crypto_tracking".equals(action)) result = tracking(context);
            else if ("crypto_chart_state".equals(action)) result = chartState(context);
            else if ("crypto_device_status".equals(action)) result = deviceStatus(context);
            else if ("crypto_chart_control".equals(action)) result = chartControl(context,job.optJSONObject("args"));
        else if ("crypto_alerts".equals(action)) result = alerts(context);
        else if ("crypto_bot_status".equals(action)) result = botStatus(context);
        else if ("crypto_app_status".equals(action)) result = appStatus(context);
            else result = new JSONObject().put("ok", false).put("error", "action_not_allowed");
        } catch (Exception e) {
            result = new JSONObject().put("ok", false).put("error", "command_validation_failed")
                    .put("detail", e.getClass().getSimpleName());
        }
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

    private static JSONObject chartState(Context context) throws Exception {
        ChartStateStore store = new ChartStateStore(context);
        JSONObject state = store.loadCurrent().toJson();
        return new JSONObject().put("ok", true).put("read_only", true)
                .put("state", state).put("source", "actual_android_chart_preferences")
                .put("capturedAt", System.currentTimeMillis());
    }
    private static JSONObject deviceStatus(Context context) throws Exception {
        return new JSONObject().put("ok", true).put("paired", paired(context))
                .put("agent", status(context)).put("appPackage", context.getPackageName())
                .put("active_when_process_running", true)
                .put("periodic_job_minutes", 15)
                .put("trading_permission", false);
    }
    private static JSONObject chartControl(Context context, JSONObject request) throws Exception {
        if(request==null)throw new IllegalArgumentException("chart_command_missing");
        String action=request.optString("action","");
        JSONObject args=request.optJSONObject("args");
        if(args==null)args=new JSONObject();
        // Only whitelist display/chart actions. Never take account, trading,
        // WebView JS, arbitrary shell, arbitrary URL, or API key parameters.
        ChartStateStore store=new ChartStateStore(context);
        JSONObject chart=store.loadCurrent().toJson();
        JSONObject viewport=chart.optJSONObject("viewport");
        if(viewport==null)viewport=new JSONObject();
        switch(action){
            case "set_symbol": {
                String symbol=args.optString("symbol","").toUpperCase(java.util.Locale.US).replace("/","");
                if(!symbol.matches("[A-Z0-9]{4,32}"))throw new IllegalArgumentException("invalid_symbol");
                chart.put("symbol",symbol);
                break;
            }
            case "set_timeframe": {
                String tf=args.optString("timeframe","");
                if(!java.util.Arrays.asList("1m","3m","5m","15m","30m","1h","2h","4h","6h","12h","1d","1w").contains(tf))
                    throw new IllegalArgumentException("invalid_timeframe");
                chart.put("timeframe",tf);
                break;
            }
            case "set_profile": {
                String profile=args.optString("profile","");
                if(!java.util.Arrays.asList("SCALP","INTRADAY","SWING").contains(profile))
                    throw new IllegalArgumentException("invalid_profile");
                chart.put("profile",profile);
                chart.put("timeframe",profile.equals("SCALP")?"5m":profile.equals("SWING")?"4h":"15m");
                break;
            }
            case "set_indicators":{
                JSONObject ind=args.optJSONObject("indicators");
                if(ind==null||ind.toString().length()>1500)throw new IllegalArgumentException("invalid_indicators");
                // ChartIndicatorConfig validates ranges internally.
                chart.put("indicators",ind);
                break;
            }
            case "zoom_in": viewport.put("visibleCount",Math.max(12,viewport.optInt("visibleCount",100)-20));break;
            case "zoom_out": viewport.put("visibleCount",Math.min(600,viewport.optInt("visibleCount",100)+20));break;
            case "pan_left": viewport.put("offsetFromEnd",Math.min(10000,viewport.optInt("offsetFromEnd",0)+args.optInt("candles",20)));break;
            case "pan_right": viewport.put("offsetFromEnd",Math.max(0,viewport.optInt("offsetFromEnd",0)-args.optInt("candles",20)));break;
            case "go_to_latest": viewport.put("offsetFromEnd",0);break;
            case "set_auto_scale": viewport.put("autoScale",args.optBoolean("enabled",true));break;
            default:throw new IllegalArgumentException("chart_action_not_allowed");
        }
        chart.put("viewport",viewport);
        store.save(ChartSessionState.Companion.fromJson(chart));
        return new JSONObject().put("ok",true).put("action",action)
                .put("saved_chart_state",chart)
                .put("note","Le graphique en cours d'affichage peut nécessiter sa réouverture pour appliquer les paramètres.");
    }

    private static JSONObject alerts(Context context) throws Exception {
        SharedPreferences p=context.getSharedPreferences("chk_workspace",Context.MODE_PRIVATE);
        JSONArray raw=new JSONArray(p.getString("local_market_alerts_v1","[]"));
        JSONArray list=new JSONArray();
        int enabledCount=0;
        for(int i=0;i<Math.min(100,raw.length());i++){
            JSONObject x=raw.optJSONObject(i);
            if(x==null)continue;
            boolean enabled=x.optBoolean("enabled",false);
            if(enabled)enabledCount++;
            list.put(new JSONObject().put("symbol",x.optString("symbol").replaceAll("[^A-Z0-9]",""))
                .put("condition",x.optString("condition")).put("targetPrice",x.optDouble("targetPrice"))
                .put("enabled",enabled).put("lastTriggeredAt",x.optLong("lastTriggeredAt")));
        }
        return new JSONObject().put("ok",true).put("read_only",true)
            .put("source","android_local_alerts").put("total",raw.length())
            .put("enabled",enabledCount).put("alerts",list);
    }

    private static JSONObject botStatus(Context context) throws Exception {
        SharedPreferences p=context.getSharedPreferences("chk_bot_v1",Context.MODE_PRIVATE);
        JSONArray raw=new JSONArray(p.getString("rules_json","[]"));
        JSONArray rules=new JSONArray();
        for(int i=0;i<Math.min(raw.length(),50);i++){
            JSONObject x=raw.optJSONObject(i);
            if(x==null)continue;
            rules.put(new JSONObject().put("name",x.optString("name").substring(0,Math.min(x.optString("name").length(),80)))
                .put("symbol",x.optString("symbol").replaceAll("[^A-Z0-9]",""))
                .put("enabled",x.optBoolean("enabled",false))
                .put("action",x.optString("action").replaceAll("[^A-Z_]","")));
        }
        return new JSONObject().put("ok",true).put("read_only",true)
            .put("source","android_bot_status").put("bot_enabled",p.getBoolean("bot_enabled",false))
            .put("rules_count",raw.length()).put("rules",rules)
            .put("trading_actions_executed",0);
    }

    private static JSONObject appStatus(Context context) throws Exception {
        android.net.ConnectivityManager cm=(android.net.ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean internet=false;
        if(cm!=null){
            android.net.Network network=cm.getActiveNetwork();
            android.net.NetworkCapabilities nc=network==null?null:cm.getNetworkCapabilities(network);
            internet=nc!=null&&nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                &&nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        String version="unknown";
        try { version=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName; }
        catch(Exception ignored){}
        return new JSONObject().put("ok",true).put("read_only",true)
            .put("application","CHK Crypto Android").put("version",version)
            .put("internet",internet).put("paired",paired(context))
            .put("agent_status",status(context)).put("checked_at",System.currentTimeMillis())
            .put("orders_supported",false);
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

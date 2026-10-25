package com.chk.binancebybit;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Explicitly enabled Android worker. Commands are hardcoded read-only. */
public final class ChkUniversalCryptoAgentService extends Service {
    public static final String START="com.chk.binancebybit.UNIVERSAL_START";
    public static final String STOP="com.chk.binancebybit.UNIVERSAL_STOP";
    private static final String CHANNEL="chk_crypto_universal_mcp";
    private ChkUniversalCryptoClient client;
    private ScheduledExecutorService worker;

    @Override public void onCreate() {
        super.onCreate();
        client=new ChkUniversalCryptoClient(this);
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"CHK Crypto • serveur MCP",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&STOP.equals(intent.getAction())) {
            client.enabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if(!client.enabled()||!client.registered()){stopSelf();return START_NOT_STICKY;}
        Notification note=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CHK Crypto — MCP local")
            .setContentText("Agent de lecture seule connecté au serveur de ton PC")
            .setOngoing(true).build();
        startForeground(8142,note);
        if(worker==null) {
            worker=Executors.newSingleThreadScheduledExecutor();
            worker.scheduleWithFixedDelay(this::check,0,18,TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    private void check() {
        if(!client.enabled())return;
        try{
            JSONObject answer=client.poll();
            JSONObject task=answer.optJSONObject("task");
            if(task==null) {client.status("En ligne • attente de demandes ChatGPT");return;}
            String id=task.optString("id"),action=task.optString("action");
            if(!id.matches("[0-9a-f]{36}"))return;
            JSONObject result;
            if("crypto_portfolio".equals(action))result=portfolio();
            else if("crypto_tracking".equals(action))result=tracking();
            else result=new JSONObject().put("ok",false).put("error","action_not_authorized");
            client.report(id,result);
            client.status("Dernière demande traitée : "+action);
        }catch(Exception e) {
            // Never log API keys, session tokens or portfolio details.
            client.status("Serveur inaccessible ou demande en attente");
        }
    }
    private JSONObject portfolio() throws Exception {
        SharedPreferences prefs=getSharedPreferences("chk_workspace",MODE_PRIVATE);
        JSONObject snapshots=new JSONObject();
        for(String exchange:new String[]{"binance","bybit"}) {
            String raw=prefs.getString("v4_snapshot_"+exchange,"");
            if(raw==null||raw.isEmpty()){snapshots.put(exchange,new JSONObject().put("available",false));continue;}
            JSONObject source=new JSONObject(raw),safe=new JSONObject();
            safe.put("available",true);
            safe.put("capturedAt",source.optLong("capturedAt"));
            safe.put("totalUsdt",source.optDouble("totalUsdt"));
            safe.put("totalEur",source.optDouble("totalEur"));
            JSONArray holdings=source.optJSONArray("holdings"),filtered=new JSONArray();
            int count=holdings==null?0:holdings.length();
            for(int i=0;i<Math.min(count,45);i++) {
                JSONObject x=holdings.optJSONObject(i);
                if(x==null)continue;
                filtered.put(new JSONObject()
                    .put("asset",x.optString("asset").replaceAll("[^A-Za-z0-9]",""))
                    .put("amount",x.optDouble("amount"))
                    .put("priceUsdt",x.optDouble("priceUsdt"))
                    .put("valueUsdt",x.optDouble("valueUsdt")));
            }
            safe.put("holdings",filtered);
            safe.put("holdingsCount",count);
            safe.put("truncated",count>45);
            snapshots.put(exchange,safe);
        }
        return new JSONObject().put("ok",true).put("type","crypto_portfolio")
            .put("readOnly",true).put("snapshots",snapshots)
            .put("source","CHK Crypto Android local cache")
            .put("notLive",true);
    }
    private JSONObject tracking() throws Exception {
        SharedPreferences runtime=getSharedPreferences("chk_tracking_runtime",MODE_PRIVATE);
        return new JSONObject().put("ok",true).put("type","crypto_tracking").put("readOnly",true)
            .put("binanceConnected",runtime.getBoolean("binance_connected",false))
            .put("bybitConnected",runtime.getBoolean("bybit_connected",false))
            .put("trackedAssets",runtime.getInt("tracked_asset_count",0))
            .put("checkedAt",System.currentTimeMillis())
            .put("source","CHK Crypto Android local status");
    }
    @Override public void onDestroy() {
        if(worker!=null){worker.shutdownNow();worker=null;}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}

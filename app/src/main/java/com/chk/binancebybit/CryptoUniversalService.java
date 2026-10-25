package com.chk.binancebybit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in foreground relay: only outbound HTTPS to the user's self-hosted PC.
 * It cannot submit orders, sign exchange requests or alter trading policies.
 */
public final class CryptoUniversalService extends Service {
    private static final String CHANNEL = "chk_crypto_universal_monitor";
    private static final int NOTIFICATION_ID = 11842;
    private ScheduledExecutorService scheduler;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (!CryptoUniversalAgent.isEnabled(this)) {
            stopSelf();return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID,notification());
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler=Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t=new Thread(r,"CHK-Universal-Crypto-Reader");
                t.setDaemon(true);return t;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (!CryptoUniversalAgent.isEnabled(getApplicationContext())) {
                        stopSelf();return;
                    }
                    if (!isInternetAvailable()) return;
                    CryptoUniversalAgent.pollOnce(getApplicationContext());
                } catch (Exception e) {
                    CryptoUniversalAgent.recordFailure(getApplicationContext(),e);
                }
            },0,60,TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    private boolean isInternetAvailable() {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(cm==null)return false;
        if(Build.VERSION.SDK_INT>=23){
            NetworkCapabilities capabilities=cm.getNetworkCapabilities(cm.getActiveNetwork());
            return capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        android.net.NetworkInfo info=cm.getActiveNetworkInfo();
        return info!=null&&info.isConnected();
    }
    private Notification notification() {
        Intent view=new Intent(this,CryptoUniversalActivity.class);
        PendingIntent open=PendingIntent.getActivity(this,11842,view,
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26
                ?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.app_icon)
                .setContentTitle("CHK Crypto Analyse 2")
                .setContentText("Suivi MCP en lecture seule • serveur personnel")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW).build();
    }
    private void createChannel() {
        if(Build.VERSION.SDK_INT<26)return;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null)manager.createNotificationChannel(new NotificationChannel(
                CHANNEL,"CHK Crypto - Suivi MCP universel",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public void onDestroy(){
        if(scheduler!=null){scheduler.shutdownNow();scheduler=null;}
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}

    public static void start(Context context) {
        Context app=context.getApplicationContext();
        if(!CryptoUniversalAgent.isEnabled(app))return;
        Intent intent=new Intent(app,CryptoUniversalService.class);
        if(Build.VERSION.SDK_INT>=26)app.startForegroundService(intent);
        else app.startService(intent);
    }
    public static void stop(Context context){
        context.getApplicationContext().stopService(new Intent(context,CryptoUniversalService.class));
    }
}

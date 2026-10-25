package com.chk.binancebybit;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** CHK Crypto worker: only whitelisted read-only tasks; no exchange API keys or trades. */
public final class ChkUniversalCryptoClient {
  public static final String BASE="https://chk-universal-1.taild9e4de.ts.net";
  public static final String MCP=BASE+"/mcp";
  public static final String APP_NAME="CHK Crypto Android";
  private static final String PREF="chk_universal_crypto";
  private final SharedPreferences prefs;
  private final SecureStore secure;
  public ChkUniversalCryptoClient(Context context) {
    prefs=context.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);
    secure=new SecureStore(context.getApplicationContext());
  }
  public String appId(){return prefs.getString("id","");}
  public boolean registered(){return !appId().isEmpty()&&!secure.get("universal_worker").isEmpty();}
  public boolean enabled(){return prefs.getBoolean("enabled",false);}
  public void enabled(boolean on){prefs.edit().putBoolean("enabled",on).apply();}
  public String status(){return prefs.getString("status","Non connecté");}
  public void status(String s){prefs.edit().putString("status",s.substring(0,Math.min(s.length(),120))).apply();}
  private static JSONObject capability(String name) throws Exception {
    return new JSONObject().put("name",name).put("readOnly",true);
  }
  public void connectWithInvitation(String invite) throws Exception {
    if(!invite.matches("chkinvite_[A-Za-z0-9_-]{20,100}"))throw new IllegalArgumentException("Invitation invalide");
    JSONArray caps=new JSONArray().put(capability("crypto_portfolio")).put(capability("crypto_tracking"));
    JSONObject body=new JSONObject().put("name",APP_NAME).put("invite",invite).put("capabilities",caps);
    JSONObject response=call("POST","/api/onboard",body,null);
    String id=response.getString("id"),token=response.getString("secret");
    if(id.isEmpty()||!token.startsWith("chkapp_"))throw new IllegalStateException("Liaison incomplète");
    secure.put("universal_worker",token);
    prefs.edit().putString("id",id).putString("status","Connecté au serveur universel").apply();
  }
  public JSONObject poll() throws Exception {
    if(!registered())throw new IllegalStateException("Connexion requise");
    return call("GET","/api/agent/"+appId()+"/poll",null,secure.get("universal_worker"));
  }
  public void report(String taskId, JSONObject result) throws Exception {
    if(!registered())throw new IllegalStateException("Connexion requise");
    call("POST","/api/agent/"+appId()+"/result",
      new JSONObject().put("task_id",taskId).put("result",result),secure.get("universal_worker"));
  }
  private JSONObject call(String method,String path,JSONObject body,String token)throws Exception{
    if(!path.matches("/api/[A-Za-z0-9/_-]+"))throw new IllegalArgumentException("Route non autorisée");
    HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();
    c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);
    c.setRequestMethod(method);c.setRequestProperty("Accept","application/json");
    if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
    try {
      if(body!=null){byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
        c.setFixedLengthStreamingMode(b.length);
        try(OutputStream o=c.getOutputStream()){o.write(b);}
      }
      int status=c.getResponseCode();
      if(status<200||status>=300)throw new IllegalStateException("HTTP "+status);
      try(InputStream i=c.getInputStream()){
        byte[] b=i.readAllBytes();
        if(b.length>65536)throw new IllegalStateException("Réponse trop longue");
        return new JSONObject(new String(b,StandardCharsets.UTF_8));
      }
    }finally{c.disconnect();}
  }
}

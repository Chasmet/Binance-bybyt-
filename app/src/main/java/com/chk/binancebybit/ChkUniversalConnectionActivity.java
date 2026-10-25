package com.chk.binancebybit;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Self-contained connection screen; does not alter existing crypto screens. */
public final class ChkUniversalConnectionActivity extends Activity {
    private ChkUniversalCryptoClient client;
    private TextView state;
    private EditText invite;
    private Button connect;
    private Button toggle;
    private final int bg=Color.rgb(10,12,15);
    private final int fg=Color.rgb(245,246,248);
    private final int secondary=Color.rgb(159,169,180);
    private final int accent=Color.rgb(57,197,128);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        client=new ChkUniversalCryptoClient(this);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        render();
    }
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String content,int size,int color){
        TextView view=new TextView(this);
        view.setText(content);view.setTextSize(size);view.setTextColor(color);
        view.setPadding(0,dp(8),0,dp(10));
        return view;
    }
    private Button button(String label,LinearLayout root) {
        Button b=new Button(this);
        b.setText(label);b.setAllCaps(false);
        b.setTextColor(Color.BLACK);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(54));
        params.bottomMargin=dp(12);root.addView(b,params);return b;
    }
    private void render(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(bg);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(22),dp(22),dp(30));scroll.addView(root);
        root.addView(text("CHK Crypto • Serveur universel",23,fg));
        root.addView(text("Connexion au MCP de ton PC — ChatGPT « CHK Crypto Analyse 2 »",14,secondary));
        root.addView(text(ChkUniversalCryptoClient.MCP,12,accent));
        button("Copier l'adresse MCP",root).setOnClickListener(v->{
            ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("CHK MCP",ChkUniversalCryptoClient.MCP));
            Toast.makeText(this,"Adresse MCP copiée",Toast.LENGTH_SHORT).show();
        });
        root.addView(text("1. Dans ChatGPT, demande au serveur universel : « Génère une invitation pour CHK Crypto Android ». Colle ici l'invitation à usage unique (15 minutes).",14,fg));
        invite=new EditText(this);
        invite.setSingleLine(true);
        invite.setHint("chkinvite_...");
        invite.setHintTextColor(secondary);
        invite.setTextColor(fg);
        invite.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(invite,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(60)));
        connect=button("Connecter CHK Crypto au serveur PC",root);
        connect.setOnClickListener(v->{
            String code=invite.getText().toString().trim();
            connect.setEnabled(false);
            state.setText("Autorisation de l'application en cours…");
            new Thread(()->{
                try{
                    client.connectWithInvitation(code);
                    runOnUiThread(()->{
                        invite.setText("");
                        state.setText("CHK Crypto enregistré. Active le service ci-dessous.");
                        connect.setEnabled(true);
                        refresh();
                    });
                }catch(Exception e){
                    runOnUiThread(()->{
                        state.setText("Connexion refusée : "+e.getMessage());
                        connect.setEnabled(true);
                    });
                }
            },"CHK-MCP-Enroll").start();
        });
        root.addView(text("2. Active l'agent pour recevoir les demandes de consultation de ChatGPT sur ce téléphone.",14,fg));
        toggle=button("Activer l'agent MCP",root);
        toggle.setOnClickListener(v->{
            if(!client.registered()){
                state.setText("Connecte d'abord CHK Crypto avec une invitation.");
                return;
            }
            boolean enable=!client.enabled();
            client.enabled(enable);
            Intent i=new Intent(this,ChkUniversalCryptoAgentService.class);
            i.setAction(enable?ChkUniversalCryptoAgentService.START:ChkUniversalCryptoAgentService.STOP);
            try{
                if(enable&&Build.VERSION.SDK_INT>=26)startForegroundService(i);
                else startService(i);
                state.setText(enable?"Service de lecture démarré":"Service arrêté");
            }catch(Exception e){
                client.enabled(false);
                state.setText("Impossible de démarrer le service Android : "+e.getClass().getSimpleName());
            }
            refresh();
        });
        state=text("",14,fg);root.addView(state);
        root.addView(text("Sécurité : aucune clé Binance/Bybit n'est communiquée au serveur. Les commandes d'achat, vente et annulation sont interdites ici. Les anciens connecteurs continuent de fonctionner.",13,secondary));
        setContentView(scroll);
        refresh();
    }
    private void refresh(){
        boolean registered=client.registered();
        toggle.setEnabled(registered);
        toggle.setText(client.enabled()?"Désactiver l'agent MCP":"Activer l'agent MCP");
        state.setText((registered?"Enregistré":"Non enregistré")+" • "+
            (client.enabled()?"Agent actif":"Agent arrêté")+"\n"+client.status());
    }
    @Override protected void onResume(){super.onResume();if(state!=null)refresh();}
}

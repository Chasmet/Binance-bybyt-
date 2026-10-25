package com.chk.binancebybit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

/**
 * Isolated user-facing console for CHK Crypto Analyse 2.
 * Does not change the current Binance/Bybit pages or update manager.
 */
public final class CryptoUniversalActivity extends Activity {
    private static final int BG=Color.rgb(10,12,15);
    private static final int SURFACE=Color.rgb(24,28,34);
    private static final int FG=Color.rgb(244,246,248);
    private static final int MUTED=Color.rgb(163,172,182);
    private static final int GREEN=Color.rgb(57,197,128);
    private static final int BLUE=Color.rgb(93,148,255);
    private TextView status;
    private EditText password;
    private Button pairing;
    private Button toggle;
    private Button diagnose;
    private Button visualToggle;
    private TextView visualState;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        drawUi();
    }
    @Override protected void onResume(){
        super.onResume();renderState();
    }
    private GradientDrawable rounded(int fill,int border){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);d.setCornerRadius(dp(16));
        d.setStroke(dp(1),border);return d;
    }
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String value,int size,int color,boolean bold){
        TextView tv=new TextView(this);tv.setText(value);tv.setTextSize(size);tv.setTextColor(color);
        if(bold)tv.setTypeface(null,Typeface.BOLD);
        tv.setPadding(dp(2),dp(4),dp(2),dp(8));return tv;
    }
    private Button button(String label,int background,LinearLayout root, Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(Color.WHITE);
        b.setTextSize(14);b.setBackground(rounded(background,background));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(54));p.topMargin=dp(10);
        root.addView(b,p);b.setOnClickListener(v->action.run());return b;
    }
    private void drawUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(20),dp(20),dp(32));
        scroll.addView(root);setContentView(scroll);
        TextView back=text("‹   Retour",18,MUTED,false);
        back.setOnClickListener(v->finish());root.addView(back);
        root.addView(text("CHK Crypto Analyse 2",25,FG,true));
        root.addView(text("Module sécurisé du serveur MCP personnel",14,MUTED,false));
        root.addView(text(CryptoUniversalAgent.MCP_URL,12,BLUE,false));

        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(rounded(SURFACE,Color.rgb(53,59,67)));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,-2);
        cp.topMargin=dp(18);root.addView(card,cp);
        card.addView(text("Connexion de l'application au PC",19,FG,true));
        status=text("Vérification…",14,MUTED,false);card.addView(status);
        card.addView(text("Une seule fois : copie le mot de passe administrateur dans CHK SERVEUR PC UNIVERSEL, puis colle-le ici.",13,MUTED,false));
        password=new EditText(this);password.setTextColor(FG);password.setHintTextColor(MUTED);
        password.setHint("Mot de passe du serveur PC");
        password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(password,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(55)));
        pairing=button("Appairer CHK Crypto",BLUE,card,()->pair());
        toggle=button("Activer le suivi MCP",GREEN,card,()->toggleMonitoring());
        diagnose=button("Vérifier la connexion maintenant",Color.rgb(61,67,79),card,()->diagnose());
        root.addView(text("Vision et commandes Android",19,FG,true));
        root.addView(text("Mode volontaire : le MCP peut voir UNIQUEMENT CHK Crypto, lire les boutons, appuyer, balayer et remplir les champs ordinaires. Les écrans de trading sensibles, les mots de passe et les clés API sont exclus.",13,MUTED,false));
        visualState=text("Contrôle visuel désactivé",13,MUTED,false);
        root.addView(visualState);
        visualToggle=button("Activer la vision et les gestes CHK",BLUE,root,()->toggleVisual());
        button("Autoriser le service dans les réglages Android",Color.rgb(61,67,79),root,()->{
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        root.addView(text("Après activation, garde CHK Crypto ouvert sur ton téléphone pour observer les actions. Le suivi MCP doit être activé. Les captures demandent Android 11 ou plus. Tu peux couper immédiatement le contrôle ici ou dans Accessibilité.",12,MUTED,false));
        root.addView(text("Réglages et outils",18,FG,true));
        button("Vérifier les mises à jour automatiques",BLUE,root,()->
            InAppUpdateManager.INSTANCE.checkForUpdate(this,true));
        root.addView(text("Vérification GitHub depuis l'application. L'installation d'une nouvelle version demande confirmation Android et conserve les données si la signature est identique.",12,MUTED,false));
        button("Fichiers : lire • importer • exporter",Color.rgb(61,67,79),root,()->
            startActivity(new Intent(this,CryptoFileWorkspaceActivity.class)));
        button("Naviguer sur Internet",Color.rgb(61,67,79),root,()->
            startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://www.bybit.eu/"))));
        root.addView(text("Les fichiers restent protégés par Android. La vision, le clavier et la souris CHK nécessitent un accord séparé dans Accessibilité. L'agent MCP ne touche jamais aux autres applications.",12,MUTED,false));
        root.addView(text("Fonctions du MCP",18,FG,true));
        root.addView(text("• Scanner toutes les paires Spot Bybit EU disponibles\n"+
                "• Analyser les graphiques 1m, 5m, 15m, 1h, 4h et 1j\n"+
                "• RSI, EMA, MACD, ATR, Bollinger et tendances\n"+
                "• Lire les actualités et pages financières publiques\n"+
                "• Consulter le portefeuille local, le tracking et les diagnostics\n"+
                "• Suivi continu uniquement lorsque tu l'actives, avec notification",14,MUTED,false));
        TextView note=text("Sécurité : aucune clé Binance/Bybit n'est envoyée au serveur. "+
            "Aucun achat, vente, retrait, annulation ou réglage d'Auto-Trade n'est exécuté par ce module. "+
            "Le suivi utilise le réseau et consomme de la batterie.",13,MUTED,false);
        note.setBackground(rounded(SURFACE,Color.rgb(53,59,67)));
        note.setPadding(dp(14),dp(12),dp(14),dp(12));root.addView(note);
        renderState();
    }
    private void renderState(){
        if(status==null)return;
        status.setText(CryptoUniversalAgent.status(this));
        boolean paired=CryptoUniversalAgent.isPaired(this);
        pairing.setText(paired?"Réappairer avec le serveur PC":"Appairer CHK Crypto");
        toggle.setEnabled(paired);
        toggle.setText(CryptoUniversalAgent.isEnabled(this)?"Désactiver le suivi permanent":"Activer le suivi permanent");
        toggle.setBackground(rounded(CryptoUniversalAgent.isEnabled(this)?Color.rgb(115,65,65):GREEN,
                CryptoUniversalAgent.isEnabled(this)?Color.rgb(115,65,65):GREEN));
        diagnose.setEnabled(paired);
        if (visualToggle != null && visualState != null) {
            boolean enabled = CryptoVisualAccessibilityService.optedIn(this);
            boolean granted = CryptoVisualAccessibilityService.granted(this);
            visualToggle.setText(enabled ? "Désactiver vision et gestes CHK" : "Activer vision et gestes CHK");
            visualState.setText("Contrôle : " + (enabled ? "ACTIVÉ" : "DÉSACTIVÉ")
                + " • Permission Android : " + (granted ? "ACCORDÉE" : "NON ACCORDÉE")
                + " • Serveur MCP : " + (CryptoUniversalAgent.isEnabled(this) ? "ACTIF" : "ARRÊTÉ"));
        }
    }
    private void toggleVisual() {
        boolean value = !CryptoVisualAccessibilityService.optedIn(this);
        CryptoVisualAccessibilityService.setOptedIn(this, value);
        renderState();
        if (value && !CryptoVisualAccessibilityService.granted(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Autoriser CHK Crypto")
                .setMessage("Android exige ton autorisation. Dans Accessibilité, active le service CHK Crypto. Il ne pourra interagir qu'avec CHK Crypto. Cette autorisation peut être retirée à tout moment.")
                .setPositiveButton("Ouvrir Accessibilité", (d,w) -> startActivity(
                    new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Plus tard", null).show();
        }
    }
    private void pair(){
        String p=password.getText().toString();
        if(p.length()<16){
            Toast.makeText(this,"Colle le mot de passe CHK du PC (16 caractères minimum).",Toast.LENGTH_LONG).show();
            return;
        }
        pairing.setEnabled(false);status.setText("Appairage HTTPS en cours…");
        new Thread(()->{
            try{
                CryptoUniversalAgent.pair(getApplicationContext(),p);
                runOnUiThread(()->{
                    password.setText("");
                    Toast.makeText(this,"Appairage réussi !",Toast.LENGTH_SHORT).show();
                    renderState();
                });
            }catch(Exception e){
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Connexion au PC impossible")
                    .setMessage(e.getMessage()).setPositiveButton("OK",null).show());
            }finally{runOnUiThread(()->{pairing.setEnabled(true);renderState();});}
        },"CHK-Crypto-Pair").start();
    }
    private void toggleMonitoring(){
        if(!CryptoUniversalAgent.isPaired(this))return;
        boolean enable=!CryptoUniversalAgent.isEnabled(this);
        try {
            CryptoUniversalAgent.enable(this,enable);
            if(enable)CryptoUniversalService.start(this);
            else CryptoUniversalService.stop(this);
            Toast.makeText(this,enable?"Suivi en arrière-plan activé":"Suivi arrêté",Toast.LENGTH_SHORT).show();
            renderState();
        }catch(Exception e){
            if(enable)CryptoUniversalAgent.enable(this,false);
            new AlertDialog.Builder(this).setTitle("Suivi non démarré")
                .setMessage(e.getMessage()).setPositiveButton("OK",null).show();
        }
    }
    private void diagnose(){
        diagnose.setEnabled(false);status.setText("Test du serveur MCP…");
        new Thread(()->{
            try{
                CryptoUniversalAgent.testConnection(getApplicationContext());
                runOnUiThread(()->Toast.makeText(this,"Serveur MCP connecté",Toast.LENGTH_SHORT).show());
            }catch(Exception e){
                CryptoUniversalAgent.recordFailure(getApplicationContext(),e);
                runOnUiThread(()->Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show());
            }finally{runOnUiThread(()->{diagnose.setEnabled(true);renderState();});}
        },"CHK-Crypto-Health").start();
    }
}

package com.chk.binancebybit;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Owner-approved connection between CHK Crypto and the Windows universal MCP. */
public final class CryptoUniversalActivity extends Activity {
    private static final int BG = Color.rgb(10,12,15);
    private static final int CARD = Color.rgb(24,28,35);
    private static final int TEXT = Color.rgb(245,247,249);
    private static final int MUTED = Color.rgb(161,171,182);
    private static final int ACCENT = Color.rgb(76,187,156);
    private TextView connection;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        draw();
    }
    @Override protected void onResume() {
        super.onResume();
        CryptoUniversalAgent.start(this);
        if (connection != null) connection.setText(CryptoUniversalAgent.status(this));
    }
    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    private GradientDrawable rounded(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(16));
        return drawable;
    }
    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        v.setPadding(dp(2), dp(8), dp(2), dp(8));
        return v;
    }
    private Button button(String title) {
        Button b = new Button(this);
        b.setText(title); b.setAllCaps(false); b.setTextColor(BG);
        b.setTextSize(15); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(rounded(ACCENT));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52));
        p.setMargins(0,dp(8),0,dp(8)); b.setLayoutParams(p);
        return b;
    }
    private void draw() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(20),dp(20),dp(32));
        scroll.addView(root);
        TextView title = text("CHK Crypto Analyse 2",24,TEXT);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        root.addView(title);
        root.addView(text("Connexion sécurisée à ton serveur universel installé sur ton PC",14,MUTED));
        TextView addr=text(CryptoUniversalAgent.MCP,13,ACCENT);
        addr.setTextIsSelectable(true);root.addView(addr);

        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(14),dp(16),dp(14));
        card.setBackground(rounded(CARD));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);
        cp.setMargins(0,dp(18),0,dp(14));
        root.addView(card,cp);
        card.addView(text("État de la connexion",17,TEXT));
        connection=text(CryptoUniversalAgent.status(this),14,ACCENT);
        card.addView(connection);
        card.addView(text("Accès limité au portefeuille déjà sauvegardé sur le téléphone et à l'état Tracking. Aucune clé Binance/Bybit n'est envoyée. Aucune commande de trading, achat, vente ou annulation.",13,MUTED));

        if (!CryptoUniversalAgent.paired(this)) {
            root.addView(text("1. Dans ChatGPT, demande au connecteur CHK Crypto Analyse 2 de créer une invitation via app_invite.\n2. Colle uniquement l'invitation temporaire ci-dessous. Ce n'est pas ton mot de passe administrateur.",14,TEXT));
            EditText invite = new EditText(this);
            invite.setSingleLine(true);
            invite.setHint("chkinvite_…");
            invite.setTextColor(TEXT);
            invite.setHintTextColor(MUTED);
            invite.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            root.addView(invite);
            Button pair=button("Connecter mon application");
            root.addView(pair);
            pair.setOnClickListener(v -> {
                String value=invite.getText().toString().trim();
                pair.setEnabled(false);
                connection.setText("Connexion et autorisation du téléphone…");
                new Thread(() -> {
                    try {
                        CryptoUniversalAgent.pair(getApplicationContext(),value);
                        runOnUiThread(() -> { Toast.makeText(this,"CHK Crypto connecté au PC",Toast.LENGTH_LONG).show(); draw(); });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            pair.setEnabled(true);
                            connection.setText("Échec du jumelage : " + e.getMessage());
                        });
                    }
                },"CHK-Crypto-Pairing").start();
            });
        } else {
            Button verify=button("Vérifier la connexion");
            root.addView(verify);
            verify.setOnClickListener(v -> {
                CryptoUniversalAgent.checkNow(this);
                connection.setText("Vérification envoyée. Actualise dans quelques secondes.");
            });
            Button disconnect=button("Déconnecter ce téléphone");
            disconnect.setBackground(rounded(Color.rgb(77,84,95)));
            root.addView(disconnect);
            disconnect.setOnClickListener(v -> {
                CryptoUniversalAgent.disconnect(this);
                Toast.makeText(this,"Connexion locale désactivée",Toast.LENGTH_SHORT).show();
                draw();
            });
        }
        TextView note=text("Ton serveur PC doit rester allumé. La communication Android fonctionne tant que le processus CHK Crypto tourne ; Android peut suspendre l'application en arrière-plan. L'app conserve son fonctionnement Binance et Bybit actuel, y compris son système de mise à jour.",12,MUTED);
        root.addView(note);
        setContentView(scroll);
    }
}

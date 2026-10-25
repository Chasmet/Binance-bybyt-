package com.chk.binancebybit;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Preferences for GitHub Release checks and optional APK downloads.
 * Android always asks for installation approval; the signing key stays stable.
 */
@SuppressWarnings("deprecation")
public final class CryptoUpdatePreferencesView extends LinearLayout {
    public CryptoUpdatePreferencesView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(16), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(20,23,28));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(48,54,64));
        setBackground(background);
        LayoutParams margin = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        margin.bottomMargin = dp(12);
        setLayoutParams(margin);

        TextView heading = label("Mises à jour automatiques", 16, Color.WHITE);
        heading.setTypeface(null, Typeface.BOLD);
        addView(heading);

        Switch check = toggle("Rechercher automatiquement les nouvelles versions");
        check.setChecked(InAppUpdateManager.INSTANCE.autoCheckEnabled(context));
        check.setOnCheckedChangeListener((button, enabled) -> {
            InAppUpdateManager.INSTANCE.setAutoCheckEnabled(context, enabled);
        });
        addView(check);

        Switch download = toggle("Télécharger automatiquement les APK signés");
        download.setChecked(InAppUpdateManager.INSTANCE.autoDownloadEnabled(context));
        download.setOnCheckedChangeListener((button, enabled) -> {
            InAppUpdateManager.INSTANCE.setAutoDownloadEnabled(context, enabled);
        });
        addView(download);
        addView(label("Le téléchargement peut utiliser des données mobiles. L'installation est toujours confirmée dans Android. Les clés et réglages existants sont conservés lorsque la signature correspond.",
                12, Color.rgb(153,162,174)));
    }

    private Switch toggle(String title) {
        Switch sw = new Switch(getContext());
        sw.setText(title);
        sw.setTextColor(Color.rgb(244,246,248));
        sw.setTextSize(14);
        sw.setPadding(0, dp(10), 0, dp(10));
        return sw;
    }

    private TextView label(String content, int size, int color) {
        TextView value = new TextView(getContext());
        value.setText(content);
        value.setTextSize(size);
        value.setTextColor(color);
        return value;
    }

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
}

package com.rockmap.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Permanent, user-visible attribution, license, and source-notice inventory. */
public final class DataSourcesLicensesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = new TextView(this);
        title.setText("Data sources & licenses");
        title.setTextSize(21f);
        title.setTextColor(Color.rgb(30, 30, 30));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Attribution, license information, and source-specific notices for RockMap data and bundled map text resources.");
        intro.setTextSize(13f);
        intro.setTextColor(Color.rgb(70, 70, 70));
        intro.setPadding(0, 0, 0, dp(10));
        root.addView(intro);

        TextView body = new TextView(this);
        body.setText(readAsset("data_sources_and_licenses.txt", 300_000));
        body.setTextSize(13f);
        body.setTextColor(Color.rgb(45, 45, 45));
        body.setTextIsSelectable(true);
        body.setLinksClickable(true);
        Linkify.addLinks(body, Linkify.WEB_URLS);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setPadding(0, 0, 0, dp(12));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setMinHeight(dp(48));
        close.setMinimumHeight(dp(48));
        close.setOnClickListener(v -> finish());
        root.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    dp(16) + insets.getSystemWindowInsetLeft(),
                    dp(16) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(),
                    dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
    }

    private String readAsset(String name, int maxBytes) {
        try (InputStream input = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) return "Document exceeded RockMap's display size limit.";
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "Data-source and license information is unavailable in this build.";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

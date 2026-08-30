package com.rockmap.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.safety.SafetyAcknowledgement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

/** In-app permanent access to RockMap's safety, source/license, privacy, and acknowledgment information. */
public final class PrivacySafetyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = heading("Safety, sources & privacy", 21f);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        Button sources = button("Data sources & licenses");
        sources.setContentDescription("Open RockMap data sources and licenses");
        sources.setOnClickListener(v ->
                startActivity(new Intent(this, DataSourcesLicensesActivity.class)));
        content.addView(sources, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sourcesHint = body("Attribution, license links, source-specific notices, and bundled map-text licensing.");
        sourcesHint.setPadding(0, 0, 0, dp(8));
        content.addView(sourcesHint);

        TextView safetyHeading = heading("Safety & data limitations", 17f);
        safetyHeading.setPadding(0, dp(4), 0, dp(6));
        content.addView(safetyHeading);
        content.addView(body(readAsset("safety_data_limitations.txt", 250_000)));

        TextView privacyHeading = heading("Privacy policy", 17f);
        privacyHeading.setPadding(0, dp(16), 0, dp(6));
        content.addView(privacyHeading);
        content.addView(body(readAsset("privacy_policy.txt", 250_000)));

        TextView ackHeading = heading("Local safety acknowledgment", 17f);
        ackHeading.setPadding(0, dp(16), 0, dp(6));
        content.addView(ackHeading);
        content.addView(body(formatStatus(SafetyAcknowledgement.getStatus(this))));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout rowOne = new LinearLayout(this);
        rowOne.setOrientation(LinearLayout.HORIZONTAL);
        rowOne.setGravity(Gravity.CENTER_VERTICAL);

        Button reminder = button("Show notice next launch");
        reminder.setOnClickListener(v -> {
            SafetyAcknowledgement.enableReminder(this);
            Toast.makeText(this, "Safety reminder enabled for the next RockMap launch.", Toast.LENGTH_LONG).show();
            recreate();
        });
        rowOne.addView(reminder, actionParams());

        Button permissions = button("Permissions");
        permissions.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        rowOne.addView(permissions, actionParams());
        root.addView(rowOne);

        LinearLayout rowTwo = new LinearLayout(this);
        rowTwo.setOrientation(LinearLayout.HORIZONTAL);
        rowTwo.setGravity(Gravity.CENTER_VERTICAL);

        if (BuildConfig.PRIVACY_POLICY_URL != null
                && !BuildConfig.PRIVACY_POLICY_URL.trim().isEmpty()) {
            Button online = button("Public privacy policy");
            online.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)));
                } catch (RuntimeException ex) {
                    Toast.makeText(this, "Could not open the public privacy policy.", Toast.LENGTH_LONG).show();
                }
            });
            rowTwo.addView(online, actionParams());
        }

        Button close = button("Close");
        close.setOnClickListener(v -> finish());
        rowTwo.addView(close, actionParams());
        root.addView(rowTwo);

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

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private String formatStatus(SafetyAcknowledgement.Status status) {
        if (status == null || status.disclosureVersion <= 0 || status.token.isEmpty()) {
            return "No local acknowledgment has been recorded yet. The required notice will appear before RockMap can be used.";
        }
        String accepted = status.acceptedAt > 0
                ? DateFormat.getDateTimeInstance().format(new Date(status.acceptedAt))
                : "unknown";
        return "Disclosure version: " + status.disclosureVersion
                + "\nLast acknowledged: " + accepted
                + "\nAcknowledgment count: " + status.acceptanceCount
                + "\nReminder suppressed for this disclosure: " + (status.reminderSuppressed ? "yes" : "no")
                + "\nLocal token: " + status.token
                + "\n\nThis random token stays in RockMap's private app data on this device. It is not transmitted, used for authentication, or used for analytics/tracking. It is not server-verified or tamper-proof and is cleared if RockMap app data is cleared.";
    }

    private TextView heading(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(30, 30, 30));
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13f);
        view.setTextColor(Color.rgb(55, 55, 55));
        view.setTextIsSelectable(true);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12.5f);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setContentDescription(label);
        return button;
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
            return "Document unavailable in this build.";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

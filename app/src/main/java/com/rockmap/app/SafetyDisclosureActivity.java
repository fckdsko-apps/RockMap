package com.rockmap.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rockmap.app.safety.SafetyAcknowledgement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Mandatory entry gate for RockMap's current safety/data-limitations disclosure. */
public final class SafetyDisclosureActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (SafetyAcknowledgement.isReminderSuppressed(this)) {
            openRockMap();
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(24));

        TextView banner = new TextView(this);
        banner.setText("IMPORTANT — REFERENCE DATA ONLY");
        banner.setTextColor(Color.WHITE);
        banner.setBackgroundColor(Color.rgb(145, 40, 32));
        banner.setTextSize(15f);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(12), dp(12), dp(12), dp(12));
        content.addView(banner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Read before using RockMap");
        title.setTextSize(22f);
        title.setTextColor(Color.rgb(25, 25, 25));
        title.setPadding(0, dp(16), 0, dp(8));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView intro = new TextView(this);
        intro.setText("RockMap does not independently validate the accuracy, legal status, field condition, or coordinate precision of the public data it displays. You must acknowledge these limitations before using the app.");
        intro.setTextSize(14f);
        intro.setTextColor(Color.rgb(45, 45, 45));
        intro.setPadding(0, 0, 0, dp(12));
        content.addView(intro);

        String disclosureText = readAsset("safety_data_limitations.txt", 250_000);
        boolean disclosureAvailable = disclosureText != null;
        TextView disclosure = new TextView(this);
        disclosure.setText(disclosureAvailable
                ? disclosureText
                : "RockMap cannot display its required safety notice in this build. Exit the app and install a valid build.");
        disclosure.setTextSize(13f);
        disclosure.setTextColor(Color.rgb(50, 50, 50));
        disclosure.setTextIsSelectable(true);
        disclosure.setPadding(0, 0, 0, dp(14));
        content.addView(disclosure);

        CheckBox suppress = new CheckBox(this);
        suppress.setText("Don't show this again unless the safety notice changes");
        suppress.setTextSize(13.5f);
        suppress.setMinHeight(dp(48));
        suppress.setPadding(0, dp(4), 0, dp(4));
        content.addView(suppress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tokenNote = new TextView(this);
        tokenNote.setText("Continuing creates a random acknowledgment token stored only in RockMap's private app data on this device. It is not sent to RockMap or used for tracking. It is a local app-state record, not a server-verified or tamper-proof legal receipt.");
        tokenNote.setTextSize(12f);
        tokenNote.setTextColor(Color.rgb(80, 80, 80));
        tokenNote.setPadding(0, dp(4), 0, dp(12));
        content.addView(tokenNote);

        Button privacy = button("Privacy & data handling");
        privacy.setOnClickListener(v -> startActivity(new Intent(this, PrivacySafetyActivity.class)));
        content.addView(privacy);

        Button continueButton = button("I understand — continue");
        continueButton.setEnabled(disclosureAvailable);
        continueButton.setOnClickListener(v -> {
            SafetyAcknowledgement.acknowledge(this, suppress.isChecked());
            openRockMap();
        });
        content.addView(continueButton);

        Button exit = button("Exit RockMap");
        exit.setOnClickListener(v -> finishAndRemoveTask());
        content.addView(exit);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private void openRockMap() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13f);
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
                if (total > maxBytes) return null;
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

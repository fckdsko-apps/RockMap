package com.rockmap.app;

import android.app.Activity;
import android.app.AlertDialog;
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

/**
 * Permanent user-facing controls for local RockMap data and Android backup/restore behavior.
 * RockMap has no account/cloud-sync service; Android backup is managed by the OS/provider.
 */
public final class UserDataBackupActivity extends Activity {
    private Button locationsTripsButton;
    private Button fieldButton;
    private Button allButton;
    private Button appStorageButton;
    private Button androidPrivacyButton;
    private TextView status;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = heading("User data & backup", 21f);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView explanation = body(
                "RockMap stores saved locations, trips, tracks, field records, prospecting areas, "
                        + "notes, and coordinates in private app databases on this device.\n\n"
                        + "Android backup/device transfer is allowed to back up those user databases. "
                        + "Because of that, Android may restore saved RockMap work after a reinstall or "
                        + "when moving to another device. RockMap's downloadable map and geology packs "
                        + "are excluded from backup and are downloaded again when needed.");
        content.addView(explanation);

        TextView deleteHeading = heading("Delete local user-created data", 17f);
        deleteHeading.setPadding(0, dp(10), 0, dp(5));
        content.addView(deleteHeading);

        locationsTripsButton = button("Delete saved locations & trips");
        locationsTripsButton.setOnClickListener(v -> confirmDelete(
                "Delete saved locations & trips?",
                "This permanently removes RockMap saved locations, trips, and trip stops from this device. "
                        + "Downloaded maps and geology are not removed.",
                () -> runDelete(DeleteKind.LOCATIONS_TRIPS)));
        content.addView(locationsTripsButton, fullParams());

        fieldButton = button("Delete tracks, field records & areas");
        fieldButton.setOnClickListener(v -> confirmDelete(
                "Delete field data?",
                "This permanently removes recorded tracks, track points, field records, prospecting areas, "
                        + "and their RockMap photo references. Original photos remain with the photo/document provider. "
                        + "Stop any active track recording first.",
                () -> runDelete(DeleteKind.FIELD)));
        content.addView(fieldButton, fullParams());

        allButton = button("Delete all user-created RockMap data");
        allButton.setOnClickListener(v -> confirmDelete(
                "Delete ALL user-created RockMap data?",
                "This removes saved locations, trips, tracks, field records, prospecting areas, imported-item "
                        + "ownership records, notes, and coordinates from RockMap on this device.\n\n"
                        + "It does NOT delete the installed offline map pack, Colorado geology, RockMap settings, "
                        + "the safety acknowledgment, or original photos stored outside RockMap.\n\n"
                        + "This cannot be undone.",
                () -> runDelete(DeleteKind.ALL)));
        content.addView(allButton, fullParams());

        status = body("");
        status.setTextColor(Color.rgb(80, 60, 40));
        status.setPadding(0, dp(4), 0, dp(8));
        content.addView(status);

        TextView backupHeading = heading("Android backup", 17f);
        backupHeading.setPadding(0, dp(10), 0, dp(5));
        content.addView(backupHeading);

        TextView backupText = body(
                "Deleting data above removes RockMap's current local copy immediately. Android/Google/Samsung "
                        + "backup copies are controlled by the Android backup provider, not by RockMap. RockMap cannot "
                        + "force that provider to erase an older cloud backup immediately, so an older backup can "
                        + "potentially remain until the provider updates or removes it.\n\n"
                        + "Use your device's backup/privacy settings to review or disable backup options that your "
                        + "phone and backup provider make available.");
        content.addView(backupText);

        androidPrivacyButton = button("Open Android privacy settings");
        androidPrivacyButton.setOnClickListener(v -> openSettingsSafely(
                new Intent(Settings.ACTION_PRIVACY_SETTINGS)));
        content.addView(androidPrivacyButton, fullParams());

        TextView allAppHeading = heading("Clear the entire RockMap installation", 17f);
        allAppHeading.setPadding(0, dp(12), 0, dp(5));
        content.addView(allAppHeading);

        TextView allAppText = body(
                "For the full reset, Android's App info > Storage > Clear data removes RockMap's local databases, "
                        + "downloaded map/geology data, preferences, and safety state. Android backup copies are still "
                        + "managed separately by Android/your backup provider.");
        content.addView(allAppText);

        appStorageButton = button("Open RockMap app settings");
        appStorageButton.setOnClickListener(v -> openSettingsSafely(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))));
        content.addView(appStorageButton, fullParams());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button close = button("Close");
        close.setOnClickListener(v -> finish());
        root.addView(close, fullParams());

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

    private void confirmDelete(String title, String message, Runnable confirmed) {
        if (busy) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> confirmed.run())
                .show();
    }

    private void runDelete(DeleteKind kind) {
        if (busy) return;
        setBusy(true);
        status.setText("Deleting RockMap user data…");

        UserDataManager.Callback callback = new UserDataManager.Callback() {
            @Override
            public void onSuccess() {
                status.setText("Deletion complete.");
                Toast.makeText(UserDataBackupActivity.this,
                        "RockMap user data deleted.", Toast.LENGTH_LONG).show();
                restartRockMap();
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                status.setText("Deletion did not complete: " + message);
                new AlertDialog.Builder(UserDataBackupActivity.this)
                        .setTitle("Could not delete data")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        };

        if (kind == DeleteKind.LOCATIONS_TRIPS) {
            UserDataManager.deleteSavedLocationsAndTrips(this, callback);
        } else if (kind == DeleteKind.FIELD) {
            UserDataManager.deleteFieldData(this, callback);
        } else {
            UserDataManager.deleteAllUserCreatedData(this, callback);
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        locationsTripsButton.setEnabled(!value);
        fieldButton.setEnabled(!value);
        allButton.setEnabled(!value);
        appStorageButton.setEnabled(!value);
        androidPrivacyButton.setEnabled(!value);
    }

    private void restartRockMap() {
        Intent restart = new Intent(this, SafetyDisclosureActivity.class);
        restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(restart);
        finish();
    }

    private void openSettingsSafely(Intent intent) {
        try {
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        } catch (RuntimeException ex) {
            Toast.makeText(this, "Android settings could not be opened.", Toast.LENGTH_LONG).show();
        }
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
        view.setTextSize(13.5f);
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

    private LinearLayout.LayoutParams fullParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(2), 0, dp(2));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum DeleteKind {
        LOCATIONS_TRIPS,
        FIELD,
        ALL
    }
}

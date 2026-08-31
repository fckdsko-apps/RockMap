package com.rockmap.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.rockmap.app.offline.DataUpdatePreviewer;
import com.rockmap.app.offline.DataUpdateWorker;
import com.rockmap.app.offline.OfflineDataManager;
import com.rockmap.app.research.GeologyDataManager;
import com.rockmap.app.research.GeologyDataPreviewer;
import com.rockmap.app.research.GeologyDataUpdateWorker;
import com.rockmap.app.safety.SafetyAcknowledgement;
import com.rockmap.app.updates.DataUpdateScheduler;

import java.text.DateFormat;
import java.util.List;

/**
 * User-facing update center.
 *
 * All primary actions are fixed below the scrollable details so Check now, Frequency, Alerts,
 * Install, and Close are always visible on a phone-sized screen.
 */
public final class DataUpdateSettingsActivity extends Activity {
    public static final String EXTRA_FIRST_RUN_SETUP = "rockmap.data_updates.first_run_setup";

    private static final int REQ_NOTIFICATIONS = 921;
    private static final String INSTALL_WORK = "rockmap-user-data-update-install";

    private TextView summary;
    private TextView coreStatus;
    private TextView geologyStatus;
    private TextView lastCheckStatus;
    private Button checkButton;
    private Button frequencyButton;
    private Button alertsButton;
    private Button installButton;
    private Button closeButton;

    private DataUpdatePreviewer.Preview manualCore;
    private GeologyDataPreviewer.Preview manualGeology;
    private String manualCoreError = "";
    private String manualGeologyError = "";
    private int manualPending;
    private boolean installRunning;
    private boolean firstRunSetup;

    private LiveData<List<WorkInfo>> installLiveData;
    private Observer<List<WorkInfo>> installObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SafetyAcknowledgement.isAccessAllowed(this)) {
            startActivity(new Intent(this, SafetyDisclosureActivity.class));
            finish();
            return;
        }

        firstRunSetup = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_FIRST_RUN_SETUP, false);

        // Android 13+ routes system/predictive back through OnBackInvokedDispatcher.
        // Keep first-run setup semantics intact without the obsolete Activity.onBackPressed()
        // override that fails release lint for targetSdk 36.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::closeScreen);
        }

        DataUpdateScheduler.ensureNotificationChannel(this);
        buildUi();
        observeInstallWork();
        refreshUi();

        // A clean install reaches this screen immediately after the required reference-data
        // bootstrap. Existing installs also receive the choice the first time they open Updates.
        if (DataUpdateScheduler.shouldShowFirstRunOnboarding(this)) {
            getWindow().getDecorView().post(this::showFirstRunOnboarding);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(10));

        TextView title = heading("Data updates", 21f);
        title.setPadding(0, 0, 0, dp(6));
        root.addView(title);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);

        summary = body(
                "RockMap can check the small Core Data and Colorado Geology manifests automatically. "
                        + "Scheduled checks never download the large data packs. You review the size and choose whether to install.");
        summary.setPadding(0, 0, 0, dp(10));
        details.addView(summary);

        TextView scheduleHeading = heading("Automatic checks", 17f);
        details.addView(scheduleHeading);
        details.addView(body(
                "Default: Weekly. You can choose Never, Daily, Weekly, or Monthly. Android runs periodic work approximately when network and system conditions allow; it is not an exact-clock alarm."));

        TextView coreHeading = heading("Core RockMap data", 17f);
        coreHeading.setPadding(0, dp(10), 0, dp(3));
        details.addView(coreHeading);
        coreStatus = body("");
        details.addView(coreStatus);

        TextView geologyHeading = heading("Colorado geology", 17f);
        geologyHeading.setPadding(0, dp(10), 0, dp(3));
        details.addView(geologyHeading);
        geologyStatus = body("");
        details.addView(geologyStatus);

        TextView checkHeading = heading("Last update check", 17f);
        checkHeading.setPadding(0, dp(10), 0, dp(3));
        details.addView(checkHeading);
        lastCheckStatus = body("");
        details.addView(lastCheckStatus);

        TextView privacyHeading = heading("What a scheduled check sends", 17f);
        privacyHeading.setPadding(0, dp(10), 0, dp(3));
        details.addView(privacyHeading);
        details.addView(body(
                "Only ordinary HTTPS requests for the small published manifests are made. RockMap does not include GPS position, saved locations, trips, tracks, field records, notes, photos, or search terms in update checks."));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(details);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Fixed actions: none of these depend on scrolling.
        LinearLayout rowOne = new LinearLayout(this);
        rowOne.setOrientation(LinearLayout.HORIZONTAL);
        checkButton = button("Check now");
        checkButton.setOnClickListener(v -> checkNow());
        rowOne.addView(checkButton, weightedButtonParams(false));

        frequencyButton = button("Frequency");
        frequencyButton.setOnClickListener(v -> chooseFrequency());
        rowOne.addView(frequencyButton, weightedButtonParams(true));
        root.addView(rowOne);

        LinearLayout rowTwo = new LinearLayout(this);
        rowTwo.setOrientation(LinearLayout.HORIZONTAL);
        alertsButton = button("Update alerts");
        alertsButton.setOnClickListener(v -> manageAlerts());
        rowTwo.addView(alertsButton, weightedButtonParams(false));

        installButton = button("No update to install");
        installButton.setEnabled(false);
        installButton.setOnClickListener(v -> confirmInstall());
        rowTwo.addView(installButton, weightedButtonParams(true));
        root.addView(rowTwo);

        closeButton = button(firstRunSetup ? "Open RockMap" : "Close");
        closeButton.setOnClickListener(v -> closeScreen());
        root.addView(closeButton, fullButtonParams());

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                    dp(14) + insets.getSystemWindowInsetLeft(),
                    dp(14) + insets.getSystemWindowInsetTop(),
                    dp(14) + insets.getSystemWindowInsetRight(),
                    dp(10) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
    }

    private void refreshUi() {
        DataUpdateScheduler.State state = DataUpdateScheduler.getState(this);
        frequencyButton.setText("Frequency: " + DataUpdateScheduler.frequencyLabel(state.frequency));

        boolean alerts = DataUpdateScheduler.areAlertsEnabled(this);
        alertsButton.setText(alerts ? "Alerts: on" : "Enable alerts");

        String installedCore = DataUpdateScheduler.installedCoreVersion(this);
        if (installedCore.isEmpty()) installedCore = "not installed";
        coreStatus.setText("Installed: " + installedCore
                + (state.coreAvailable
                ? "\nAvailable: " + display(state.coreVersion, "new release")
                    + transferLine(state.coreDownloadBytes)
                : state.coreError.isEmpty() ? "\nStatus: current / no known update"
                    : "\nLast check issue: " + state.coreError));

        String installedGeology = new GeologyDataManager(this).getInstalledVersion();
        if (installedGeology == null || installedGeology.trim().isEmpty()) {
            installedGeology = "not installed";
        }
        boolean geologyInstalled = !"not installed".equals(installedGeology);
        geologyStatus.setText("Installed: " + installedGeology
                + (state.geologyAvailable
                ? "\nAvailable: " + display(state.geologyVersion, "new release")
                    + transferLine(state.geologyDownloadBytes)
                : !geologyInstalled
                    ? "\nStatus: optional package not installed — add it from Offline Maps & Data if wanted"
                    : state.geologyError.isEmpty() ? "\nStatus: current / no known update"
                    : "\nLast check issue: " + state.geologyError));

        String checked = state.lastCheckedAt > 0L
                ? DateFormat.getDateTimeInstance().format(state.lastCheckedAt)
                : "Not checked yet";
        lastCheckStatus.setText(checked
                + (state.lastResult.isEmpty() ? "" : "\n" + state.lastResult));

        if (installRunning) {
            installButton.setText("Installing…");
            installButton.setEnabled(false);
        } else if (state.hasUpdate()) {
            long bytes = state.totalDownloadBytes();
            installButton.setText(bytes > 0L
                    ? "Install updates (" + DataUpdateScheduler.formatBytes(bytes) + ")"
                    : "Install update");
            installButton.setEnabled(true);
        } else {
            installButton.setText("No update to install");
            installButton.setEnabled(false);
        }
    }

    private String transferLine(long bytes) {
        return bytes > 0L
                ? "\nExpected transfer: " + DataUpdateScheduler.formatBytes(bytes)
                : "\nExpected additional asset transfer: none";
    }

    private void chooseFrequency() {
        final String[] values = new String[]{
                DataUpdateScheduler.FREQUENCY_NEVER,
                DataUpdateScheduler.FREQUENCY_DAILY,
                DataUpdateScheduler.FREQUENCY_WEEKLY,
                DataUpdateScheduler.FREQUENCY_MONTHLY
        };
        final String[] labels = new String[]{"Never", "Daily", "Weekly", "Monthly"};
        String current = DataUpdateScheduler.getFrequency(this);
        int checked = 2;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) checked = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Automatic update checks")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    DataUpdateScheduler.setFrequency(this, values[which]);
                    DataUpdateScheduler.markFirstRunOnboardingSeen(this);
                    if (firstRunSetup) InitialDataSetupActivity.markSetupFinished(this);
                    TourDebugLog.mapDiagnostic("DATA_UPDATE_ONBOARDING",
                            "state=complete frequency=" + values[which] + " source=settings");
                    dialog.dismiss();
                    refreshUi();
                    if (!DataUpdateScheduler.FREQUENCY_NEVER.equals(values[which])
                            && !DataUpdateScheduler.areAlertsEnabled(this)) {
                        offerNotificationPermission();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFirstRunOnboarding() {
        if (!DataUpdateScheduler.shouldShowFirstRunOnboarding(this)
                || isFinishing() || isDestroyed()) {
            return;
        }

        final String[] values = new String[]{
                DataUpdateScheduler.FREQUENCY_NEVER,
                DataUpdateScheduler.FREQUENCY_DAILY,
                DataUpdateScheduler.FREQUENCY_WEEKLY,
                DataUpdateScheduler.FREQUENCY_MONTHLY
        };
        final String[] labels = new String[]{
                "Never",
                "Daily",
                "Weekly — recommended",
                "Monthly"
        };

        String current = DataUpdateScheduler.getFrequency(this);
        int currentIndex = 2;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) currentIndex = i;
        }
        final int[] selected = new int[]{currentIndex};

        TourDebugLog.mapDiagnostic("DATA_UPDATE_ONBOARDING", "state=shown");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Keep RockMap data current?")
                .setMessage("RockMap's offline reference data can change over time. "
                        + "Choose how often RockMap should check the small update manifests.\n\n"
                        + "These checks do not download the large data packs. "
                        + "RockMap will always show the update size and require your approval before installing one.")
                .setSingleChoiceItems(labels, currentIndex,
                        (d, which) -> selected[0] = which)
                .setPositiveButton("Save", null)
                .setCancelable(false)
                .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String chosen = values[selected[0]];
                    DataUpdateScheduler.setFrequency(this, chosen);
                    DataUpdateScheduler.markFirstRunOnboardingSeen(this);
                    if (firstRunSetup) InitialDataSetupActivity.markSetupFinished(this);
                    TourDebugLog.mapDiagnostic("DATA_UPDATE_ONBOARDING",
                            "state=complete frequency=" + chosen + " source=first_run");
                    dialog.dismiss();
                    refreshUi();

                    if (!DataUpdateScheduler.FREQUENCY_NEVER.equals(chosen)
                            && !DataUpdateScheduler.areAlertsEnabled(this)) {
                        getWindow().getDecorView().postDelayed(
                                this::offerNotificationPermission, 250L);
                    }
                }));
        dialog.show();
    }

    private void closeScreen() {
        if (firstRunSetup) {
            if (DataUpdateScheduler.shouldShowFirstRunOnboarding(this)) {
                showFirstRunOnboarding();
                return;
            }
            InitialDataSetupActivity.markSetupFinished(this);
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            return;
        }
        finish();
    }

    private void manageAlerts() {
        if (DataUpdateScheduler.areAlertsEnabled(this)) {
            openNotificationSettings();
            return;
        }
        offerNotificationPermission();
    }

    private void offerNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openNotificationSettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            openNotificationSettings();
            return;
        }

        if (DataUpdateScheduler.wasNotificationPermissionRequested(this)) {
            openNotificationSettings();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Enable RockMap update alerts?")
                .setMessage("RockMap will use notifications only to tell you when a data update is available. "
                        + "Automatic checks still download only the small manifests; large data packs are never downloaded without your approval.")
                .setPositiveButton("Continue", (d, w) -> {
                    DataUpdateScheduler.markNotificationPermissionRequested(this);
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQ_NOTIFICATIONS);
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (RuntimeException ex) {
            Toast.makeText(this, "Android notification settings could not be opened.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) refreshUi();
    }

    private void checkNow() {
        if (manualPending > 0 || installRunning) return;
        manualCore = null;
        manualGeology = null;
        manualCoreError = "";
        manualGeologyError = "";
        manualPending = 2;
        checkButton.setEnabled(false);
        checkButton.setText("Checking…");
        lastCheckStatus.setText("Checking the Core Data and Colorado Geology manifests…");
        TourDebugLog.mapDiagnostic("DATA_UPDATE_SCAN", "type=manual state=start");

        if (BuildConfig.DATA_MANIFEST_URL == null
                || BuildConfig.DATA_MANIFEST_URL.trim().isEmpty()) {
            manualCoreError = "Core data manifest is not configured.";
            finishOneManualCheck();
        } else {
            DataUpdatePreviewer.preview(this, BuildConfig.DATA_MANIFEST_URL,
                    new DataUpdatePreviewer.Callback() {
                @Override public void onPreview(DataUpdatePreviewer.Preview preview) {
                    manualCore = preview;
                    finishOneManualCheck();
                }

                @Override public void onError(String message) {
                    manualCoreError = message == null ? "Core update check failed." : message;
                    finishOneManualCheck();
                }
            });
        }

        if (BuildConfig.GEOLOGY_MANIFEST_URL == null
                || BuildConfig.GEOLOGY_MANIFEST_URL.trim().isEmpty()) {
            manualGeologyError = "Colorado geology manifest is not configured.";
            finishOneManualCheck();
        } else {
            GeologyDataPreviewer.preview(this, BuildConfig.GEOLOGY_MANIFEST_URL,
                    new GeologyDataPreviewer.Callback() {
                @Override public void onPreview(GeologyDataPreviewer.Preview preview) {
                    manualGeology = preview;
                    finishOneManualCheck();
                }

                @Override public void onError(String message) {
                    manualGeologyError = message == null ? "Geology update check failed." : message;
                    finishOneManualCheck();
                }
            });
        }
    }

    private void finishOneManualCheck() {
        manualPending--;
        if (manualPending > 0) return;
        checkButton.setEnabled(true);
        checkButton.setText("Check now");

        DataUpdateScheduler.State state = DataUpdateScheduler.recordScan(
                this, manualCore, manualCoreError, manualGeology, manualGeologyError, "manual");
        // The user is looking at this exact result now, so a later scheduled scan should not
        // immediately duplicate the same information as a notification.
        DataUpdateScheduler.markCurrentResultSeen(this);
        refreshUi();

        if (state.hasUpdate()) {
            Toast.makeText(this, "RockMap data update available.", Toast.LENGTH_LONG).show();
        } else if (!manualCoreError.isEmpty() || !manualGeologyError.isEmpty()) {
            Toast.makeText(this, "Update check completed with an issue.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "RockMap data is current.", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmInstall() {
        if (installRunning) return;
        DataUpdateScheduler.State state = DataUpdateScheduler.getState(this);
        if (!state.hasUpdate()) {
            refreshUi();
            return;
        }

        long bytes = state.totalDownloadBytes();
        String transfer = bytes > 0L
                ? "Expected transfer based on the last manifest check: "
                    + DataUpdateScheduler.formatBytes(bytes) + "."
                : "No additional asset transfer is currently expected; RockMap will refresh and verify the release metadata.";

        String packages = state.coreAvailable && state.geologyAvailable
                ? "Core RockMap data and Colorado geology"
                : state.coreAvailable ? "Core RockMap data" : "Colorado geology";

        new AlertDialog.Builder(this)
                .setTitle("Install available updates?")
                .setMessage(packages + "\n\n" + transfer
                        + "\n\nRockMap will fetch the current manifests again and integrity-check downloaded files before activation. User-created Field data is preserved.")
                .setPositiveButton("Install", (d, w) -> queueInstall(
                        state.coreAvailable, state.geologyAvailable))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void queueInstall(boolean core, boolean geology) {
        if (!core && !geology) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        WorkManager manager = WorkManager.getInstance(this);
        if (core && geology) {
            OneTimeWorkRequest coreRequest = new OneTimeWorkRequest.Builder(DataUpdateWorker.class)
                    .setConstraints(constraints)
                    .addTag(INSTALL_WORK + "-core")
                    .build();
            OneTimeWorkRequest geologyRequest = new OneTimeWorkRequest.Builder(
                    GeologyDataUpdateWorker.class)
                    .setConstraints(constraints)
                    .addTag(INSTALL_WORK + "-geology")
                    .build();
            manager.beginUniqueWork(INSTALL_WORK, ExistingWorkPolicy.REPLACE, coreRequest)
                    .then(geologyRequest)
                    .enqueue();
        } else if (core) {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DataUpdateWorker.class)
                    .setConstraints(constraints)
                    .addTag(INSTALL_WORK + "-core")
                    .build();
            manager.beginUniqueWork(INSTALL_WORK, ExistingWorkPolicy.REPLACE, request).enqueue();
        } else {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                    GeologyDataUpdateWorker.class)
                    .setConstraints(constraints)
                    .addTag(INSTALL_WORK + "-geology")
                    .build();
            manager.beginUniqueWork(INSTALL_WORK, ExistingWorkPolicy.REPLACE, request).enqueue();
        }

        installRunning = true;
        setControlsEnabled(false);
        installButton.setText("Installing…");
        lastCheckStatus.setText("Installing and verifying the selected RockMap data update…");
        TourDebugLog.mapDiagnostic("DATA_UPDATE_DOWNLOAD",
                "state=queued core=" + core + " geology=" + geology);
    }

    private void observeInstallWork() {
        installLiveData = WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(INSTALL_WORK);
        installObserver = this::handleInstallWork;
        installLiveData.observeForever(installObserver);
    }

    private void handleInstallWork(List<WorkInfo> infos) {
        if (infos == null || infos.isEmpty()) return;

        boolean currentlyActive = false;
        for (WorkInfo info : infos) {
            WorkInfo.State state = info.getState();
            if (state == WorkInfo.State.RUNNING
                    || state == WorkInfo.State.ENQUEUED
                    || state == WorkInfo.State.BLOCKED) {
                currentlyActive = true;
                break;
            }
        }

        // WorkManager retains finished unique-work history. Do not mistake an old successful
        // install for an install started during this screen/session. If work is actually active,
        // adopt it so rotation/process recreation still tracks the real installation.
        if (!installRunning && !currentlyActive) return;
        if (currentlyActive) installRunning = true;

        boolean anyActive = false;
        boolean anyFailed = false;
        boolean allFinished = true;
        for (WorkInfo info : infos) {
            WorkInfo.State state = info.getState();
            if (state == WorkInfo.State.RUNNING
                    || state == WorkInfo.State.ENQUEUED
                    || state == WorkInfo.State.BLOCKED) {
                anyActive = true;
                allFinished = false;
            } else if (!state.isFinished()) {
                allFinished = false;
            }
            if (state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED) {
                anyFailed = true;
            }
        }

        if (anyActive) {
            installRunning = true;
            setControlsEnabled(false);
            installButton.setText("Installing…");
            return;
        }
        if (!allFinished) return;

        installRunning = false;
        setControlsEnabled(true);

        if (anyFailed) {
            String core = new OfflineDataManager(this).getLastUpdateStatus();
            String geology = new GeologyDataManager(this).getLastUpdateStatus();
            lastCheckStatus.setText("Update installation did not complete."
                    + (core == null || core.trim().isEmpty() ? "" : "\nCore: " + core.trim())
                    + (geology == null || geology.trim().isEmpty() ? "" : "\nGeology: " + geology.trim()));
            TourDebugLog.mapDiagnostic("DATA_UPDATE_DOWNLOAD", "state=failure");
            refreshUi();
            return;
        }

        DataUpdateScheduler.clearAvailability(this);
        DataUpdateScheduler.cancelUpdateNotification(this);
        TourDebugLog.mapDiagnostic("DATA_UPDATE_DOWNLOAD", "state=success");
        new AlertDialog.Builder(this)
                .setTitle("RockMap data updated")
                .setMessage("The selected data update was installed and verified. RockMap will reopen so the active map and research repositories load the new snapshot.")
                .setPositiveButton("Open RockMap", (d, w) -> restartRockMap())
                .setCancelable(false)
                .show();
    }

    private void setControlsEnabled(boolean enabled) {
        checkButton.setEnabled(enabled);
        frequencyButton.setEnabled(enabled);
        alertsButton.setEnabled(enabled);
        closeButton.setEnabled(enabled);
        if (!enabled) installButton.setEnabled(false);
        else refreshUi();
    }

    private void restartRockMap() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (summary != null) refreshUi();
    }

    @Override
    protected void onDestroy() {
        if (installLiveData != null && installObserver != null) {
            installLiveData.removeObserver(installObserver);
        }
        super.onDestroy();
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
        view.setTextSize(13.2f);
        view.setTextColor(Color.rgb(55, 55, 55));
        view.setTextIsSelectable(true);
        view.setPadding(0, 0, 0, dp(7));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(11.5f);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setContentDescription(label);
        return button;
    }

    private LinearLayout.LayoutParams weightedButtonParams(boolean right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (right) params.setMargins(dp(3), dp(2), 0, dp(2));
        else params.setMargins(0, dp(2), dp(3), dp(2));
        return params;
    }

    private LinearLayout.LayoutParams fullButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(2), 0, dp(2));
        return params;
    }

    private String display(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

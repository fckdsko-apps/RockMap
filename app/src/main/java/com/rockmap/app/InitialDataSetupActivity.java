package com.rockmap.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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

import java.util.List;
import java.util.Locale;

/**
 * First-install bootstrap for RockMap's externally stored reference data.
 *
 * Existing installations that already have a renderable map pack bypass this screen.
 * A genuine clean install must install the core map/data pack before MainActivity opens.
 * Colorado geology is separately selectable and checked by default.
 */
public final class InitialDataSetupActivity extends Activity {
    private static final String PREFS = "rockmap_initial_setup";
    private static final String KEY_STARTED = "started";
    private static final String KEY_FINISHED = "finished";
    private static final String KEY_GEOLOGY_REQUESTED = "geology_requested";
    private static final String WORK_NAME = "rockmap-initial-data-setup";

    private OfflineDataManager offlineDataManager;
    private GeologyDataManager geologyDataManager;

    private TextView coreStatus;
    private TextView geologyStatus;
    private TextView totalStatus;
    private TextView progressStatus;
    private CheckBox geologyCheck;
    private Button installButton;
    private Button retryPreviewButton;
    private Button exitButton;

    private DataUpdatePreviewer.Preview mapPreview;
    private GeologyDataPreviewer.Preview geologyPreview;
    private boolean mapPreviewFinished;
    private boolean geologyPreviewFinished;
    private boolean mapPreviewFailed;
    private boolean geologyPreviewFailed;
    private boolean workRunning;

    private LiveData<List<WorkInfo>> workLiveData;
    private Observer<List<WorkInfo>> workObserver;

    public static boolean shouldShow(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_FINISHED, false)) return false;

        boolean started = prefs.getBoolean(KEY_STARTED, false);
        OfflineDataManager manager = new OfflineDataManager(app);

        // This is an upgrade of an already functioning installation, not a first install.
        // Mark the bootstrap complete without interrupting an existing user.
        if (!started && manager.hasRenderableActivePack()) {
            prefs.edit().putBoolean(KEY_FINISHED, true).apply();
            return false;
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_STARTED, true)
                .apply();

        offlineDataManager = new OfflineDataManager(this);
        geologyDataManager = new GeologyDataManager(this);

        if (isRequestedSetupComplete()) {
            markFinishedAndOpen();
            return;
        }

        buildUi();
        observeInitialWork();
        loadPreviews();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));

        TextView title = heading("Set up RockMap data", 22f);
        root.addView(title);

        TextView intro = body(
                "RockMap needs its offline map data before the main map can be used. "
                        + "RockMap will show the download sizes before anything is installed.\n\n"
                        + "Offline Find and map-label resources are already included with the app and do not need a separate download.");
        intro.setPadding(0, dp(5), 0, dp(12));
        root.addView(intro);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView coreHeading = heading("Core RockMap data — required", 17f);
        content.addView(coreHeading);

        TextView coreDescription = body(
                "Colorado basemap, land-status context, mining-claim context, mineral search, "
                        + "and installed mine/mineral evidence used by the current RockMap data release.");
        coreDescription.setPadding(0, dp(3), 0, dp(4));
        content.addView(coreDescription);

        coreStatus = body("Checking current core-data download size…");
        coreStatus.setTextColor(Color.rgb(75, 75, 75));
        coreStatus.setPadding(0, 0, 0, dp(14));
        content.addView(coreStatus);

        TextView geologyHeading = heading("Colorado geology research data", 17f);
        content.addView(geologyHeading);

        geologyCheck = new CheckBox(this);
        geologyCheck.setText("Install Colorado geology data");
        geologyCheck.setChecked(true);
        geologyCheck.setEnabled(false);
        geologyCheck.setMinHeight(dp(48));
        geologyCheck.setOnCheckedChangeListener((buttonView, isChecked) -> updateInstallSummary());
        content.addView(geologyCheck);

        TextView geologyDescription = body(
                "Queryable offline USGS State Geologic Map Compilation data used by RockMap research tools.");
        geologyDescription.setPadding(0, 0, 0, dp(4));
        content.addView(geologyDescription);

        geologyStatus = body("Checking current geology download size…");
        geologyStatus.setTextColor(Color.rgb(75, 75, 75));
        geologyStatus.setPadding(0, 0, 0, dp(14));
        content.addView(geologyStatus);

        totalStatus = heading("Checking total download size…", 15f);
        totalStatus.setPadding(0, dp(4), 0, dp(8));
        content.addView(totalStatus);

        progressStatus = body("");
        progressStatus.setTextColor(Color.rgb(55, 55, 55));
        progressStatus.setPadding(0, 0, 0, dp(8));
        content.addView(progressStatus);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        retryPreviewButton = button("Retry size check");
        retryPreviewButton.setVisibility(View.GONE);
        retryPreviewButton.setOnClickListener(v -> loadPreviews());
        root.addView(retryPreviewButton, fullButtonParams());

        installButton = button("Checking data…");
        installButton.setEnabled(false);
        installButton.setOnClickListener(v -> installSelectedData());
        root.addView(installButton, fullButtonParams());

        exitButton = button("Exit");
        exitButton.setOnClickListener(v -> finishAndRemoveTask());
        root.addView(exitButton, fullButtonParams());

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

    private void loadPreviews() {
        if (workRunning) return;

        mapPreview = null;
        geologyPreview = null;
        mapPreviewFinished = false;
        geologyPreviewFinished = false;
        mapPreviewFailed = false;
        geologyPreviewFailed = false;

        installButton.setEnabled(false);
        installButton.setText("Checking data…");
        retryPreviewButton.setVisibility(View.GONE);
        geologyCheck.setEnabled(false);
        progressStatus.setText("");
        coreStatus.setText("Checking current core-data download size…");
        geologyStatus.setText("Checking current geology download size…");
        totalStatus.setText("Checking total download size…");

        DataUpdatePreviewer.preview(this, BuildConfig.DATA_MANIFEST_URL, new DataUpdatePreviewer.Callback() {
            @Override
            public void onPreview(DataUpdatePreviewer.Preview preview) {
                mapPreview = preview;
                mapPreviewFinished = true;
                if (!preview.renderable) {
                    mapPreviewFailed = true;
                    coreStatus.setText(preview.message.isEmpty()
                            ? "The core RockMap data release is not currently installable."
                            : preview.message);
                } else if (preview.estimatedDownloadBytes <= 0L && offlineDataManager.hasRenderableActivePack()) {
                    coreStatus.setText("Already installed.");
                } else {
                    coreStatus.setText("Download: " + formatBytes(preview.estimatedDownloadBytes)
                            + "\nInstalled pack: " + formatBytes(preview.totalPackBytes)
                            + "\nVersion: " + display(preview.version, "current release"));
                }
                finishPreviewState();
            }

            @Override
            public void onError(String message) {
                mapPreviewFinished = true;
                mapPreviewFailed = true;
                coreStatus.setText("Could not check core data: " + message);
                finishPreviewState();
            }
        });

        GeologyDataPreviewer.preview(this, BuildConfig.GEOLOGY_MANIFEST_URL, new GeologyDataPreviewer.Callback() {
            @Override
            public void onPreview(GeologyDataPreviewer.Preview preview) {
                geologyPreview = preview;
                geologyPreviewFinished = true;
                if (!preview.published) {
                    geologyPreviewFailed = true;
                    geologyCheck.setChecked(false);
                    geologyStatus.setText(preview.message.isEmpty()
                            ? "Colorado geology data are not currently available."
                            : preview.message);
                } else if (!preview.needsDownload && geologyDataManager.getActiveDatabaseFile() != null) {
                    geologyStatus.setText("Already installed.\nVersion: "
                            + display(preview.version, "current release"));
                } else {
                    geologyStatus.setText("Download: " + formatBytes(preview.downloadBytes)
                            + "\nInstalled size: " + formatBytes(preview.installedBytes)
                            + "\nVersion: " + display(preview.version, "current release"));
                }
                finishPreviewState();
            }

            @Override
            public void onError(String message) {
                geologyPreviewFinished = true;
                geologyPreviewFailed = true;
                geologyCheck.setChecked(false);
                geologyStatus.setText("Could not check geology data: " + message
                        + "\nYou can install geology later from RockMap's Data menu.");
                finishPreviewState();
            }
        });
    }

    private void finishPreviewState() {
        if (!mapPreviewFinished || !geologyPreviewFinished || workRunning) return;

        retryPreviewButton.setVisibility(
                mapPreviewFailed || geologyPreviewFailed ? View.VISIBLE : View.GONE);

        if (!geologyPreviewFailed && geologyPreview != null && geologyPreview.published) {
            geologyCheck.setEnabled(true);
        } else {
            geologyCheck.setEnabled(false);
        }

        if (mapPreviewFailed || mapPreview == null || !mapPreview.renderable) {
            installButton.setEnabled(false);
            installButton.setText("Core data unavailable");
            totalStatus.setText("RockMap cannot complete first-time setup until the core data release can be checked.");
            return;
        }

        installButton.setEnabled(true);
        updateInstallSummary();
    }

    private void updateInstallSummary() {
        if (mapPreview == null || !mapPreview.renderable || workRunning) return;

        long total = mapPreview.estimatedDownloadBytes;
        boolean geologySelected = geologyCheck.isEnabled() && geologyCheck.isChecked()
                && geologyPreview != null && geologyPreview.published;

        if (geologySelected && geologyPreview.needsDownload) {
            try {
                total = Math.addExact(total, geologyPreview.downloadBytes);
            } catch (ArithmeticException ex) {
                installButton.setEnabled(false);
                totalStatus.setText("Download-size calculation failed safely.");
                return;
            }
        }

        totalStatus.setText("Selected download: " + formatBytes(total));
        if (geologySelected) {
            installButton.setText(total > 0L
                    ? "Install all selected data (" + formatBytes(total) + ")"
                    : "Finish setup");
        } else {
            installButton.setText(total > 0L
                    ? "Install required data (" + formatBytes(total) + ")"
                    : "Finish setup");
        }
    }

    private void installSelectedData() {
        if (mapPreview == null || !mapPreview.renderable || workRunning) return;

        boolean geologyRequested = geologyCheck.isEnabled()
                && geologyCheck.isChecked()
                && geologyPreview != null
                && geologyPreview.published;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_GEOLOGY_REQUESTED, geologyRequested)
                .apply();

        if (offlineDataManager.hasRenderableActivePack()
                && (!geologyRequested || geologyDataManager.getActiveDatabaseFile() != null)) {
            markFinishedAndOpen();
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest mapRequest = new OneTimeWorkRequest.Builder(DataUpdateWorker.class)
                .setConstraints(constraints)
                .addTag(WORK_NAME + "-core")
                .build();

        WorkManager manager = WorkManager.getInstance(this);
        if (geologyRequested) {
            OneTimeWorkRequest geologyRequest = new OneTimeWorkRequest.Builder(GeologyDataUpdateWorker.class)
                    .setConstraints(constraints)
                    .addTag(WORK_NAME + "-geology")
                    .build();
            manager.beginUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, mapRequest)
                    .then(geologyRequest)
                    .enqueue();
        } else {
            manager.beginUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, mapRequest)
                    .enqueue();
        }

        workRunning = true;
        installButton.setEnabled(false);
        retryPreviewButton.setVisibility(View.GONE);
        geologyCheck.setEnabled(false);
        progressStatus.setText(geologyRequested
                ? "Installing core RockMap data first. Colorado geology will install immediately afterward."
                : "Installing required RockMap data…");
        TourDebugLog.mapDiagnostic("INITIAL_DATA_SETUP",
                "state=queued geologyRequested=" + geologyRequested);
    }

    private void observeInitialWork() {
        workLiveData = WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(WORK_NAME);
        workObserver = this::handleWorkState;
        workLiveData.observeForever(workObserver);
    }

    private void handleWorkState(List<WorkInfo> infos) {
        if (infos == null || infos.isEmpty()) return;

        boolean requestedGeology = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_GEOLOGY_REQUESTED, false);

        boolean coreReady = offlineDataManager.hasRenderableActivePack();
        boolean geologyReady = geologyDataManager.getActiveDatabaseFile() != null;
        if (coreReady && (!requestedGeology || geologyReady)) {
            workRunning = false;
            progressStatus.setText(requestedGeology
                    ? "Core RockMap data and Colorado geology installed."
                    : "Required RockMap data installed.");
            TourDebugLog.mapDiagnostic("INITIAL_DATA_SETUP",
                    "state=complete geologyRequested=" + requestedGeology);
            installButton.setEnabled(true);
            installButton.setText("Open RockMap");
            installButton.setOnClickListener(v -> markFinishedAndOpen());
            return;
        }

        boolean active = false;
        for (WorkInfo info : infos) {
            WorkInfo.State state = info.getState();
            if (state == WorkInfo.State.RUNNING
                    || state == WorkInfo.State.ENQUEUED
                    || state == WorkInfo.State.BLOCKED) {
                active = true;
                break;
            }
        }

        if (active) {
            workRunning = true;
            installButton.setEnabled(false);
            retryPreviewButton.setVisibility(View.GONE);
            geologyCheck.setEnabled(false);
            if (coreReady && requestedGeology && !geologyReady) {
                progressStatus.setText("Core RockMap data installed. Installing Colorado geology…");
            } else {
                progressStatus.setText("Installing RockMap data… Keep the app installed; Android may continue this work if you leave this screen.");
            }
            return;
        }

        workRunning = false;
        String mapStatus = offlineDataManager.getLastUpdateStatus();
        String geologyStatusText = geologyDataManager.getLastUpdateStatus();
        StringBuilder failure = new StringBuilder("Setup did not finish.");
        if (mapStatus != null && !mapStatus.trim().isEmpty()) {
            failure.append("\n\nCore data: ").append(mapStatus.trim());
        }
        if (requestedGeology && geologyStatusText != null && !geologyStatusText.trim().isEmpty()) {
            failure.append("\n\nGeology: ").append(geologyStatusText.trim());
        }
        failure.append("\n\nCheck your connection and available storage, then retry.");
        progressStatus.setText(failure.toString());

        retryPreviewButton.setVisibility(View.VISIBLE);
        installButton.setEnabled(false);
        installButton.setText("Setup incomplete");
        TourDebugLog.mapDiagnostic("INITIAL_DATA_SETUP",
                "state=incomplete coreReady=" + coreReady
                        + " geologyRequested=" + requestedGeology
                        + " geologyReady=" + geologyReady);
    }

    private boolean isRequestedSetupComplete() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_STARTED, false)) return false;
        if (!offlineDataManager.hasRenderableActivePack()) return false;
        boolean geologyRequested = prefs.getBoolean(KEY_GEOLOGY_REQUESTED, false);
        return !geologyRequested || geologyDataManager.getActiveDatabaseFile() != null;
    }

    private void markFinishedAndOpen() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FINISHED, true)
                .apply();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (workLiveData != null && workObserver != null) {
            workLiveData.removeObserver(workObserver);
        }
        super.onDestroy();
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 MB";
        double mb = bytes / (1024d * 1024d);
        if (mb < 1d) return String.format(Locale.US, "%.0f KB", bytes / 1024d);
        if (mb < 100d) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.0f MB", mb);
    }

    private String display(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private TextView heading(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(28, 28, 28));
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13.5f);
        view.setTextColor(Color.rgb(50, 50, 50));
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        return button;
    }

    private LinearLayout.LayoutParams fullButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(2), 0, dp(2));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

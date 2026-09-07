package com.rockmap.app;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.rockmap.app.offline.DataInstallProgress;

import java.util.List;
import java.util.Locale;

/**
 * Presentation-only progress UI for RockMap's existing first-install and update WorkManager jobs.
 * It observes worker progress and does not enqueue, cancel, retry, or alter any data work.
 */
public final class DataInstallProgressController {
    private static final String INITIAL_WORK = "rockmap-initial-data-setup";
    private static final String UPDATE_WORK = "rockmap-user-data-update-install";
    private static final String PANEL_TAG = "rockmap-data-install-progress-panel";

    private final Activity activity;
    private final String workName;
    private LiveData<List<WorkInfo>> liveData;
    private Observer<List<WorkInfo>> observer;
    private LinearLayout panel;
    private TextView status;
    private ProgressBar progress;
    private boolean attached;
    private String lastWorkSignature = "";

    public DataInstallProgressController(Activity activity) {
        this.activity = activity;
        if (activity instanceof InitialDataSetupActivity) {
            workName = INITIAL_WORK;
        } else if (activity instanceof DataUpdateSettingsActivity) {
            workName = UPDATE_WORK;
        } else {
            workName = null;
        }
    }

    public void attach() {
        if (attached || workName == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!ensureUi()) return;
        attached = true;
        liveData = WorkManager.getInstance(activity).getWorkInfosForUniqueWorkLiveData(workName);
        observer = this::render;
        liveData.observeForever(observer);
        WholeAppDiagnostics.event("WORK_OBSERVER",
                "workName=" + workName + " state=attached activity=" + activity.getClass().getSimpleName());
    }

    public void destroy() {
        if (liveData != null && observer != null) liveData.removeObserver(observer);
        liveData = null;
        observer = null;
        attached = false;
        WholeAppDiagnostics.event("WORK_OBSERVER",
                "workName=" + workName + " state=destroyed activity=" + activity.getClass().getSimpleName());
    }

    private boolean ensureUi() {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return false;
        ViewGroup contentGroup = (ViewGroup) content;
        if (contentGroup.getChildCount() < 1) return false;
        View rootView = contentGroup.getChildAt(0);
        if (!(rootView instanceof LinearLayout)) return false;
        LinearLayout root = (LinearLayout) rootView;

        View existing = root.findViewWithTag(PANEL_TAG);
        if (existing instanceof LinearLayout) {
            panel = (LinearLayout) existing;
            if (panel.getChildCount() >= 2
                    && panel.getChildAt(0) instanceof TextView
                    && panel.getChildAt(1) instanceof ProgressBar) {
                status = (TextView) panel.getChildAt(0);
                progress = (ProgressBar) panel.getChildAt(1);
                return true;
            }
        }

        panel = new LinearLayout(activity);
        panel.setTag(PANEL_TAG);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(5), 0, dp(5));
        panel.setVisibility(View.GONE);

        status = new TextView(activity);
        status.setTextSize(13f);
        status.setTextColor(Color.rgb(45, 45, 45));
        status.setTextIsSelectable(true);
        status.setPadding(dp(2), 0, dp(2), dp(4));
        panel.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setIndeterminate(false);
        panel.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));

        int index = Math.max(0, root.getChildCount() - 3);
        root.addView(panel, index, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (activity instanceof DataUpdateSettingsActivity) {
            attachDiagnosticsControls(root);
        }
        return true;
    }

    private void attachDiagnosticsControls(LinearLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (!(child instanceof ScrollView)) continue;
            ScrollView scroll = (ScrollView) child;
            if (scroll.getChildCount() < 1) return;
            View details = scroll.getChildAt(0);
            if (details instanceof LinearLayout) {
                DiagnosticsPanel.addTo(activity, (LinearLayout) details);
            }
            return;
        }
    }

    private void render(List<WorkInfo> infos) {
        if (panel == null || status == null || progress == null
                || activity.isFinishing() || activity.isDestroyed()) return;

        logWorkState(infos);
        WorkInfo active = chooseActive(infos);
        if (active == null) {
            panel.setVisibility(View.GONE);
            return;
        }

        Data data = active.getProgress();
        String phase = data.getString(DataInstallProgress.KEY_PHASE);
        long done = data.getLong(DataInstallProgress.KEY_BYTES_DONE, 0L);
        long total = data.getLong(DataInstallProgress.KEY_BYTES_TOTAL, 0L);
        boolean indeterminate = data.getBoolean(
                DataInstallProgress.KEY_INDETERMINATE, total <= 0L);

        if (phase == null || phase.trim().isEmpty()) {
            WorkInfo.State state = active.getState();
            phase = state == WorkInfo.State.BLOCKED
                    ? "Waiting to continue data installation…"
                    : state == WorkInfo.State.ENQUEUED
                    ? "Preparing data installation…"
                    : "Starting data installation…";
            indeterminate = true;
        }

        panel.setVisibility(View.VISIBLE);
        if (indeterminate || total <= 0L) {
            progress.setIndeterminate(true);
            status.setText(phase);
            progress.setContentDescription(phase);
            return;
        }

        progress.setIndeterminate(false);
        long cappedDone = Math.max(0L, Math.min(done, total));
        int value = (int) Math.min(1000L, (cappedDone * 1000L) / Math.max(1L, total));
        int percent = (int) Math.min(100L, (cappedDone * 100L) / Math.max(1L, total));
        progress.setProgress(value);
        String text = phase + " " + percent + "% — "
                + formatBytes(cappedDone) + " of " + formatBytes(total);
        status.setText(text);
        progress.setContentDescription(text);
    }

    private void logWorkState(List<WorkInfo> infos) {
        StringBuilder signature = new StringBuilder();
        StringBuilder detail = new StringBuilder("workName=").append(workName);
        if (infos == null || infos.isEmpty()) {
            signature.append("empty");
            detail.append(" entries=0");
        } else {
            detail.append(" entries=").append(infos.size());
            for (WorkInfo info : infos) {
                if (info == null) continue;
                Data data = info.getProgress();
                String phase = data.getString(DataInstallProgress.KEY_PHASE);
                long done = data.getLong(DataInstallProgress.KEY_BYTES_DONE, 0L);
                long total = data.getLong(DataInstallProgress.KEY_BYTES_TOTAL, 0L);
                int percent = total > 0L ? (int) Math.min(100L, (Math.max(0L, done) * 100L) / total) : -1;
                String compact = info.getId() + ":" + info.getState() + ":" + info.getRunAttemptCount()
                        + ":" + percent + ":" + (phase == null ? "" : phase);
                signature.append('|').append(compact);
                detail.append(" [id=").append(info.getId())
                        .append(" state=").append(info.getState())
                        .append(" attempt=").append(info.getRunAttemptCount())
                        .append(" percent=").append(percent)
                        .append(" done=").append(done)
                        .append(" total=").append(total)
                        .append(" phase=").append(phase == null ? "" : phase.replace('\n', ' '))
                        .append(']');
            }
        }
        String current = signature.toString();
        if (current.equals(lastWorkSignature)) return;
        lastWorkSignature = current;
        WholeAppDiagnostics.event("WORK_STATE", detail.toString());
    }

    private static WorkInfo chooseActive(List<WorkInfo> infos) {
        if (infos == null || infos.isEmpty()) return null;
        WorkInfo queued = null;
        for (WorkInfo info : infos) {
            if (info == null) continue;
            if (info.getState() == WorkInfo.State.RUNNING) return info;
            if ((info.getState() == WorkInfo.State.ENQUEUED
                    || info.getState() == WorkInfo.State.BLOCKED) && queued == null) {
                queued = info;
            }
        }
        return queued;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f MB", value);
        return String.format(Locale.US, "%.2f GB", value / 1024.0);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

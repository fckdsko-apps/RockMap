package com.rockmap.app.updates;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.rockmap.app.DataUpdateSettingsActivity;

/**
 * Small MainActivity integration helper.
 *
 * It keeps "Check selected sizes" and "Updates & schedule" together in the pinned action area
 * of Offline Maps & Data, so update controls never depend on scrolling.
 */
public final class DataUpdateUiBridge {
    private DataUpdateUiBridge() {}

    public static View pinnedCheckAndUpdates(Activity activity, Button checkSizes) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.setMargins(0, 0, dp(activity, 3), 0);
        row.addView(checkSizes, left);

        DataUpdateScheduler.State state = DataUpdateScheduler.getState(activity);
        Button updates = new Button(activity);
        updates.setAllCaps(false);
        updates.setTextSize(11.5f);
        updates.setMinHeight(dp(activity, 48));
        updates.setMinimumHeight(dp(activity, 48));
        updates.setText(state.hasUpdate() ? "Updates available" : "Updates & schedule");
        updates.setContentDescription(state.hasUpdate()
                ? "RockMap data updates are available; open update settings"
                : "Open RockMap data update settings and scan schedule");
        updates.setOnClickListener(v ->
                activity.startActivity(new Intent(activity, DataUpdateSettingsActivity.class)));

        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        right.setMargins(dp(activity, 3), 0, 0, 0);
        row.addView(updates, right);
        return row;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

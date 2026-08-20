package com.rockmap.app;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;

import com.rockmap.app.field.FieldActivity;

/**
 * Commit-1 integration shim. It adds one compact Field entry above the existing map toolbar
 * without changing MainActivity or the recently verified GPS-centering path.
 */
public final class RockMapApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static final String FIELD_TAG = "rockmap-field-entry";

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    private void wireFieldButton(Activity activity) {
        if (!(activity instanceof MainActivity) || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        decor.post(() -> {
            Button markers = findButton(decor, "Markers");
            if (markers == null || !(markers.getParent() instanceof ViewGroup)) return;
            ViewGroup markerRow = (ViewGroup) markers.getParent();
            if (!(markerRow.getParent() instanceof ViewGroup)) return;
            ViewGroup controls = (ViewGroup) markerRow.getParent();
            if (!(controls.getParent() instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) controls.getParent();
            if (root.findViewWithTag(FIELD_TAG) != null) return;

            Button field = new Button(activity);
            field.setTag(FIELD_TAG);
            field.setText("Field");
            field.setAllCaps(false);
            field.setTextSize(12f);
            field.setMinWidth(dp(activity, 82));
            field.setMinimumWidth(dp(activity, 82));
            field.setMinHeight(dp(activity, 48));
            field.setMinimumHeight(dp(activity, 48));
            field.setContentDescription("Open RockMap Field tools");
            field.setOnClickListener(v -> activity.startActivity(new Intent(activity, FieldActivity.class)));

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(activity, 92), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            params.setMargins(0, 0, dp(activity, 10), dp(activity, 112));
            root.addView(field, params);

            // Reposition once the existing two-row toolbar has its real measured height/insets.
            controls.post(() -> {
                ViewGroup.LayoutParams raw = field.getLayoutParams();
                if (!(raw instanceof FrameLayout.LayoutParams)) return;
                FrameLayout.LayoutParams positioned = (FrameLayout.LayoutParams) raw;
                positioned.bottomMargin = controls.getHeight() + dp(activity, 8);
                field.setLayoutParams(positioned);
            });
        });
    }

    private Button findButton(View view, String text) {
        if (view instanceof Button) {
            Button button = (Button) view;
            if (text.contentEquals(button.getText())) return button;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { wireFieldButton(activity); }
    @Override public void onActivityStarted(Activity activity) { wireFieldButton(activity); }
    @Override public void onActivityResumed(Activity activity) { wireFieldButton(activity); }
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}

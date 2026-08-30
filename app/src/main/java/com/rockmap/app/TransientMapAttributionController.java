package com.rockmap.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.rockmap.app.map.MapController;

import org.maplibre.android.maps.MapView;

/**
 * Provides OSM attribution without permanent map furniture.
 *
 * The OpenStreetMap Foundation permits interactive-map attribution to collapse automatically
 * after five seconds when the full origin/license information remains easy to find elsewhere.
 * RockMap keeps that permanent information in Data Sources & Licenses.
 */
final class TransientMapAttributionController {
    private static final long VISIBLE_MILLIS = 5_000L;
    private static boolean shownThisProcess;

    private final Activity activity;
    private MapView mapView;
    private MapView.OnDidFinishRenderingMapListener renderListener;
    private TextView attributionView;
    private Runnable removeRunnable;
    private boolean destroyed;

    TransientMapAttributionController(Activity activity) {
        this.activity = activity;
    }

    void attach() {
        if (destroyed) return;

        MapView found = findMapView(activity.getWindow().getDecorView());
        if (found == null) {
            activity.getWindow().getDecorView().postDelayed(this::attach, 120L);
            return;
        }

        if (mapView != found) {
            detachRenderListener();
            mapView = found;
            renderListener = fully -> {
                if (fully) inspectRenderedStyle();
            };
            mapView.addOnDidFinishRenderingMapListener(renderListener);
        }

        // RockMap supplies its own OSM-safe-harbour presentation, so the SDK's permanent
        // attribution ornament is intentionally disabled to avoid duplicate map clutter.
        mapView.getMapAsync(mapLibreMap -> {
            mapLibreMap.getUiSettings().setAttributionEnabled(false);
            mapLibreMap.getStyle(style -> {
                if (style.getSource(MapController.BASE_SOURCE) != null) {
                    showIfNeeded();
                }
            });
        });
    }

    private void inspectRenderedStyle() {
        if (destroyed || shownThisProcess || mapView == null) return;
        mapView.getMapAsync(mapLibreMap -> {
            mapLibreMap.getUiSettings().setAttributionEnabled(false);
            mapLibreMap.getStyle(style -> {
                if (style.getSource(MapController.BASE_SOURCE) != null) {
                    showIfNeeded();
                }
            });
        });
    }

    private void showIfNeeded() {
        if (destroyed || shownThisProcess || mapView == null) return;
        shownThisProcess = true;

        TextView label = new TextView(activity);
        attributionView = label;
        label.setText("© OpenStreetMap contributors");
        label.setTextSize(12f);
        label.setTextColor(Color.rgb(45, 45, 45));
        label.setPadding(dp(6), dp(3), dp(6), dp(3));
        label.setAllCaps(false);
        label.setClickable(true);
        label.setFocusable(false);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        label.setContentDescription("Map © OpenStreetMap contributors. Tap for data sources and licenses.");

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(224, 255, 255, 255));
        background.setCornerRadius(dp(4));
        label.setBackground(background);
        label.setElevation(dp(1));
        label.setAlpha(0f);

        WindowInsets insets = mapView.getRootWindowInsets();
        int topInset = insets == null ? 0 : insets.getSystemWindowInsetTop();

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        params.setMargins(dp(8), topInset + dp(8), dp(8), 0);

        label.setOnClickListener(v ->
                activity.startActivity(new Intent(activity, DataSourcesLicensesActivity.class)));

        mapView.addView(label, params);
        mapView.bringChildToFront(label);
        label.animate().alpha(1f).setDuration(120L).start();

        removeRunnable = () -> {
            if (attributionView != label) return;
            label.animate()
                    .alpha(0f)
                    .setDuration(300L)
                    .withEndAction(() -> {
                        if (label.getParent() == mapView) mapView.removeView(label);
                        if (attributionView == label) attributionView = null;
                        TourDebugLog.mapDiagnostic("MAP_ATTRIBUTION", "state=hidden");
                    })
                    .start();
        };
        label.postDelayed(removeRunnable, VISIBLE_MILLIS);
        TourDebugLog.mapDiagnostic("MAP_ATTRIBUTION",
                "state=shown durationMs=" + VISIBLE_MILLIS + " permanentSdkOrnament=false");
    }

    void destroy() {
        destroyed = true;
        detachRenderListener();
        if (attributionView != null && removeRunnable != null) {
            attributionView.removeCallbacks(removeRunnable);
        }
        if (attributionView != null && attributionView.getParent() == mapView) {
            mapView.removeView(attributionView);
        }
        attributionView = null;
        removeRunnable = null;
        mapView = null;
    }

    private void detachRenderListener() {
        if (mapView != null && renderListener != null) {
            mapView.removeOnDidFinishRenderingMapListener(renderListener);
        }
        renderListener = null;
    }

    private MapView findMapView(View view) {
        if (view instanceof MapView) return (MapView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            MapView found = findMapView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

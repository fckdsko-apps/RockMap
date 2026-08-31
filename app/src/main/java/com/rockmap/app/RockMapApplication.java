package com.rockmap.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.rockmap.app.field.FieldMapController;
import com.rockmap.app.field.FieldMapPolishController;
import com.rockmap.app.updates.DataUpdateScheduler;

import java.util.WeakHashMap;

/**
 * Installs the map-first Field workspace without replacing MainActivity. This deliberately
 * leaves the confirmed GPS-centering implementation in MainActivity untouched.
 */
public final class RockMapApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final WeakHashMap<Activity, FieldMapController> controllers = new WeakHashMap<>();
    private final WeakHashMap<Activity, FieldMapPolishController> polishControllers = new WeakHashMap<>();
    private final WeakHashMap<Activity, TransientMapAttributionController> attributionControllers = new WeakHashMap<>();

    @Override public void onCreate() {
        super.onCreate();

        // Keep the source-level debugger marker expected by scripts/inject_tour_debug.py.
        // The injector is idempotent and will leave this alone when it sees the marker.
        TourDebugLog.install(this);

        // Manifest-only background checks. This schedules no large data transfer.
        DataUpdateScheduler.ensureScheduled(this);
        DataUpdateScheduler.ensureNotificationChannel(this);

        registerActivityLifecycleCallbacks(this);
    }

    private FieldMapController controller(Activity activity) {
        if (!(activity instanceof MainActivity)) return null;
        FieldMapController controller = controllers.get(activity);
        if (controller == null) {
            controller = new FieldMapController(activity);
            controllers.put(activity, controller);
        }
        return controller;
    }

    private FieldMapPolishController polish(Activity activity) {
        if (!(activity instanceof MainActivity)) return null;
        FieldMapPolishController controller = polishControllers.get(activity);
        if (controller == null) {
            controller = new FieldMapPolishController(activity);
            polishControllers.put(activity, controller);
        }
        return controller;
    }

    private TransientMapAttributionController attribution(Activity activity) {
        if (!(activity instanceof MainActivity)) return null;
        TransientMapAttributionController controller = attributionControllers.get(activity);
        if (controller == null) {
            controller = new TransientMapAttributionController(activity);
            attributionControllers.put(activity, controller);
        }
        return controller;
    }

    private void attach(Activity activity) {
        FieldMapController controller = controller(activity);
        if (controller != null) controller.attach();
        FieldMapPolishController polish = polish(activity);
        if (polish != null) polish.attach();
        TransientMapAttributionController attribution = attribution(activity);
        if (attribution != null) attribution.attach();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        if (activity instanceof MainActivity) activity.getWindow().getDecorView().post(() -> attach(activity));
    }

    @Override public void onActivityStarted(Activity activity) {
        if (activity instanceof MainActivity) activity.getWindow().getDecorView().post(() -> attach(activity));
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        activity.getWindow().getDecorView().post(() -> {
            FieldMapController controller = controller(activity);
            if (controller != null) controller.onResume();
            FieldMapPolishController polish = polishControllers.get(activity);
            if (polish != null) polish.onResume();
            TransientMapAttributionController attribution = attribution(activity);
            if (attribution != null) attribution.attach();
        });
    }

    @Override public void onActivityPaused(Activity activity) {
        FieldMapController controller = controllers.get(activity);
        if (controller != null) controller.onPause();
        FieldMapPolishController polish = polishControllers.get(activity);
        if (polish != null) polish.onPause();
    }

    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override public void onActivityDestroyed(Activity activity) {
        FieldMapController controller = controllers.remove(activity);
        if (controller != null) controller.destroy();
        FieldMapPolishController polish = polishControllers.remove(activity);
        if (polish != null) polish.destroy();
        TransientMapAttributionController attribution = attributionControllers.remove(activity);
        if (attribution != null) attribution.destroy();
    }
}

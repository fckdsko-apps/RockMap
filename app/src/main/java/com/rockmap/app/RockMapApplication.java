package com.rockmap.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.rockmap.app.field.FieldMapController;

import java.util.WeakHashMap;

/**
 * Installs the map-first Field workspace without replacing MainActivity. This deliberately
 * leaves the confirmed GPS-centering implementation in MainActivity untouched.
 */
public final class RockMapApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final WeakHashMap<Activity, FieldMapController> controllers = new WeakHashMap<>();

    @Override public void onCreate() {
        super.onCreate();
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

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        FieldMapController controller = controller(activity);
        if (controller != null) activity.getWindow().getDecorView().post(controller::attach);
    }

    @Override public void onActivityStarted(Activity activity) {
        FieldMapController controller = controller(activity);
        if (controller != null) activity.getWindow().getDecorView().post(controller::attach);
    }

    @Override public void onActivityResumed(Activity activity) {
        FieldMapController controller = controller(activity);
        if (controller != null) activity.getWindow().getDecorView().post(controller::onResume);
    }

    @Override public void onActivityPaused(Activity activity) {
        FieldMapController controller = controllers.get(activity);
        if (controller != null) controller.onPause();
    }

    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override public void onActivityDestroyed(Activity activity) {
        FieldMapController controller = controllers.remove(activity);
        if (controller != null) controller.destroy();
    }
}

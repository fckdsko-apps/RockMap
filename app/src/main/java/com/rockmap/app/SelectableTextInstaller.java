package com.rockmap.app;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * App-wide policy for informational text: plain read-only TextViews should support Android's
 * normal long-press selection/copy behavior. Interactive text controls are deliberately left
 * alone so selection does not compete with their primary action.
 *
 * The installer watches an Activity's decor tree so HUDs and other programmatically-created
 * informational surfaces inherit the policy when they are added later; callers do not need to
 * remember to configure every individual TextView.
 */
public final class SelectableTextInstaller {
    private static final WeakHashMap<View, Boolean> installedRoots = new WeakHashMap<>();
    private static final WeakHashMap<TextView, Boolean> managedTextViews = new WeakHashMap<>();

    private SelectableTextInstaller() {}

    public static void install(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        install(activity.getWindow().getDecorView());
    }

    public static void install(View root) {
        if (root == null) return;
        apply(root);
        synchronized (installedRoots) {
            if (installedRoots.containsKey(root)) return;
            installedRoots.put(root, Boolean.TRUE);
        }
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> apply(root));
    }

    private static void apply(View view) {
        if (view instanceof TextView) configure((TextView) view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            apply(group.getChildAt(i));
        }
    }

    private static void configure(TextView textView) {
        boolean managed;
        synchronized (managedTextViews) {
            managed = managedTextViews.containsKey(textView);
        }

        // Buttons/inputs are controls, not informational copy. Also leave alone TextViews with
        // their own click/link/long-press behavior so copy selection cannot steal that gesture.
        boolean interactive = textView instanceof Button
                || textView instanceof EditText
                || textView.hasOnClickListeners()
                || textView.isClickable()
                || textView.getMovementMethod() != null
                || (!managed && textView.isLongClickable());

        if (interactive) {
            if (managed) {
                textView.setTextIsSelectable(false);
                synchronized (managedTextViews) {
                    managedTextViews.remove(textView);
                }
            }
            return;
        }

        if (!textView.isTextSelectable()) textView.setTextIsSelectable(true);
        synchronized (managedTextViews) {
            managedTextViews.put(textView, Boolean.TRUE);
        }
    }
}

package com.rockmap.app;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Makes user-facing informational text copyable without changing interactive controls.
 *
 * RockMap displays coordinates, source metadata, geology/mineral summaries, land/claim details,
 * Field notes and other reference text that users may reasonably need to copy into another app.
 * Buttons, check/radio controls, editable fields and explicitly clickable text are intentionally
 * excluded so text selection never steals their interaction model.
 */
public final class SelectableText {
    private SelectableText() {}

    public static <T extends TextView> T informational(T view) {
        if (view == null) return null;
        if (isInteractive(view)) return view;
        view.setTextIsSelectable(true);
        return view;
    }

    /** Recursively enables selection on non-interactive TextViews already present in a UI tree. */
    public static void applyToTree(View root) {
        if (root == null) return;
        if (root instanceof TextView) {
            informational((TextView) root);
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyToTree(group.getChildAt(i));
        }
    }

    private static boolean isInteractive(TextView view) {
        return view instanceof Button
                || view instanceof EditText
                || view instanceof CompoundButton
                || view.isClickable()
                || view.isLongClickable();
    }
}

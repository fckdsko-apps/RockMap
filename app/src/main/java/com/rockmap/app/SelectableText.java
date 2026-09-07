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
 * Buttons, check/radio controls, editable fields, explicitly clickable text, and text inside
 * clickable/long-clickable containers are intentionally excluded so text selection never steals
 * the interaction model of a tappable row or card.
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
        applyToTree(root, false);
    }

    private static void applyToTree(View root, boolean interactiveAncestor) {
        if (root == null) return;

        // A selectable TextView becomes its own touch target. Never do that beneath a clickable
        // row/card, because the child would consume taps that belong to the parent action.
        if (root instanceof TextView && !interactiveAncestor) {
            informational((TextView) root);
        }

        if (!(root instanceof ViewGroup)) return;

        boolean blockDescendantSelection = interactiveAncestor || isInteractiveContainer(root);
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyToTree(group.getChildAt(i), blockDescendantSelection);
        }
    }

    private static boolean isInteractiveContainer(View view) {
        return view.isClickable() || view.isLongClickable();
    }

    private static boolean isInteractive(TextView view) {
        return view instanceof Button
                || view instanceof EditText
                || view instanceof CompoundButton
                || view.isClickable()
                || view.isLongClickable();
    }
}

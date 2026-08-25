package com.rockmap.app;

import android.app.Activity;
import android.app.AlertDialog;

/** Short contextual reference help that remains available after onboarding. */
public final class RockMapHelp {
    private RockMapHelp() {}

    public static void showResearch(Activity activity, String view, Runnable startTour) {
        String viewText = view == null || view.trim().isEmpty() ? "Research" : view.trim();
        String message = "Research keeps geography and evidence together on the map.\n\n"
                + "Geology — mapped interpretive geologic units. A mapped unit is not a property, access, hazard, or collecting boundary.\n\n"
                + "Mineral Evidence — documented mineral/locality/source records. Evidence-density heatmaps show where installed evidence is denser or stronger; they are not probabilities of finding specimens.\n\n"
                + "Historic Mines — documented mine/workings records and nearby evidence. Old workings can be hazardous.\n\n"
                + "Claims and land management remain separate map layers. A missing rendered claim does not prove land is unclaimed, and land-management mapping is not a parcel/title determination.\n\n"
                + "Current view: " + viewText + ".\n\n"
                + "Full and topic tours are always available from the small ? help button above the main map controls.";
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Research help")
                .setMessage(message)
                .setPositiveButton("Close", null);
        if (startTour != null) builder.setNeutralButton("Start guided tour", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            if (startTour == null) return;
            android.widget.Button start = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            start.setOnClickListener(v -> {
                dialog.setOnDismissListener(null);
                dialog.dismiss();
                android.view.View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (decor != null) decor.post(startTour);
                else startTour.run();
            });
        });
        dialog.show();
    }

    public static void showOfflineData(Activity activity, Runnable startTour) {
        String message = "RockMap has two independently installable offline packages:\n\n"
                + "• Core Offline Map & Research Data — the basemap, offline place search, Mineral Evidence, Historic Mines, land-management context, and mining-claim records are one integrity-versioned pack.\n\n"
                + "• Queryable Colorado Geology — a separate verified statewide geology database.\n\n"
                + "Check selected sizes reads only the small published manifests. It does not install the large files. RockMap shows the expected transfer before Install selected is enabled, reuses current files when possible, and verifies downloaded data before activation.\n\n"
                + "Full and topic tours are always available from the small ? help button above the main map controls.";
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("Offline Maps & Data help")
                .setMessage(message)
                .setPositiveButton("Close", null);
        if (startTour != null) builder.setNeutralButton("Start guided tour", (d, w) -> startTour.run());
        builder.show();
    }
}

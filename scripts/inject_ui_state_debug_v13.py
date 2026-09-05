#!/usr/bin/env python3
"""v13: route map Field-menu guided tours through the shared warning/cleanup pipeline.

FieldActivity/Help & Tours already uses TourStartCoordinator.confirm(). The floating map Field menu
has a separate contextual guided-tour launcher in FieldMapController; it previously started
FieldTourState directly (or used EXTRA_START_TRAINING_FIELD_TOUR for training tours), bypassing the
shared warning and, for the training branch, the queued clean-start contract. This pass removes
that duplicate entry behavior and reuses the existing coordinator + MainActivity pending-tour
consumer.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def replace_once(path: Path, marker: str, old: str, new: str, label: str) -> None:
    current = text(path)
    if marker in current:
        print(f"{label}: already present")
        return
    count = current.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one match in {path.relative_to(ROOT)}, found {count}"
        )
    path.write_text(current.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def main() -> int:
    field = ROOT / "app/src/main/java/com/rockmap/app/field/FieldMapController.java"
    main_activity = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    for path in (field, main_activity):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    original_field = text(field)
    try:
        # v12's public pending-tour consumer must already exist before the map Field-menu route is
        # allowed to call it. This keeps one cleanup implementation and one queue consumer.
        generated_main = text(main_activity)
        for required in (
            "public void onMapWorkspaceResumedForTour()",
            "v12-all-pending-field-tours-clean-first",
        ):
            if required not in generated_main:
                raise RuntimeError(
                    f"Field-menu warning v13 requires prior generated MainActivity marker: {required}"
                )

        old_start = '''            start.setOnClickListener(v -> {
                helpDialog.setOnDismissListener(d -> {
                    final AlertDialog field = fieldDialog != null && fieldDialog.length > 0
                            ? fieldDialog[0] : null;
                    if (fieldMenuTourUsesTrainingArea(guidedTourTool)) {
                        // The Help & Tours path already asks before moving the map. The contextual
                        // ? beside Measure/Prospecting Areas/Field Records must do the same thing.
                        // Do not mark the numbered tour active until Saint Peters Dome is selected.
                        if (field != null && field.isShowing()) field.dismiss();
                        main.post(() -> confirmTrainingAreaForFieldMenuTour(guidedTourTool));
                        return;
                    }

                    // Start only after the help window is gone. The Field menu remains alive
                    // underneath, so its real row can be highlighted without racing two dialogs.
                    FieldTourState.start(activity, guidedTourTool);
                    lastFieldTourCoachKey = "";
                    GuidedTourCoach.clear(activity);
                    main.post(() -> {
                        if (field != null && field.isShowing() && field.getWindow() != null) {
                            View target = field.getWindow().getDecorView()
                                    .findViewWithTag(fieldMenuTourTag(guidedTourTool));
                            if (target != null) {
                                showUnifiedFieldMenuTourCoach(field, target, guidedTourTool);
                                return;
                            }
                        }
                        showFieldMenu();
                    });
                });
                helpDialog.dismiss();
            });
'''
        new_start = '''            start.setOnClickListener(v -> {
                final AlertDialog field = fieldDialog != null && fieldDialog.length > 0
                        ? fieldDialog[0] : null;

                // Close the explanatory/menu dialogs before showing the shared warning. The tour
                // is not active yet, so the Field-menu dismiss listener cannot accidentally finish it.
                helpDialog.setOnDismissListener(null);
                helpDialog.dismiss();
                if (field != null && field.isShowing()) field.dismiss();

                TourDebugLog.mapDiagnostic("TOUR_FIELD_MENU_WARNING_REQUIRED",
                        "tool=" + guidedTourTool
                                + " training=" + fieldMenuTourUsesTrainingArea(guidedTourTool));
                com.rockmap.app.TourStartCoordinator.confirm(
                        activity,
                        "Field Tools — " + guidedTourTool,
                        () -> {
                            TourDebugLog.mapDiagnostic("TOUR_FIELD_MENU_WARNING_ACCEPTED_ROUTE",
                                    "tool=" + guidedTourTool
                                            + " training=" + fieldMenuTourUsesTrainingArea(guidedTourTool));
                            if (fieldMenuTourUsesTrainingArea(guidedTourTool)) {
                                // Keep the existing second, specific consent before moving to the
                                // training area. The numbered tour is still not active here.
                                main.post(() -> confirmTrainingAreaForFieldMenuTour(guidedTourTool));
                                return;
                            }
                            com.rockmap.app.TourStartCoordinator.queueFieldTour(
                                    activity, guidedTourTool, false, false);
                            if (activity instanceof MainActivity) {
                                ((MainActivity) activity).onMapWorkspaceResumedForTour();
                            }
                        },
                        () -> {
                            Intent tracks = new Intent(activity, FieldActivity.class);
                            tracks.putExtra(FieldActivity.EXTRA_SCREEN, "tracks");
                            activity.startActivity(tracks);
                        });
                // marker: v13-map-field-menu-warning-gate
            });
'''
        replace_once(
            field,
            "v13-map-field-menu-warning-gate",
            old_start,
            new_start,
            "map Field-menu tour warning gate",
        )

        old_training = '''                .setPositiveButton("Continue", (d, w) -> {
                    GuidedTourCoach.clear(activity);
                    Intent intent = new Intent(activity, MainActivity.class);
                    intent.putExtra(MainActivity.EXTRA_START_TRAINING_FIELD_TOUR, tool);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    activity.startActivity(intent);
                })
'''
        new_training = '''                .setPositiveButton("Continue", (d, w) -> {
                    GuidedTourCoach.clear(activity);
                    com.rockmap.app.TourStartCoordinator.queueFieldTour(
                            activity, tool, true, false);
                    TourDebugLog.mapDiagnostic("TOUR_FIELD_MENU_TRAINING_QUEUED",
                            "tool=" + tool + " warningAlreadyAccepted=true");
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).onMapWorkspaceResumedForTour();
                    }
                    // marker: v13-map-field-training-uses-pending-cleanup
                })
'''
        replace_once(
            field,
            "v13-map-field-training-uses-pending-cleanup",
            old_training,
            new_training,
            "map Field-menu training tour queued cleanup route",
        )

        updated = text(field)
        method_start = updated.index("private void showFieldMenuHelp")
        method_end = updated.index("private boolean fieldMenuTourUsesTrainingArea", method_start)
        help_block = updated[method_start:method_end]
        if "TourStartCoordinator.confirm" not in help_block:
            raise RuntimeError("v13 warning guard failed: Field menu does not call shared confirmation")
        if "FieldTourState.start(activity, guidedTourTool)" in help_block:
            raise RuntimeError("v13 bypass guard failed: Field menu still starts tour directly")

        training_start = updated.index("private void confirmTrainingAreaForFieldMenuTour")
        training_end = updated.index("private void showNavigateMenu", training_start)
        training_block = updated[training_start:training_end]
        if "TourStartCoordinator.queueFieldTour" not in training_block:
            raise RuntimeError("v13 training guard failed: training route is not queued")
        if "EXTRA_START_TRAINING_FIELD_TOUR" in training_block:
            raise RuntimeError("v13 training bypass guard failed: legacy direct intent remains")
        if "onMapWorkspaceResumedForTour()" not in help_block or "onMapWorkspaceResumedForTour()" not in training_block:
            raise RuntimeError("v13 consumer guard failed: queued Field menu tour is not consumed on resumed map")

        # Routing-only change: never alter saved data or the stabilized HUD implementation here.
        touched = help_block + training_block
        for forbidden in (
            "deleteTrack(", "deleteArea(", "deleteFieldRecord(", "deleteWaypoint(",
            "deleteTrip(", "MAPPED_HUD_FAST_REOPEN", "RESEARCH_TOUR_STEP17_HANDOFF",
            "closePresentationForTourStart(",
        ):
            if forbidden in touched:
                raise RuntimeError(f"v13 scope guard failed: unexpected token {forbidden}")

        print("Field-menu v13 warning/queue routing complete.")
        print("Floating Field-menu tours now reuse the same warning and v12 clean-start consumer.")
        return 0
    except Exception:
        field.write_text(original_field, encoding="utf-8")
        print("Field-menu v13 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())

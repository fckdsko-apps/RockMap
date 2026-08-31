package com.rockmap.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import com.rockmap.app.field.FieldDatabase;
import com.rockmap.app.field.ProspectingAreaResearchStore;
import com.rockmap.app.field.ProspectingAreaVisibility;
import com.rockmap.app.waypoints.RockMapDatabase;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Centralized destructive operations for RockMap user-created data. */
public final class UserDataManager {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final String AREA_VISIBILITY_PREFS = "rockmap-prospecting-area-visibility";
    private static final String AREA_RESEARCH_PREFS = "rockmap-prospecting-area-research-v1";

    private UserDataManager() {}

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public static void deleteSavedLocationsAndTrips(Context context, Callback callback) {
        run(context, callback, () -> {
            RockMapDatabase.get(context).clearAllTables();
            clearImportedWaypointOwnership(context);
        });
    }

    public static void deleteFieldData(Context context, Callback callback) {
        run(context, callback, () -> {
            FieldDatabase field = FieldDatabase.get(context);
            if (field.getActiveTrack() != null) {
                throw new IllegalStateException(
                        "Stop the active track recording before deleting track and field data.");
            }

            List<FieldDatabase.Area> areasBeforeDelete = field.listAreas();
            clearFieldTables(field.getWritableDatabase(), false);
            clearProspectingAreaState(context, areasBeforeDelete);
            verifyFieldDataDeleted(field);
            releasePersistedDocumentPermissions(context);
        });
    }

    public static void deleteAllUserCreatedData(Context context, Callback callback) {
        run(context, callback, () -> {
            FieldDatabase field = FieldDatabase.get(context);
            if (field.getActiveTrack() != null) {
                throw new IllegalStateException(
                        "Stop the active track recording before deleting all user-created data.");
            }

            List<FieldDatabase.Area> areasBeforeDelete = field.listAreas();

            RockMapDatabase.get(context).clearAllTables();
            clearFieldTables(field.getWritableDatabase(), true);
            clearProspectingAreaState(context, areasBeforeDelete);
            verifyFieldDataDeleted(field);
            releasePersistedDocumentPermissions(context);
        });
    }

    private static void run(Context context, Callback callback, ThrowingRunnable work) {
        Handler main = new Handler(Looper.getMainLooper());
        EXECUTOR.execute(() -> {
            try {
                work.run();
                TourDebugLog.mapDiagnostic("USER_DATA_DELETE", "state=success");
                main.post(callback::onSuccess);
            } catch (Exception ex) {
                String message = ex.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = "RockMap could not complete the deletion.";
                }
                final String safe = message;
                TourDebugLog.mapDiagnostic("USER_DATA_DELETE",
                        "state=error message=" + safe.replace('\n', ' '));
                main.post(() -> callback.onError(safe));
            }
        });
    }

    private static void clearImportedWaypointOwnership(Context context) {
        SQLiteDatabase db = FieldDatabase.get(context).getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("import_items", "item_type=?",
                    new String[]{FieldDatabase.IMPORT_WAYPOINT});
            db.execSQL("UPDATE import_batches SET waypoint_count=0");
            db.execSQL("DELETE FROM import_batches WHERE id NOT IN "
                    + "(SELECT DISTINCT batch_id FROM import_items)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void clearFieldTables(SQLiteDatabase db, boolean clearAllImports) {
        db.beginTransaction();
        try {
            if (clearAllImports) {
                db.delete("import_items", null, null);
                db.delete("import_batches", null, null);
            } else {
                db.delete("import_items", "item_type IN (?,?)",
                        new String[]{FieldDatabase.IMPORT_TRACK, FieldDatabase.IMPORT_AREA});
                db.execSQL("UPDATE import_batches SET track_count=0, area_count=0");
                db.execSQL("DELETE FROM import_batches WHERE id NOT IN "
                        + "(SELECT DISTINCT batch_id FROM import_items)");
            }

            db.delete("track_points", null, null);
            db.delete("tracks", null, null);
            db.delete("field_records", null, null);
            db.delete("area_points", null, null);
            db.delete("areas", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void clearProspectingAreaState(
            Context context, List<FieldDatabase.Area> areasBeforeDelete) {
        if (areasBeforeDelete != null) {
            for (FieldDatabase.Area area : areasBeforeDelete) {
                if (area == null || area.id <= 0L) continue;
                ProspectingAreaVisibility.forget(context, area.id);
                ProspectingAreaResearchStore.forget(context, area.id);
            }
        }

        boolean visibilityCleared = context
                .getSharedPreferences(AREA_VISIBILITY_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        boolean researchCleared = context
                .getSharedPreferences(AREA_RESEARCH_PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();

        if (!visibilityCleared || !researchCleared) {
            throw new IllegalStateException(
                    "Prospecting Area records were removed, but RockMap could not clear all associated local area state.");
        }
    }

    private static void verifyFieldDataDeleted(FieldDatabase field) {
        if (!field.listTracks(1).isEmpty()) {
            throw new IllegalStateException("RockMap could not remove all recorded tracks.");
        }
        if (!field.listFieldRecords().isEmpty()) {
            throw new IllegalStateException("RockMap could not remove all field records.");
        }
        if (!field.listAreas().isEmpty()) {
            throw new IllegalStateException("RockMap could not remove all Prospecting Areas.");
        }
    }

    private static void releasePersistedDocumentPermissions(Context context) {
        ContentResolver resolver = context.getContentResolver();
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            int flags = 0;
            if (permission.isReadPermission()) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if (flags == 0) continue;
            try {
                resolver.releasePersistableUriPermission(permission.getUri(), flags);
            } catch (SecurityException ignored) {
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

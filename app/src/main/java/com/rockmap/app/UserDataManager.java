package com.rockmap.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import com.rockmap.app.field.FieldDatabase;
import com.rockmap.app.waypoints.RockMapDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Centralized destructive operations for RockMap user-created data. */
public final class UserDataManager {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

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
                throw new IllegalStateException("Stop the active track recording before deleting track and field data.");
            }
            clearFieldTables(field.getWritableDatabase(), false);
            releasePersistedDocumentPermissions(context);
        });
    }

    public static void deleteAllUserCreatedData(Context context, Callback callback) {
        run(context, callback, () -> {
            FieldDatabase field = FieldDatabase.get(context);
            if (field.getActiveTrack() != null) {
                throw new IllegalStateException("Stop the active track recording before deleting all user-created data.");
            }

            // Check the active-track condition before deleting either database so the operation
            // cannot partially erase waypoints/trips while a track blocks field-data deletion.
            RockMapDatabase.get(context).clearAllTables();
            clearFieldTables(field.getWritableDatabase(), true);
            releasePersistedDocumentPermissions(context);
        });
    }

    private static void run(Context context, Callback callback, ThrowingRunnable work) {
        Context app = context.getApplicationContext();
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
            db.delete("import_items", "item_type=?", new String[]{FieldDatabase.IMPORT_WAYPOINT});
            db.execSQL("UPDATE import_batches SET waypoint_count=0");
            db.execSQL("DELETE FROM import_batches WHERE id NOT IN "
                    + "(SELECT DISTINCT batch_id FROM import_items)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Deletes field-owned user content. When clearAllImports is false, imported waypoints and
     * their batch records survive; track/area ownership counts are zeroed to remain consistent.
     */
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

    /**
     * Field-record photos remain owned by the user's chosen photo/document provider. RockMap
     * stores only URI references. Once field records are gone, release any persisted document
     * grants RockMap no longer needs. Failure to release a provider grant must not resurrect or
     * block deletion of RockMap database records.
     */
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
                // Provider may already have revoked or changed the grant.
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

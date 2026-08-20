package com.rockmap.app.waypoints;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.rockmap.app.trips.TripDao;
import com.rockmap.app.trips.TripEntity;
import com.rockmap.app.trips.TripItemEntity;

@Database(
        entities = {WaypointEntity.class, TripEntity.class, TripItemEntity.class},
        version = 2,
        exportSchema = false)
public abstract class RockMapDatabase extends RoomDatabase {
    private static volatile RockMapDatabase instance;

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `trips` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`name` TEXT, `plannedDate` TEXT, `notes` TEXT, "
                    + "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)");
            database.execSQL("CREATE TABLE IF NOT EXISTS `trip_items` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`tripId` INTEGER NOT NULL, `name` TEXT, `kind` TEXT, `context` TEXT, "
                    + "`latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `notes` TEXT, "
                    + "`sourceType` TEXT, `sourceRef` TEXT, `sortOrder` INTEGER NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL, "
                    + "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_items_tripId` ON `trip_items` (`tripId`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_trip_items_tripId_sortOrder` ON `trip_items` (`tripId`, `sortOrder`)");
        }
    };

    public abstract WaypointDao waypointDao();
    public abstract TripDao tripDao();

    public static RockMapDatabase get(Context context) {
        RockMapDatabase local = instance;
        if (local == null) {
            synchronized (RockMapDatabase.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(context.getApplicationContext(),
                            RockMapDatabase.class, "rockmap.db")
                            // Preserve user waypoints and trips across schema upgrades.
                            .addMigrations(MIGRATION_1_2)
                            .build();
                    instance = local;
                }
            }
        }
        return local;
    }
}

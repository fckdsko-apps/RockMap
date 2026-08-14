package com.rockmap.app.waypoints;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {WaypointEntity.class}, version = 1, exportSchema = false)
public abstract class RockMapDatabase extends RoomDatabase {
    private static volatile RockMapDatabase instance;

    public abstract WaypointDao waypointDao();

    public static RockMapDatabase get(Context context) {
        RockMapDatabase local = instance;
        if (local == null) {
            synchronized (RockMapDatabase.class) {
                local = instance;
                if (local == null) {
                    local = Room.databaseBuilder(context.getApplicationContext(),
                            RockMapDatabase.class, "rockmap.db")
                            // Never use fallbackToDestructiveMigration for user waypoints.
                            .build();
                    instance = local;
                }
            }
        }
        return local;
    }
}

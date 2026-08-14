package com.rockmap.app.waypoints;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface WaypointDao {
    @Query("SELECT * FROM waypoints ORDER BY createdAt DESC")
    List<WaypointEntity> getAll();

    @Insert
    long insert(WaypointEntity waypoint);

    @Insert
    java.util.List<Long> insertAll(java.util.List<WaypointEntity> waypoints);

    @Update
    void update(WaypointEntity waypoint);

    @Delete
    void delete(WaypointEntity waypoint);
}

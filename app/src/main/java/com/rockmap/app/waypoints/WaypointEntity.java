package com.rockmap.app.waypoints;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "waypoints")
public class WaypointEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public double latitude;
    public double longitude;
    public float accuracyMeters;
    public long capturedAt;
    public String name;
    public String notes;
    public long createdAt;
    public long updatedAt;

    public WaypointEntity(double latitude, double longitude, float accuracyMeters, long capturedAt,
                          String name, String notes, long createdAt, long updatedAt) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.capturedAt = capturedAt;
        this.name = name == null ? "" : name;
        this.notes = notes == null ? "" : notes;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}

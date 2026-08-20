package com.rockmap.app.trips;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "trips")
public class TripEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String name;
    public String plannedDate;
    public String notes;
    public long createdAt;
    public long updatedAt;

    public TripEntity(String name, String plannedDate, String notes,
                      long createdAt, long updatedAt) {
        this.name = name == null ? "" : name;
        this.plannedDate = plannedDate == null ? "" : plannedDate;
        this.notes = notes == null ? "" : notes;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}

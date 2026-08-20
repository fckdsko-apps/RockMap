package com.rockmap.app.trips;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "trip_items",
        foreignKeys = @ForeignKey(
                entity = TripEntity.class,
                parentColumns = "id",
                childColumns = "tripId",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index("tripId"),
                @Index(value = {"tripId", "sortOrder"})
        })
public class TripItemEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long tripId;
    public String name;
    public String kind;
    public String context;
    public double latitude;
    public double longitude;
    public String notes;
    public String sourceType;
    public String sourceRef;
    public int sortOrder;
    public long createdAt;

    public TripItemEntity(long tripId, String name, String kind, String context,
                          double latitude, double longitude, String notes,
                          String sourceType, String sourceRef,
                          int sortOrder, long createdAt) {
        this.tripId = tripId;
        this.name = name == null ? "" : name;
        this.kind = kind == null ? "" : kind;
        this.context = context == null ? "" : context;
        this.latitude = latitude;
        this.longitude = longitude;
        this.notes = notes == null ? "" : notes;
        this.sourceType = sourceType == null ? "" : sourceType;
        this.sourceRef = sourceRef == null ? "" : sourceRef;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }
}

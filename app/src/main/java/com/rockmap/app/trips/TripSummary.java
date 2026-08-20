package com.rockmap.app.trips;

public class TripSummary {
    public long id;
    public String name;
    public String plannedDate;
    public String notes;
    public long createdAt;
    public long updatedAt;
    public long itemCount;

    public TripEntity toEntity() {
        TripEntity trip = new TripEntity(name, plannedDate, notes, createdAt, updatedAt);
        trip.id = id;
        return trip;
    }
}

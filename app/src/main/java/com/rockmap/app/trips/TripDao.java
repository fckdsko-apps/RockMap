package com.rockmap.app.trips;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface TripDao {
    @Query("SELECT t.id, t.name, t.plannedDate, t.notes, t.createdAt, t.updatedAt, "
            + "COUNT(i.id) AS itemCount FROM trips t "
            + "LEFT JOIN trip_items i ON i.tripId = t.id "
            + "GROUP BY t.id ORDER BY t.updatedAt DESC, t.id DESC")
    List<TripSummary> getSummaries();

    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    TripEntity getTrip(long tripId);

    @Query("SELECT * FROM trip_items WHERE tripId = :tripId ORDER BY sortOrder ASC, id ASC")
    List<TripItemEntity> getItems(long tripId);

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM trip_items WHERE tripId = :tripId")
    int nextSortOrder(long tripId);

    @Insert
    long insertTrip(TripEntity trip);

    @Update
    void updateTrip(TripEntity trip);

    @Delete
    void deleteTrip(TripEntity trip);

    @Insert
    long insertItem(TripItemEntity item);

    @Delete
    void deleteItem(TripItemEntity item);

    @Query("UPDATE trip_items SET sortOrder = :sortOrder WHERE id = :itemId")
    void updateItemOrder(long itemId, int sortOrder);
}

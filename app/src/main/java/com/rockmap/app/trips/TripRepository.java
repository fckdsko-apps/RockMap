package com.rockmap.app.trips;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.rockmap.app.waypoints.RockMapDatabase;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class TripRepository {
    private final RockMapDatabase database;
    private final TripDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public TripRepository(Context context) {
        database = RockMapDatabase.get(context);
        dao = database.tripDao();
    }

    public void getSummaries(Consumer<List<TripSummary>> callback) {
        executor.execute(() -> {
            List<TripSummary> rows = dao.getSummaries();
            main.post(() -> callback.accept(rows));
        });
    }

    public void getTrip(long tripId, Consumer<TripEntity> callback) {
        executor.execute(() -> {
            TripEntity trip = dao.getTrip(tripId);
            main.post(() -> callback.accept(trip));
        });
    }

    public void getItems(long tripId, Consumer<List<TripItemEntity>> callback) {
        executor.execute(() -> {
            List<TripItemEntity> rows = dao.getItems(tripId);
            main.post(() -> callback.accept(rows));
        });
    }

    public void create(TripEntity trip, Consumer<TripEntity> callback) {
        executor.execute(() -> {
            trip.id = dao.insertTrip(trip);
            main.post(() -> callback.accept(trip));
        });
    }

    public void update(TripEntity trip, Runnable callback) {
        executor.execute(() -> {
            trip.updatedAt = System.currentTimeMillis();
            dao.updateTrip(trip);
            main.post(callback);
        });
    }

    public void delete(TripEntity trip, Runnable callback) {
        executor.execute(() -> {
            dao.deleteTrip(trip);
            main.post(callback);
        });
    }

    public void addItem(TripItemEntity item, Consumer<TripItemEntity> callback) {
        executor.execute(() -> {
            item.sortOrder = dao.nextSortOrder(item.tripId);
            item.id = dao.insertItem(item);
            TripEntity trip = dao.getTrip(item.tripId);
            if (trip != null) {
                trip.updatedAt = System.currentTimeMillis();
                dao.updateTrip(trip);
            }
            main.post(() -> callback.accept(item));
        });
    }

    public void deleteItem(TripItemEntity item, Runnable callback) {
        executor.execute(() -> {
            dao.deleteItem(item);
            normalizeOrder(item.tripId);
            touch(item.tripId);
            main.post(callback);
        });
    }

    public void moveItem(long tripId, long itemId, int direction, Consumer<Boolean> callback) {
        executor.execute(() -> {
            final boolean[] moved = {false};
            database.runInTransaction(() -> {
                List<TripItemEntity> items = dao.getItems(tripId);
                int index = -1;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).id == itemId) {
                        index = i;
                        break;
                    }
                }
                int target = index + direction;
                if (index < 0 || target < 0 || target >= items.size()) return;
                TripItemEntity current = items.get(index);
                TripItemEntity other = items.get(target);
                int currentOrder = current.sortOrder;
                dao.updateItemOrder(current.id, other.sortOrder);
                dao.updateItemOrder(other.id, currentOrder);
                touch(tripId);
                moved[0] = true;
            });
            main.post(() -> callback.accept(moved[0]));
        });
    }

    private void normalizeOrder(long tripId) {
        List<TripItemEntity> items = dao.getItems(tripId);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).sortOrder != i) {
                dao.updateItemOrder(items.get(i).id, i);
            }
        }
    }

    private void touch(long tripId) {
        TripEntity trip = dao.getTrip(tripId);
        if (trip == null) return;
        trip.updatedAt = System.currentTimeMillis();
        dao.updateTrip(trip);
    }

    public void close() {
        executor.shutdown();
    }
}

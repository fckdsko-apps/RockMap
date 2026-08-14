package com.rockmap.app.waypoints;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class WaypointRepository {
    private final WaypointDao dao;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public WaypointRepository(Context context) {
        dao = RockMapDatabase.get(context).waypointDao();
    }

    public void getAll(Consumer<List<WaypointEntity>> callback) {
        executor.execute(() -> {
            List<WaypointEntity> rows = dao.getAll();
            main.post(() -> callback.accept(rows));
        });
    }

    public void insert(WaypointEntity waypoint, Runnable callback) {
        executor.execute(() -> {
            waypoint.id = dao.insert(waypoint);
            main.post(callback);
        });
    }

    public void insertAll(List<WaypointEntity> waypoints, Consumer<Integer> callback) {
        executor.execute(() -> {
            List<Long> ids = dao.insertAll(waypoints);
            main.post(() -> callback.accept(ids.size()));
        });
    }

    public void update(WaypointEntity waypoint, Runnable callback) {
        executor.execute(() -> {
            waypoint.updatedAt = System.currentTimeMillis();
            dao.update(waypoint);
            main.post(callback);
        });
    }

    public void delete(WaypointEntity waypoint, Runnable callback) {
        executor.execute(() -> {
            dao.delete(waypoint);
            main.post(callback);
        });
    }

    public void close() {
        executor.shutdown();
    }
}

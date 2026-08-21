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
            int assigned = Math.min(ids.size(), waypoints.size());
            for (int i = 0; i < assigned; i++) waypoints.get(i).id = ids.get(i);
            main.post(() -> callback.accept(ids.size()));
        });
    }

    /** Deletes exactly the supplied waypoint rows and nothing else. */
    public void deleteAll(List<WaypointEntity> waypoints, Consumer<Integer> callback) {
        executor.execute(() -> {
            int deleted = 0;
            if (waypoints != null) {
                for (WaypointEntity waypoint : waypoints) {
                    if (waypoint == null) continue;
                    dao.delete(waypoint);
                    deleted++;
                }
            }
            int count = deleted;
            main.post(() -> callback.accept(count));
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

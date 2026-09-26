package com.nexuscraft.nexusgate;

import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks every currently-connected player who has NOT yet typed the correct
 * password. While a player's UUID is in here, GateListener freezes them and
 * blocks everything except /login.
 */
public final class PendingAuthManager {

    public static final class PendingData {
        public int attempts = 0;
        public final BukkitTask timeoutTask;

        public PendingData(BukkitTask timeoutTask) {
            this.timeoutTask = timeoutTask;
        }
    }

    private final Map<UUID, PendingData> pending = new ConcurrentHashMap<>();

    public void addPending(UUID uuid, BukkitTask timeoutTask) {
        pending.put(uuid, new PendingData(timeoutTask));
    }

    public boolean isPending(UUID uuid) {
        return pending.containsKey(uuid);
    }

    public PendingData get(UUID uuid) {
        return pending.get(uuid);
    }

    /** Increments and returns the new failed-attempt count for this player. */
    public int incrementAttempt(UUID uuid) {
        PendingData data = pending.get(uuid);
        if (data == null) {
            return 0;
        }
        data.attempts++;
        return data.attempts;
    }

    /** Cancels the timeout task (if any) and removes the player from tracking. */
    public void removeAndCancel(UUID uuid) {
        PendingData data = pending.remove(uuid);
        if (data != null && data.timeoutTask != null) {
            try {
                data.timeoutTask.cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled/completed -- fine.
            }
        }
    }

    public int pendingCount() {
        return pending.size();
    }
}

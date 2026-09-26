package com.nexuscraft.nexusgate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escalating per-IP lockout applied after a player fails the password gate
 * (wrong password max-attempts times, or a timeout). In-memory only --
 * resets on server restart. This is a deterrent layer on top of the
 * password itself, not a substitute for it.
 */
public final class LockoutManager {

    private static final class Entry {
        int strikeLevel = 0;
        long lockedUntilMillis = 0L;
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private long[] durationsSeconds = new long[]{120, 300, 900, 1800, 3600};

    public void setDurationsSeconds(long[] durationsSeconds) {
        if (durationsSeconds != null && durationsSeconds.length > 0) {
            this.durationsSeconds = durationsSeconds;
        }
    }

    /** Returns remaining lockout seconds for this IP, or 0 if not locked. */
    public long remainingLockoutSeconds(String ip) {
        Entry e = entries.get(ip);
        if (e == null) {
            return 0L;
        }
        long remainingMillis = e.lockedUntilMillis - System.currentTimeMillis();
        return remainingMillis > 0 ? (remainingMillis / 1000L) + 1 : 0L;
    }

    public boolean isLocked(String ip) {
        return remainingLockoutSeconds(ip) > 0;
    }

    /** Call when an IP fails the gate (exhausts attempts, or times out). */
    public void registerFailure(String ip) {
        Entry e = entries.computeIfAbsent(ip, k -> new Entry());
        int index = Math.min(e.strikeLevel, durationsSeconds.length - 1);
        long durationSeconds = durationsSeconds[index];
        e.lockedUntilMillis = System.currentTimeMillis() + (durationSeconds * 1000L);
        e.strikeLevel++;
    }

    /** Call on a successful login -- forgives prior strikes for this IP. */
    public void clear(String ip) {
        entries.remove(ip);
    }

    public int activeLockoutCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Entry e : entries.values()) {
            if (e.lockedUntilMillis > now) {
                count++;
            }
        }
        return count;
    }
}

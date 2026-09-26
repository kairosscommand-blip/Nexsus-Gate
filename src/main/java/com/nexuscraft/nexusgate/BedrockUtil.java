package com.nexuscraft.nexusgate;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Best-effort Bedrock detection, now used to pick which of the two per-platform passwords
 * (v0.2.0) applies to a given connection, rather than to skip the gate outright the way v0.1.0
 * used it.
 *
 * Deliberately uses reflection instead of a compile-time Floodgate dependency: Floodgate is only
 * a soft-dependency (plugin.yml softdepend), so this class must still compile and run fine on a
 * server that doesn't have Floodgate installed at all.
 *
 * Takes a UUID rather than a Player so it also works from AsyncPlayerPreLoginEvent, which only
 * has a UUID/name at that point -- no Player object exists yet that early in the connection.
 *
 * If Floodgate isn't present/reachable, falls back to the well-known community heuristic that
 * Floodgate assigns UUID version 0 to Bedrock players who don't have a linked Java account. That
 * fallback is NOT as reliable as asking Floodgate directly (a real Java player could in
 * principle have a version-0 UUID, though it's exceedingly rare since Mojang UUIDs are version 4
 * or version 3), so it's config-toggleable via exempt-bedrock.
 */
public final class BedrockUtil {

    private static Boolean floodgatePresent = null;
    private static Object floodgateApiInstance = null;
    private static Method isFloodgatePlayerMethod = null;

    private BedrockUtil() {
    }

    /** Convenience wrapper used everywhere a password lookup needs to know which of the two
     * platform-specific passwords applies to this connection. */
    public static PasswordManager.PasswordKind kindOf(Player player, Logger logger) {
        return isBedrockPlayer(player.getUniqueId(), logger) ? PasswordManager.PasswordKind.BEDROCK : PasswordManager.PasswordKind.JAVA;
    }

    public static boolean isBedrockPlayer(Player player, Logger logger) {
        return isBedrockPlayer(player.getUniqueId(), logger);
    }

    public static boolean isBedrockPlayer(UUID uuid, Logger logger) {
        if (tryFloodgate(logger)) {
            try {
                Object result = isFloodgatePlayerMethod.invoke(floodgateApiInstance, uuid);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "[NexusGate] Floodgate lookup failed, falling back to UUID heuristic.", e);
            }
        }
        return isLikelyBedrockUuid(uuid);
    }

    private static boolean tryFloodgate(Logger logger) {
        if (floodgatePresent != null) {
            return floodgatePresent;
        }
        try {
            if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
                floodgatePresent = false;
                return false;
            }
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            floodgateApiInstance = getInstance.invoke(null);
            isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            floodgatePresent = true;
        } catch (Throwable t) {
            logger.info("[NexusGate] Floodgate not detected (or API shape differs); using UUID-version fallback for Bedrock detection.");
            floodgatePresent = false;
        }
        return floodgatePresent;
    }

    private static boolean isLikelyBedrockUuid(UUID uuid) {
        return uuid.version() == 0;
    }
}

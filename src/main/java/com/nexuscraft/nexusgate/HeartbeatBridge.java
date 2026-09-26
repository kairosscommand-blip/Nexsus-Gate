package com.nexuscraft.nexusgate;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Zero-dependency bridge to NexusHeartbeat, the server's shared "world mood" plugin.
 *
 * Reports a "moment" -- something that just happened worth the world's shared mood knowing
 * about -- if NexusHeartbeat is installed. Completely harmless no-op if it isn't (checked on
 * every single call, never cached/assumed). There is NO compile-time or plugin.yml dependency
 * on NexusHeartbeat at all: this works purely by dispatching NexusHeartbeat's own public
 * "/heartbeat report" command through the console, which is the supported, zero-coupling way
 * to integrate with it from any other plugin (deliberately avoids sharing a custom Bukkit
 * Event class across separately-built plugin jars, which runs into classloader-identity
 * problems unless there's a real Maven dependency between the two -- this sidesteps that
 * entirely).
 *
 * Copy this file as-is into your plugin's own source tree (adjust only the package line above
 * to match wherever you drop it), then call HeartbeatBridge.report(...) from the moments in
 * your own code that are worth the world's mood knowing about. Nothing else to wire up --
 * no pom.xml change, no plugin.yml softdepend needed (Bukkit console command dispatch works
 * regardless of plugin load order or whether NexusHeartbeat is present at all).
 */
public final class HeartbeatBridge {
    private HeartbeatBridge() {
    }

    /**
     * @param plugin   the calling plugin (used only to hop to the main thread if needed, and to
     *                 stay silent if something goes wrong -- never throws back into your code)
     * @param category one of NexusHeartbeat's known categories -- LIFE, DEATH, CONFLICT,
     *                 COMMERCE, DISCOVERY, MISCHIEF, NATURE, MYSTERY, SECURITY, COMMUNITY.
     *                 An unrecognized category still works fine; NexusHeartbeat just files it
     *                 under that raw name instead of one of its known ones.
     * @param weight   -10 (worst) to +10 (best) impact on the world's shared mood. NexusHeartbeat
     *                 clamps this on its end too, so an out-of-range value is harmless, but stay
     *                 in -10..10 for predictable results.
     * @param summary  a short, human-readable sentence. Shows up in /heartbeat history, and --
     *                 for moments big enough to shift the world's overall mood label -- in the
     *                 ambient broadcast every player sees. Keep it a single line, no newlines.
     */
    public static void report(JavaPlugin plugin, String category, int weight, String summary) {
        if (plugin == null || summary == null || summary.isBlank()) {
            return;
        }
        String safeCategory = (category == null || category.isBlank()) ? "MISC" : category;
        String safeSummary = summary.replace("\n", " ").replace("\r", " ");
        Runnable dispatch = () -> {
            try {
                if (!Bukkit.getPluginManager().isPluginEnabled("NexusHeartbeat")) {
                    return;
                }
                String cmd = "heartbeat report " + safeCategory + " " + weight + " " + safeSummary;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Throwable ignored) {
                // Never let a missing, disabled, or misbehaving NexusHeartbeat break the plugin
                // that called us -- this integration is a nice-to-have, not a dependency.
            }
        };
        if (Bukkit.isPrimaryThread()) {
            dispatch.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, dispatch);
        }
    }
}

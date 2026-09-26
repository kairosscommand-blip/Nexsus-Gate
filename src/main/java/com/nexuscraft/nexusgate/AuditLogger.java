package com.nexuscraft.nexusgate;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * Appends a plain-text audit trail of every gate outcome (success, wrong
 * password, timeout, lockout) to a file in the plugin's data folder, in
 * addition to the console. Never logs the password itself.
 */
public final class AuditLogger {

    private final JavaPlugin plugin;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private boolean enabled = true;
    private String fileName = "auth-log.txt";

    public AuditLogger(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void configure(boolean enabled, String fileName) {
        this.enabled = enabled;
        if (fileName != null && !fileName.isEmpty()) {
            this.fileName = fileName;
        }
    }

    public synchronized void log(String line) {
        String timestamped = "[" + LocalDateTime.now().format(formatter) + "] " + line;
        plugin.getLogger().info("[NexusGate] " + line);
        if (!enabled) {
            return;
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            File file = new File(plugin.getDataFolder(), fileName);
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(timestamped);
                writer.write(System.lineSeparator());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to write auth-log.txt", e);
        }
    }
}

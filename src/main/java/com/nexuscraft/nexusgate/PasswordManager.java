package com.nexuscraft.nexusgate;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Owns two independent shared passwords -- one for Java players, one for Bedrock/Xbox players.
 *
 * As of v0.2.2, both live in PLAIN TEXT directly in config.yml under {@code passwords.java} /
 * {@code passwords.bedrock}, by explicit request: open config.yml, see exactly what's
 * configured, edit it by hand, save, and know that whatever is sitting in that file is exactly
 * what a matching {@code /login} attempt gets checked against -- no hashing, no salt, no
 * separate password.yml, no possible gap between "what I think I set" and "what's actually
 * stored". That gap is what caused the two real bugs in v0.2.0/v0.2.1.
 *
 * This trades away the previous salted-hash-at-rest protection (anyone who can read config.yml
 * can now read the password) for that transparency. On a private server where this password is
 * a shared "front door" secret rather than a personal credential, and where anyone who can read
 * your plugins/ folder already has full control of the server anyway, that's the right trade
 * for what was asked.
 *
 * Every read comes straight off disk (never a cached copy), so hand-editing config.yml and
 * saving it takes effect on the very next join or /login attempt -- no /nexusgate reload, no
 * server restart required.
 *
 * v0.1.0/v0.2.0/v0.2.1 stored a salted SHA-256 hash instead, in a separate password.yml. That
 * file, if still present from before, is no longer read at all -- a hash can't be turned back
 * into the plaintext it came from, so upgrading can't recover an old password automatically.
 * See CHANGES.md and the README's upgrade note.
 */
public final class PasswordManager {

    public enum PasswordKind {
        JAVA("java"),
        BEDROCK("bedrock");

        private final String key;

        PasswordKind(String key) {
            this.key = key;
        }

        /** Bare platform name, e.g. "java" -- for display and command parsing. */
        public String sectionKey() {
            return key;
        }

        /** Dotted path into config.yml, e.g. "passwords.java". */
        public String configKey() {
            return "passwords." + key;
        }
    }

    private final JavaPlugin plugin;
    private final File configFile;

    public PasswordManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        ensureKeysExist();
        warnIfOnlyLegacyHashExists();
    }

    /**
     * Adds empty {@code passwords.java} / {@code passwords.bedrock} keys to an existing
     * config.yml if they're missing entirely (e.g. upgrading from a pre-v0.2.2 config), so
     * there's always an obvious, already-there place to type the password in -- without
     * touching anything else already in the file.
     */
    private void ensureKeysExist() {
        if (!configFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        boolean changed = false;
        for (PasswordKind kind : PasswordKind.values()) {
            if (!yaml.isSet(kind.configKey())) {
                yaml.set(kind.configKey(), "");
                changed = true;
            }
        }
        if (changed) {
            try {
                yaml.save(configFile);
                plugin.getLogger().info("[NexusGate] Added passwords.java / passwords.bedrock to config.yml -- "
                        + "open it and type each password in directly.");
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to add passwords section to config.yml", e);
            }
        }
    }

    /** One-time-per-startup warning if there's an old hashed password.yml sitting around and
     * the corresponding plaintext slot in config.yml is still blank -- it can't be migrated. */
    private void warnIfOnlyLegacyHashExists() {
        File legacy = new File(plugin.getDataFolder(), "password.yml");
        if (!legacy.exists()) {
            return;
        }
        YamlConfiguration legacyYaml = YamlConfiguration.loadConfiguration(legacy);
        boolean hadJava = legacyYaml.getBoolean("java.password-set", false) || legacyYaml.getBoolean("password-set", false);
        boolean hadBedrock = legacyYaml.getBoolean("bedrock.password-set", false);
        if (!hadJava && !hadBedrock) {
            return;
        }
        if ((hadJava && !isSet(PasswordKind.JAVA)) || (hadBedrock && !isSet(PasswordKind.BEDROCK))) {
            plugin.getLogger().warning("[NexusGate] Found an old password.yml with a hashed password from before "
                    + "v0.2.2. It is no longer read -- a hash can't be converted back into plaintext. "
                    + "Type the real password directly into config.yml under 'passwords:', or run "
                    + "/nexusgate setpassword java <password> / /nexusgate setpassword bedrock <password>.");
        }
    }

    public boolean isSet(PasswordKind kind) {
        String value = read(kind);
        return value != null && !value.isEmpty();
    }

    /** Always reads straight off disk -- see class doc for why. */
    private String read(PasswordKind kind) {
        if (!configFile.exists()) {
            return "";
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        return yaml.getString(kind.configKey(), "");
    }

    /** Admin-facing lookup so /nexusgate status can show exactly what's currently stored --
     * there is no confidentiality left to protect once it's plaintext in config.yml anyway. */
    public String currentValue(PasswordKind kind) {
        return read(kind);
    }

    /**
     * Sets (or changes) the password for one platform immediately, writing plaintext directly
     * into config.yml. Takes effect for every subsequent /login attempt from that platform right
     * away; never touches the other platform's password or anything else in config.yml.
     */
    public void setPassword(PasswordKind kind, String plainPassword) {
        YamlConfiguration yaml = configFile.exists() ? YamlConfiguration.loadConfiguration(configFile) : new YamlConfiguration();
        yaml.set(kind.configKey(), plainPassword);
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            yaml.save(configFile);
            plugin.reloadConfig();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save config.yml", e);
        }
    }

    /**
     * Plain string comparison against whatever is currently on disk for that platform. Returns
     * false if that platform's password is blank -- an unset platform can't be "guessed" open.
     */
    public boolean verify(PasswordKind kind, String attempt) {
        String stored = read(kind);
        return stored != null && !stored.isEmpty() && attempt != null && stored.equals(attempt);
    }
}

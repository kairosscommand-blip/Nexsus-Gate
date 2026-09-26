package com.nexuscraft.nexusgate;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Three related but separate things, all stored in access.yml:
 *
 * 1. A log of every gamer tag that has ever successfully typed the correct
 *    password (first seen, last seen, how many times, which platform, and
 *    the last several IPs they've connected from) -- the "who actually has
 *    access" list the admin asked for, and the raw material for spotting a
 *    banned player coming back on a new account from the same connection.
 *
 * 2. A NexusGate-specific ban list, by player. Deliberately independent of
 *    the server's normal /ban: it revokes ONE specific player's ability to
 *    use the shared password gate at all (checked before they can even
 *    finish connecting), without touching the shared password itself and
 *    without touching the server's own ban list. Useful for "this person
 *    knows the password but shouldn't anymore" without having to rotate the
 *    password for everyone else.
 *
 * 3. A NexusGate-specific ban list, by IP. A UUID ban stops that account;
 *    it does nothing to stop the same person connecting a minute later on a
 *    brand new Microsoft/Mojang account from the same computer. An IP ban is
 *    the escalation for that: a deliberate, admin-decided block on a whole
 *    connection, independent of which account is used. Stored as a list of
 *    maps rather than IP-keyed sections, because an IP address contains dots
 *    and Bukkit's config paths treat "." as a section separator -- using the
 *    IP as a path segment directly would silently split "1.2.3.4" into four
 *    nested sections instead of one key.
 *
 * Same "always read fresh off disk, never cache" approach as PasswordManager
 * -- this file is tiny and low-traffic (only touched on login/ban/unban), so
 * the disk round trip is cheap and it means hand-editing access.yml takes
 * effect immediately, same as config.yml already does.
 */
public final class AccessManager {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** How many distinct recent IPs to remember per known player. */
    private static final int MAX_TRACKED_IPS = 8;

    public static final class KnownPlayer {
        public UUID uuid;
        public String name;
        public long firstSeenMillis;
        public long lastSeenMillis;
        public int logins;
        public String kind;
        public List<String> ips = new ArrayList<>();
    }

    public static final class BanEntry {
        public UUID uuid;
        public String name;
        public String reason;
        public String bannedBy;
        public long bannedAtMillis;
    }

    public static final class IpBanEntry {
        public String ip;
        public String reason;
        public String bannedBy;
        public long bannedAtMillis;
    }

    private final JavaPlugin plugin;
    private final File file;

    public AccessManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "access.yml");
    }

    // ---------------------------------------------------------------
    // Known players -- every gamer tag that has ever typed the correct password
    // ---------------------------------------------------------------

    /**
     * Records a successful login, including which IP it came from (tracked as a
     * capped recent-history list, most recent last -- this is the raw material
     * {@link #findBannedMatchesForIp(String)} uses to flag ban evasion).
     *
     * @return true if this is the very first time this UUID has ever successfully
     *         logged in -- callers use this to alert watchers to a brand-new player.
     */
    public synchronized boolean recordSuccess(UUID uuid, String name, String kind, String ip) {
        YamlConfiguration yaml = load();
        String base = "known." + uuid;
        long now = System.currentTimeMillis();
        boolean firstTime = !yaml.isSet(base);
        long firstSeen = yaml.getLong(base + ".first-seen", now);
        int logins = yaml.getInt(base + ".logins", 0) + 1;

        yaml.set(base + ".name", name);
        yaml.set(base + ".first-seen", firstSeen);
        yaml.set(base + ".last-seen", now);
        yaml.set(base + ".logins", logins);
        yaml.set(base + ".kind", kind);

        if (ip != null && !ip.isBlank()) {
            List<String> ips = new ArrayList<>(yaml.getStringList(base + ".ips"));
            ips.remove(ip);
            ips.add(ip);
            while (ips.size() > MAX_TRACKED_IPS) {
                ips.remove(0);
            }
            yaml.set(base + ".ips", ips);
        }

        save(yaml);
        return firstTime;
    }

    public synchronized List<KnownPlayer> listKnownPlayers() {
        YamlConfiguration yaml = load();
        List<KnownPlayer> result = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("known");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            try {
                KnownPlayer kp = new KnownPlayer();
                kp.uuid = UUID.fromString(key);
                kp.name = section.getString(key + ".name", "?");
                kp.firstSeenMillis = section.getLong(key + ".first-seen", 0);
                kp.lastSeenMillis = section.getLong(key + ".last-seen", 0);
                kp.logins = section.getInt(key + ".logins", 0);
                kp.kind = section.getString(key + ".kind", "java");
                kp.ips = new ArrayList<>(section.getStringList(key + ".ips"));
                result.add(kp);
            } catch (IllegalArgumentException ignored) {
                // Not a valid UUID key -- skip a corrupt/hand-edited entry rather than fail.
            }
        }
        result.sort((a, b) -> Long.compare(b.lastSeenMillis, a.lastSeenMillis));
        return result;
    }

    /** Every recent IP on file for a given known player, oldest first, empty if unknown. */
    public synchronized List<String> getKnownIps(UUID uuid) {
        YamlConfiguration yaml = load();
        return new ArrayList<>(yaml.getStringList("known." + uuid + ".ips"));
    }

    // ---------------------------------------------------------------
    // Bans -- NexusGate-specific, independent of the server's own /ban
    // ---------------------------------------------------------------

    public synchronized void ban(UUID uuid, String name, String reason, String bannedBy) {
        YamlConfiguration yaml = load();
        String base = "bans." + uuid;
        yaml.set(base + ".name", name);
        yaml.set(base + ".reason", (reason == null || reason.isBlank()) ? "(no reason given)" : reason);
        yaml.set(base + ".banned-by", bannedBy);
        yaml.set(base + ".banned-at", System.currentTimeMillis());
        save(yaml);
    }

    /** Returns true if a ban actually existed and was removed. */
    public synchronized boolean unban(UUID uuid) {
        YamlConfiguration yaml = load();
        String base = "bans." + uuid;
        if (!yaml.isSet(base)) {
            return false;
        }
        yaml.set(base, null);
        save(yaml);
        return true;
    }

    public synchronized boolean isBanned(UUID uuid) {
        return getBan(uuid) != null;
    }

    public synchronized BanEntry getBan(UUID uuid) {
        YamlConfiguration yaml = load();
        String base = "bans." + uuid;
        if (!yaml.isSet(base)) {
            return null;
        }
        BanEntry b = new BanEntry();
        b.uuid = uuid;
        b.name = yaml.getString(base + ".name", "?");
        b.reason = yaml.getString(base + ".reason", "(no reason given)");
        b.bannedBy = yaml.getString(base + ".banned-by", "?");
        b.bannedAtMillis = yaml.getLong(base + ".banned-at", 0);
        return b;
    }

    public synchronized List<BanEntry> listBans() {
        YamlConfiguration yaml = load();
        List<BanEntry> result = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("bans");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                BanEntry b = getBan(uuid);
                if (b != null) {
                    result.add(b);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    // ---------------------------------------------------------------
    // IP bans -- deliberate, admin-initiated, independent of UUID bans.
    // Stored as a list of maps (see class javadoc) rather than IP-keyed
    // sections, since an IP contains dots and Bukkit paths split on ".".
    // ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rawIpBans(YamlConfiguration yaml) {
        return (List<Map<String, Object>>) (List<?>) yaml.getMapList("ip-bans");
    }

    /** Bans an IP outright. Overwrites any existing ban on the same IP with the new reason/admin. */
    public synchronized void banIp(String ip, String reason, String bannedBy) {
        YamlConfiguration yaml = load();
        List<Map<String, Object>> list = rawIpBans(yaml);
        list.removeIf(m -> ip.equals(String.valueOf(m.get("ip"))));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ip", ip);
        entry.put("reason", (reason == null || reason.isBlank()) ? "(no reason given)" : reason);
        entry.put("banned-by", bannedBy);
        entry.put("banned-at", System.currentTimeMillis());
        list.add(entry);

        yaml.set("ip-bans", list);
        save(yaml);
    }

    /** Returns true if an IP ban actually existed and was removed. */
    public synchronized boolean unbanIp(String ip) {
        YamlConfiguration yaml = load();
        List<Map<String, Object>> list = rawIpBans(yaml);
        boolean removed = list.removeIf(m -> ip.equals(String.valueOf(m.get("ip"))));
        if (removed) {
            yaml.set("ip-bans", list);
            save(yaml);
        }
        return removed;
    }

    public synchronized boolean isIpBanned(String ip) {
        return getIpBan(ip) != null;
    }

    public synchronized IpBanEntry getIpBan(String ip) {
        YamlConfiguration yaml = load();
        for (Map<String, Object> m : rawIpBans(yaml)) {
            if (ip.equals(String.valueOf(m.get("ip")))) {
                return toIpBanEntry(m);
            }
        }
        return null;
    }

    public synchronized List<IpBanEntry> listIpBans() {
        YamlConfiguration yaml = load();
        List<IpBanEntry> result = new ArrayList<>();
        for (Map<String, Object> m : rawIpBans(yaml)) {
            result.add(toIpBanEntry(m));
        }
        result.sort((a, b) -> Long.compare(b.bannedAtMillis, a.bannedAtMillis));
        return result;
    }

    private IpBanEntry toIpBanEntry(Map<String, Object> m) {
        IpBanEntry e = new IpBanEntry();
        e.ip = String.valueOf(m.get("ip"));
        Object reason = m.get("reason");
        e.reason = reason == null ? "(no reason given)" : String.valueOf(reason);
        Object by = m.get("banned-by");
        e.bannedBy = by == null ? "?" : String.valueOf(by);
        Object at = m.get("banned-at");
        e.bannedAtMillis = at instanceof Number ? ((Number) at).longValue() : 0L;
        return e;
    }

    // ---------------------------------------------------------------
    // Ban-evasion detection -- NEVER used to auto-reject a connection.
    // This only tells a caller "here's who else has connected from this IP
    // and whether any of them are banned" -- it's up to GateListener to turn
    // that into a staff alert, never into a kick, per the explicit admin
    // decision that shared/household IPs must not lock innocent players out.
    // ---------------------------------------------------------------

    /** Every currently-UUID-banned player whose known IP history includes the given IP. */
    public synchronized List<BanEntry> findBannedMatchesForIp(String ip) {
        List<BanEntry> result = new ArrayList<>();
        if (ip == null || ip.isBlank()) {
            return result;
        }
        List<BanEntry> bans = listBans();
        if (bans.isEmpty()) {
            return result;
        }
        for (BanEntry ban : bans) {
            if (getKnownIps(ban.uuid).contains(ip)) {
                result.add(ban);
            }
        }
        return result;
    }

    /** Every known player (banned or not) whose IP history includes the given IP, for /nexusgate altcheck. */
    public synchronized List<KnownPlayer> listKnownPlayersByIp(String ip) {
        List<KnownPlayer> result = new ArrayList<>();
        if (ip == null || ip.isBlank()) {
            return result;
        }
        for (KnownPlayer kp : listKnownPlayers()) {
            if (kp.ips.contains(ip)) {
                result.add(kp);
            }
        }
        return result;
    }

    public static String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "unknown";
        }
        return DISPLAY.format(Instant.ofEpochMilli(epochMillis));
    }

    // ---------------------------------------------------------------
    // Disk I/O
    // ---------------------------------------------------------------

    private YamlConfiguration load() {
        if (!file.exists()) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void save(YamlConfiguration yaml) {
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save access.yml", e);
        }
    }
}

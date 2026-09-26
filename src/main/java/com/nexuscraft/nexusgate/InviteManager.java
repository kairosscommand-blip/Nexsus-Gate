package com.nexuscraft.nexusgate;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
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
 * Temporary, expiring, limited-use "invite codes" -- a second, independent way in besides the
 * main shared password, stored in a new invites.yml (same always-read-fresh-off-disk,
 * synchronized-methods pattern as AccessManager/PasswordManager).
 *
 * The problem this solves: the main password is shared by everyone, so if it leaks, the only
 * clean fix used to be rotating it for the whole server -- disruptive, and it doesn't tell you
 * WHO leaked it. An invite code is a separate, disposable credential: hand a code to one person,
 * it expires on its own and/or burns out after a fixed number of uses, and every use is recorded
 * (who, when, from what IP). If a code leaks, revoke just that code (or ban just the specific
 * account that used it -- see AccessManager) -- the real password, and every other invite code,
 * are completely untouched. Nobody else even notices anything changed.
 *
 * Checked in LoginCommandExecutor as a fallback AFTER the platform password fails to match, so
 * from a player's perspective it's just "another thing /login <x> might accept" -- no separate
 * command, no separate flow, no separate freeze/timeout logic to duplicate.
 *
 * Codes are generated from a 31-character alphabet with the visually-ambiguous characters
 * (0/O, 1/I/L) removed, so they're easy to read and type correctly out loud or over Discord.
 */
public final class InviteManager {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    /** How many past redemptions to keep per code. Deliberately generous and set well above the
     * config default for invites.max-uses-cap (25): NexusGateCommandExecutor's group-removal
     * commands ("/nexusgate revokeinvite <code> kick|ban") rely on this history being COMPLETE --
     * if it silently trimmed older redemptions the way AccessManager's IP list does, a group purge
     * on a heavily-used code could quietly miss the first people who joined with it. If you ever
     * raise invites.max-uses-cap in config.yml above this number, raise this constant to match. */
    private static final int MAX_TRACKED_REDEMPTIONS = 500;

    private final SecureRandom random = new SecureRandom();

    public enum RedeemStatus {
        /** Matched a currently-valid code; the redemption was recorded. */
        SUCCESS,
        /** The typed string doesn't match any code that's ever existed -- indistinguishable, on
         * purpose, from a plain wrong password (never confirms/denies a code "almost" matched). */
        NOT_FOUND,
        EXPIRED,
        REVOKED,
        EXHAUSTED
    }

    public static final class RedeemResult {
        public final RedeemStatus status;
        /** The matched invite, or null only when status is NOT_FOUND. */
        public final InviteEntry entry;

        RedeemResult(RedeemStatus status, InviteEntry entry) {
            this.status = status;
            this.entry = entry;
        }
    }

    public static final class Redemption {
        public UUID uuid;
        public String name;
        public String ip;
        public long atMillis;
    }

    public static final class InviteEntry {
        public String code;
        public String note;
        public String createdBy;
        public long createdAtMillis;
        /** 0 means "never expires" -- not reachable from /nexusgate invite (always capped to a
         * real expiry), but left as a legitimate hand-edit option in invites.yml for an admin who
         * explicitly wants one, since this file is meant to be as inspectable/editable as the
         * rest of NexusGate's config. */
        public long expiresAtMillis;
        /** 0 means unlimited uses -- same hand-edit-only caveat as expiresAtMillis. */
        public int maxUses;
        public int usesCount;
        public boolean revoked;
        public List<Redemption> redemptions = new ArrayList<>();
        /** Non-null only for a code issued via ensureTeamCode()/rotateTeamCode() -- the id of the
         * NexusRealms team it belongs to, so /nexusgate teamremove and friends can find it again
         * without an admin needing to know the raw code string. Null for an ordinary ad hoc
         * invite created with /nexusgate invite. */
        public String teamId;

        public boolean isExpired(long now) {
            return expiresAtMillis > 0 && now >= expiresAtMillis;
        }

        public boolean isExhausted() {
            return maxUses > 0 && usesCount >= maxUses;
        }

        public boolean isActive(long now) {
            return !revoked && !isExpired(now) && !isExhausted();
        }
    }

    private final JavaPlugin plugin;
    private final File file;

    public InviteManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "invites.yml");
    }

    // ---------------------------------------------------------------
    // Create / revoke
    // ---------------------------------------------------------------

    /**
     * @param maxUses     how many total successful redemptions before the code stops working;
     *                    callers should pass at least 1 (0/negative is treated as unlimited by
     *                    isExhausted(), but /nexusgate invite never passes that)
     * @param minutesValid how many minutes from now the code is valid for; callers should pass
     *                     at least 1 (0/negative is treated as "never expires")
     */
    public synchronized InviteEntry create(String createdBy, int maxUses, int minutesValid, String note) {
        return createInternal(createdBy, maxUses, minutesValid, note, null);
    }

    /** Shared by create() and the team-code methods below. Callers must already hold this
     * instance's monitor (i.e. only ever called from inside a synchronized method) since it
     * isn't synchronized itself. */
    private InviteEntry createInternal(String createdBy, int maxUses, int minutesValid, String note, String teamId) {
        YamlConfiguration yaml = load();
        String code = generateUniqueCode(yaml);
        String base = "invites." + code;
        long now = System.currentTimeMillis();
        long expires = minutesValid > 0 ? now + (minutesValid * 60_000L) : 0L;

        yaml.set(base + ".created-by", createdBy);
        yaml.set(base + ".created-at", now);
        yaml.set(base + ".expires-at", expires);
        yaml.set(base + ".max-uses", maxUses);
        yaml.set(base + ".uses-count", 0);
        yaml.set(base + ".revoked", false);
        yaml.set(base + ".note", note == null ? "" : note);
        if (teamId != null) {
            yaml.set(base + ".team-id", teamId);
            yaml.set("team-links." + teamId, code);
        }
        save(yaml);

        InviteEntry entry = new InviteEntry();
        entry.code = code;
        entry.note = note == null ? "" : note;
        entry.createdBy = createdBy;
        entry.createdAtMillis = now;
        entry.expiresAtMillis = expires;
        entry.maxUses = maxUses;
        entry.usesCount = 0;
        entry.revoked = false;
        entry.teamId = teamId;
        return entry;
    }

    /** Returns true only if a not-already-revoked code actually existed and was just revoked. */
    public synchronized boolean revoke(String code) {
        YamlConfiguration yaml = load();
        String normalized = normalize(code);
        String base = "invites." + normalized;
        if (!yaml.isSet(base) || yaml.getBoolean(base + ".revoked", false)) {
            return false;
        }
        yaml.set(base + ".revoked", true);
        save(yaml);
        return true;
    }

    // ---------------------------------------------------------------
    // Team-linked codes -- a persistent, per-NexusRealms-team secondary password, issued/rotated
    // by NexusGate's own "/nexusgate teamcode|teamrotate|teamremove" commands (normally invoked
    // via console by NexusRealms' own GateBridge the moment a team forms/disbands -- see that
    // class's javadoc). Unlike an ad hoc guest invite, a team code is deliberately unlimited-use
    // and non-expiring (any current or future team member can use it, indefinitely) -- it's only
    // ever replaced or removed by an explicit admin/team-lifecycle action, never by aging out.
    // ---------------------------------------------------------------

    /** The code currently linked to a team, or null if none has ever been issued (or the link
     * was cleared) -- regardless of whether that code is still active; callers decide what an
     * expired/revoked/exhausted linked code means for them. */
    public synchronized InviteEntry getForTeam(String teamId) {
        YamlConfiguration yaml = load();
        String code = yaml.getString("team-links." + teamId, null);
        if (code == null || !yaml.isSet("invites." + code)) {
            return null;
        }
        return readEntry(yaml, code);
    }

    /** Idempotent: if this team already has a linked code (active or not), returns it UNCHANGED
     * rather than minting a second one -- safe to call more than once for the same team (e.g. a
     * replayed team-creation event). Use rotateTeamCode() to deliberately replace an existing one. */
    public synchronized InviteEntry ensureTeamCode(String teamId, String teamName, String createdBy) {
        InviteEntry existing = getForTeam(teamId);
        // A revoked linked code (e.g. an admin ran /nexusgate revokeinvite directly on it, or a
        // prior /nexusgate teamremove) is treated the same as "no code yet" -- team codes never
        // expire/exhaust on their own (0/0), so revoked is the only way an existing link can be
        // stale, and reusing a dead code here would leave the team permanently without one.
        if (existing != null && !existing.revoked) {
            return existing;
        }
        return createInternal(createdBy, 0, 0, "Team: " + teamName, teamId);
    }

    /** Always replaces: revokes whatever code is currently linked to this team (if any, so it
     * stops working immediately) and issues a brand new one in its place. This is "change their
     * password" for a team, in one call. */
    public synchronized InviteEntry rotateTeamCode(String teamId, String teamName, String createdBy) {
        InviteEntry existing = getForTeam(teamId);
        if (existing != null) {
            revoke(existing.code);
        }
        return createInternal(createdBy, 0, 0, "Team: " + teamName, teamId);
    }

    /** Clears the team-links index entry only -- does NOT revoke the code itself, so a caller
     * that also wants the code dead (e.g. on team disband) must revoke() it separately. A future
     * ensureTeamCode() for this teamId starts fresh afterward. */
    public synchronized void unlinkTeam(String teamId) {
        YamlConfiguration yaml = load();
        if (yaml.isSet("team-links." + teamId)) {
            yaml.set("team-links." + teamId, null);
            save(yaml);
        }
    }

    // ---------------------------------------------------------------
    // Pending announcements -- for a team member who wasn't online when their team's code was
    // issued or rotated. Delivered the next time that UUID joins (see GateListener.onJoin).
    // ---------------------------------------------------------------

    /** Queues telling this player about this code next time they join. Safe to call repeatedly --
     * de-duplicates so the same code is never queued twice for the same player. */
    public synchronized void queuePendingAnnouncement(UUID uuid, String code) {
        YamlConfiguration yaml = load();
        String path = "pending." + uuid;
        List<String> codes = new ArrayList<>(yaml.getStringList(path));
        if (!codes.contains(code)) {
            codes.add(code);
            yaml.set(path, codes);
            save(yaml);
        }
    }

    /** Reads and clears every code queued for this player, returning only the ones still worth
     * telling them about (silently drops one that's since been revoked -- nothing to announce
     * about a dead code). Called once per join. */
    public synchronized List<InviteEntry> takePendingAnnouncements(UUID uuid) {
        YamlConfiguration yaml = load();
        String path = "pending." + uuid;
        List<String> codes = yaml.getStringList(path);
        if (codes.isEmpty()) {
            return new ArrayList<>();
        }
        yaml.set(path, null);
        save(yaml);

        List<InviteEntry> result = new ArrayList<>();
        for (String code : codes) {
            String normalized = normalize(code);
            if (yaml.isSet("invites." + normalized)) {
                InviteEntry entry = readEntry(yaml, normalized);
                if (!entry.revoked) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------
    // Redeem -- the /login integration point
    // ---------------------------------------------------------------

    /**
     * Attempts to redeem {@code attempt} as an invite code. Validity check and use-count
     * increment happen under the same lock/disk round trip, so two people racing to use the
     * last remaining use of a code can't both get SUCCESS.
     */
    public synchronized RedeemResult tryRedeem(String attempt, UUID uuid, String name, String ip) {
        if (attempt == null || attempt.isBlank()) {
            return new RedeemResult(RedeemStatus.NOT_FOUND, null);
        }
        YamlConfiguration yaml = load();
        String code = normalize(attempt);
        String base = "invites." + code;
        if (!yaml.isSet(base)) {
            return new RedeemResult(RedeemStatus.NOT_FOUND, null);
        }

        InviteEntry entry = readEntry(yaml, code);
        long now = System.currentTimeMillis();

        if (entry.revoked) {
            return new RedeemResult(RedeemStatus.REVOKED, entry);
        }
        if (entry.isExpired(now)) {
            return new RedeemResult(RedeemStatus.EXPIRED, entry);
        }
        if (entry.isExhausted()) {
            return new RedeemResult(RedeemStatus.EXHAUSTED, entry);
        }

        int newCount = entry.usesCount + 1;
        yaml.set(base + ".uses-count", newCount);

        List<Map<String, Object>> redemptions = rawRedemptions(yaml, base);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("uuid", uuid == null ? "unknown" : uuid.toString());
        record.put("name", name == null ? "unknown" : name);
        record.put("ip", ip == null ? "unknown" : ip);
        record.put("at", now);
        redemptions.add(record);
        while (redemptions.size() > MAX_TRACKED_REDEMPTIONS) {
            redemptions.remove(0);
        }
        yaml.set(base + ".redemptions", redemptions);
        save(yaml);

        entry.usesCount = newCount;
        Redemption r = new Redemption();
        r.uuid = uuid;
        r.name = name;
        r.ip = ip;
        r.atMillis = now;
        entry.redemptions.add(r);
        return new RedeemResult(RedeemStatus.SUCCESS, entry);
    }

    // ---------------------------------------------------------------
    // Lookup / listing
    // ---------------------------------------------------------------

    public synchronized InviteEntry get(String code) {
        YamlConfiguration yaml = load();
        String normalized = normalize(code);
        if (!yaml.isSet("invites." + normalized)) {
            return null;
        }
        return readEntry(yaml, normalized);
    }

    public synchronized List<InviteEntry> listAll() {
        YamlConfiguration yaml = load();
        List<InviteEntry> result = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("invites");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(false)) {
            result.add(readEntry(yaml, key));
        }
        result.sort((a, b) -> Long.compare(b.createdAtMillis, a.createdAtMillis));
        return result;
    }

    /** Splits "3F7K-Q9RT" (or any spacing/case) into groups of 4 for display, e.g. as printed
     * right after /nexusgate invite, or echoed back in /nexusgate invites and inviteinfo. */
    public static String formatForDisplay(String code) {
        String c = code == null ? "" : code.trim().toUpperCase().replace("-", "");
        if (c.length() <= 4) {
            return c;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length(); i += 4) {
            if (sb.length() > 0) {
                sb.append('-');
            }
            sb.append(c, i, Math.min(i + 4, c.length()));
        }
        return sb.toString();
    }

    public static String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "never";
        }
        return DISPLAY.format(Instant.ofEpochMilli(epochMillis));
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rawRedemptions(YamlConfiguration yaml, String base) {
        return (List<Map<String, Object>>) (List<?>) yaml.getMapList(base + ".redemptions");
    }

    private InviteEntry readEntry(YamlConfiguration yaml, String code) {
        String base = "invites." + code;
        InviteEntry e = new InviteEntry();
        e.code = code;
        e.note = yaml.getString(base + ".note", "");
        e.createdBy = yaml.getString(base + ".created-by", "?");
        e.createdAtMillis = yaml.getLong(base + ".created-at", 0);
        e.expiresAtMillis = yaml.getLong(base + ".expires-at", 0);
        e.maxUses = yaml.getInt(base + ".max-uses", 1);
        e.usesCount = yaml.getInt(base + ".uses-count", 0);
        e.revoked = yaml.getBoolean(base + ".revoked", false);
        e.teamId = yaml.getString(base + ".team-id", null);
        for (Map<String, Object> m : rawRedemptions(yaml, base)) {
            Redemption r = new Redemption();
            Object u = m.get("uuid");
            try {
                r.uuid = u == null ? null : UUID.fromString(String.valueOf(u));
            } catch (IllegalArgumentException ignored) {
                r.uuid = null;
            }
            r.name = String.valueOf(m.get("name"));
            r.ip = String.valueOf(m.get("ip"));
            Object at = m.get("at");
            r.atMillis = at instanceof Number ? ((Number) at).longValue() : 0L;
            e.redemptions.add(r);
        }
        return e;
    }

    private String generateUniqueCode(YamlConfiguration yaml) {
        String code;
        int guard = 0;
        do {
            code = randomCode();
            guard++;
        } while (yaml.isSet("invites." + code) && guard < 50);
        return code;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Codes are matched case-insensitively with hyphens/spaces stripped -- "3F7K-Q9RT",
     * "3f7kq9rt", and "3F7K Q9RT" all hit the same entry. */
    private String normalize(String raw) {
        return raw.trim().toUpperCase().replace("-", "").replace(" ", "");
    }

    // ---------------------------------------------------------------
    // Disk I/O -- same "always fresh off disk, never cache" approach as AccessManager
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
            plugin.getLogger().log(Level.SEVERE, "Failed to save invites.yml", e);
        }
    }
}

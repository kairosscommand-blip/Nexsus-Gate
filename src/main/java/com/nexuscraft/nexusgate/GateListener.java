package com.nexuscraft.nexusgate;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The core of NexusGate: freezes every not-yet-authenticated Java player on
 * join and blocks movement/interaction/commands/chat/damage until they type
 * /login <password> correctly, or kicks them on timeout / too many wrong
 * attempts. Bedrock players and configured exemptions skip all of this.
 */
public final class GateListener implements Listener {

    private final JavaPlugin plugin;
    private final PasswordManager passwordManager;
    private final PendingAuthManager pendingAuthManager;
    private final LockoutManager lockoutManager;
    private final AuditLogger auditLogger;
    private final AccessManager accessManager;
    private final InviteManager inviteManager;

    private int timeoutSeconds = 60;
    private int maxAttempts = 3;
    private boolean exemptOperators = false;
    private boolean exemptBedrock = true;
    private Set<String> trustedUuids = new HashSet<>();
    private Map<String, String> messages = new HashMap<>();

    // Defensive in-code defaults (safe/alert-only) -- what actually ships in config.yml
    // is set to "autoban" as of v0.5.0, by explicit admin request. See loadSettingsFromConfig().
    private String altDetectionMode = "watch";
    private boolean altDetectionBanIpToo = true;

    // Invite-code (temporary password) settings -- see loadSettingsFromConfig() and InviteManager.
    private boolean invitesEnabled = true;
    private int inviteDefaultUses = 1;
    private int inviteDefaultMinutes = 60;
    private int inviteMaxMinutes = 10080;
    private int inviteMaxUsesCap = 25;

    public GateListener(JavaPlugin plugin, PasswordManager passwordManager, PendingAuthManager pendingAuthManager,
                         LockoutManager lockoutManager, AuditLogger auditLogger, AccessManager accessManager,
                         InviteManager inviteManager) {
        this.plugin = plugin;
        this.passwordManager = passwordManager;
        this.pendingAuthManager = pendingAuthManager;
        this.lockoutManager = lockoutManager;
        this.auditLogger = auditLogger;
        this.accessManager = accessManager;
        this.inviteManager = inviteManager;
    }

    // ---------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------

    public void loadSettingsFromConfig() {
        plugin.reloadConfig();
        ConfigurationSection settings = plugin.getConfig().getConfigurationSection("settings");
        if (settings != null) {
            timeoutSeconds = settings.getInt("timeout-seconds", 60);
            maxAttempts = settings.getInt("max-attempts", 3);
            exemptOperators = settings.getBoolean("exempt-operators", false);
            exemptBedrock = settings.getBoolean("exempt-bedrock", true);
            List<String> uuids = settings.getStringList("trusted-uuids");
            trustedUuids = new HashSet<>(uuids);
        }

        List<Long> durations = new ArrayList<>();
        for (Object o : plugin.getConfig().getList("lockout.durations-seconds", new ArrayList<>())) {
            if (o instanceof Number) {
                durations.add(((Number) o).longValue());
            }
        }
        if (!durations.isEmpty()) {
            long[] arr = new long[durations.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = durations.get(i);
            }
            lockoutManager.setDurationsSeconds(arr);
        }

        messages = new HashMap<>();
        ConfigurationSection msgSection = plugin.getConfig().getConfigurationSection("messages");
        if (msgSection != null) {
            for (String key : msgSection.getKeys(false)) {
                messages.put(key, msgSection.getString(key, ""));
            }
        }

        boolean loggingEnabled = plugin.getConfig().getBoolean("logging.enabled", true);
        String logFile = plugin.getConfig().getString("logging.file", "auth-log.txt");
        auditLogger.configure(loggingEnabled, logFile);

        ConfigurationSection altDetection = plugin.getConfig().getConfigurationSection("alt-detection");
        if (altDetection != null) {
            String mode = altDetection.getString("mode", "watch");
            altDetectionMode = mode == null ? "watch" : mode.toLowerCase();
            altDetectionBanIpToo = altDetection.getBoolean("ban-ip-too", true);
        }

        ConfigurationSection invites = plugin.getConfig().getConfigurationSection("invites");
        if (invites != null) {
            invitesEnabled = invites.getBoolean("enabled", true);
            inviteDefaultUses = Math.max(1, invites.getInt("default-uses", 1));
            inviteDefaultMinutes = Math.max(1, invites.getInt("default-minutes", 60));
            inviteMaxMinutes = Math.max(1, invites.getInt("max-minutes", 10080));
            inviteMaxUsesCap = Math.max(1, invites.getInt("max-uses-cap", 25));
        }
    }

    public String getAltDetectionMode() {
        return altDetectionMode;
    }

    public boolean isAltDetectionBanIpToo() {
        return altDetectionBanIpToo;
    }

    public boolean isInvitesEnabled() {
        return invitesEnabled;
    }

    public int getInviteDefaultUses() {
        return inviteDefaultUses;
    }

    public int getInviteDefaultMinutes() {
        return inviteDefaultMinutes;
    }

    public int getInviteMaxMinutes() {
        return inviteMaxMinutes;
    }

    public int getInviteMaxUsesCap() {
        return inviteMaxUsesCap;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getMessage(String key) {
        String raw = messages.get(key);
        return raw != null ? raw : "";
    }

    public String format(String key, Map<String, String> placeholders) {
        String raw = getMessage(key);
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return colorize(raw);
    }

    public static String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    // ---------------------------------------------------------------
    // Exemptions
    // ---------------------------------------------------------------

    public boolean isExempt(Player player) {
        if (player.hasPermission("nexusgate.bypass")) {
            return true;
        }
        if (exemptOperators && player.isOp()) {
            return true;
        }
        if (trustedUuids.contains(player.getUniqueId().toString())) {
            return true;
        }
        boolean isBedrock = BedrockUtil.isBedrockPlayer(player, plugin.getLogger());
        if (exemptBedrock && isBedrock) {
            // Admin has explicitly chosen to keep Bedrock ungated even though a Bedrock
            // password can now be configured (v0.2.0) -- same opt-out as v0.1.0 had.
            return true;
        }
        // If this player's platform doesn't have a password configured yet, exempting them
        // is the only sane option -- gating with no password would just lock everyone out.
        PasswordManager.PasswordKind kind = isBedrock ? PasswordManager.PasswordKind.BEDROCK : PasswordManager.PasswordKind.JAVA;
        return !passwordManager.isSet(kind);
    }

    public static String ipOf(Player player) {
        try {
            InetAddress addr = player.getAddress().getAddress();
            return addr != null ? addr.getHostAddress() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ---------------------------------------------------------------
    // Pre-login lockout check (earliest possible point to reject)
    // ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        InetAddress preLoginAddr = event.getAddress();
        String ip = preLoginAddr != null ? preLoginAddr.getHostAddress() : null;

        AccessManager.BanEntry ban = accessManager.getBan(uuid);
        if (ban != null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("reason", ban.reason);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, format("banned-kick", ph));
            auditLogger.log("BAN-REJECT  player=" + name + " uuid=" + uuid + " reason=" + ban.reason);
            return;
        }

        // Deliberate, admin-initiated IP ban (/nexusgate banip) -- unlike the ban-evasion
        // alert below, this DOES auto-reject: an IP ban is a direct decision by a human,
        // not a heuristic, so there's no false-positive risk to weigh against blocking it.
        if (ip != null) {
            AccessManager.IpBanEntry ipBan = accessManager.getIpBan(ip);
            if (ipBan != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("reason", ipBan.reason);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, format("ip-banned-kick", ph));
                auditLogger.log("IPBAN-REJECT  player=" + name + " uuid=" + uuid + " ip=" + ip + " reason=" + ipBan.reason);
                return;
            }
        }

        // Ban-evasion detection. Two modes, set by settings.alt-detection.mode in config.yml:
        //   "watch"   -- never rejects. Alerts nexusgate.watch + logs ALT-ALERT. Safe default:
        //                a heuristic IP match can just as easily be two people sharing a
        //                house/router as one person evading a ban.
        //   "autoban" -- rejects immediately AND auto-bans the new account (and, if
        //                alt-detection.ban-ip-too is true, the IP itself too), no human
        //                needed. This is what config.yml ships with as of v0.5.0, by explicit
        //                admin request to run this as aggressively as possible -- but it WILL
        //                catch genuinely innocent shared connections (same house, same dorm,
        //                same mobile carrier/CGNAT) exactly as readily as it catches a real
        //                alt account. Every auto-ban is still logged and alerted live so it can
        //                be reversed in one command (/nexusgate unban, /nexusgate unbanip) the
        //                moment someone notices it was a false positive.
        if (ip != null) {
            List<AccessManager.BanEntry> matches = accessManager.findBannedMatchesForIp(ip);
            if (!matches.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (AccessManager.BanEntry m : matches) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(m.name);
                }

                if ("autoban".equals(altDetectionMode)) {
                    String reason = "Auto-ban: shared connection with banned player(s) " + names;
                    accessManager.ban(uuid, name, reason, "NexusGate (auto)");
                    if (altDetectionBanIpToo) {
                        accessManager.banIp(ip, "Auto-ban: shared with banned player(s) " + names, "NexusGate (auto)");
                    }
                    Map<String, String> ph = new HashMap<>();
                    ph.put("reason", reason);
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, format("banned-kick", ph));
                    auditLogger.log("AUTOBAN  player=" + name + " uuid=" + uuid + " ip=" + ip + " matched=" + names
                            + (altDetectionBanIpToo ? " ip-banned=true" : " ip-banned=false"));
                    notifyWatchers("&4[NexusGate] &cAuto-banned &f" + name + " &7-- shared connection with banned player(s): &c"
                            + names + "&7. Wrong call? &f/nexusgate unban " + name
                            + (altDetectionBanIpToo ? " &7and &f/nexusgate unbanip " + ip : ""));
                    // NexusHeartbeat integration: a repelled ban-evasion attempt is a small win
                    // for server security, worth the world's shared mood knowing about.
                    HeartbeatBridge.report(plugin, "SECURITY", 2, "A repeat troublemaker's alt account was automatically banned.");
                    return;
                }

                auditLogger.log("ALT-ALERT  player=" + name + " uuid=" + uuid + " ip=" + ip + " matches=" + names);
                notifyWatchers("&c[NexusGate] &f" + name + " &7connected from an IP also used by banned player(s): &c"
                        + names + " &7-- NOT blocked automatically. Review with &f/nexusgate altcheck " + name);
            }
        }

        boolean anyPasswordConfigured = passwordManager.isSet(PasswordManager.PasswordKind.JAVA)
                || passwordManager.isSet(PasswordManager.PasswordKind.BEDROCK);
        if (!anyPasswordConfigured) {
            return;
        }
        if (ip == null) {
            return;
        }
        long remaining = lockoutManager.remainingLockoutSeconds(ip);
        if (remaining > 0) {
            Map<String, String> ph = new HashMap<>();
            ph.put("remaining", formatDuration(remaining));
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, format("locked-out-kick", ph));
            // Logged so a "my friend can't connect" report can be told apart from a real bug --
            // if this fires for someone who never typed a wrong password themselves, it means
            // another player on the same IP (e.g. same house/router) triggered the lockout.
            auditLogger.log("LOCKOUT-REJECT  player=" + event.getName() + " uuid=" + uuid + " ip=" + ip
                    + " remaining=" + formatDuration(remaining));
        }
    }

    // ---------------------------------------------------------------
    // Join -> begin the gate
    // ---------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Deliver any team-code announcements queued for this player (see InviteManager's
        // queuePendingAnnouncement/takePendingAnnouncements) -- runs unconditionally, before any
        // exemption/gate logic below, since knowing your own team's password isn't a
        // security-sensitive reveal and shouldn't wait on whether this player even goes through
        // the gate at all (an exempt/bypass player still deserves to be told).
        for (InviteManager.InviteEntry entry : inviteManager.takePendingAnnouncements(player.getUniqueId())) {
            Map<String, String> ph = new HashMap<>();
            ph.put("code", InviteManager.formatForDisplay(entry.code));
            player.sendMessage(format("team-code-announce", ph));
        }

        PasswordManager.PasswordKind kind = BedrockUtil.kindOf(player, plugin.getLogger());

        if (!passwordManager.isSet(kind)) {
            plugin.getLogger().warning(getMessage("no-password-configured-warning").replace("{kind}", kind.sectionKey()));
            return;
        }

        if (isExempt(player)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        String ip = ipOf(player);

        Map<String, String> ph = new HashMap<>();
        ph.put("timeout", String.valueOf(timeoutSeconds));
        player.sendMessage(format("gate-chat", ph));
        try {
            player.sendTitle(colorize(getMessage("gate-title")), colorize(getMessage("gate-subtitle")), 10, 100, 20);
        } catch (Throwable ignored) {
            // Cosmetic only -- never let a title API mismatch break the gate itself.
        }
        try {
            // Freezing purely by cancelling PlayerMoveEvent still lets gravity pull the
            // player down every tick; cancelling that repeatedly for up to timeoutSeconds
            // (60s by default) lets the client's own position drift further and further out
            // of sync with the server's frozen one. The moment the freeze lifts, the client
            // sends a single packet trying to "catch up" all at once, which vanilla's own
            // anti-speedhack check reads as an impossible jump and disconnects the player
            // right after a correct password -- this was the actual cause of players getting
            // kicked shortly after typing /login. Turning gravity off while pending removes
            // the thing that was driving that drift in the first place.
            player.setGravity(false);
        } catch (Throwable ignored) {
            // Best-effort -- never let this break the gate itself.
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingAuthManager.isPending(uuid)) {
                pendingAuthManager.removeAndCancel(uuid);
                lockoutManager.registerFailure(ip);
                auditLogger.log("TIMEOUT  player=" + player.getName() + " uuid=" + uuid + " ip=" + ip + " kind=" + kind);
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    online.kickPlayer(colorize(getMessage("timeout-kick")));
                }
            }
        }, timeoutSeconds * 20L);

        pendingAuthManager.addPending(uuid, task);
        auditLogger.log("PENDING  player=" + player.getName() + " uuid=" + uuid + " ip=" + ip + " kind=" + kind);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingAuthManager.removeAndCancel(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        pendingAuthManager.removeAndCancel(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------
    // Freeze: movement, damage, interaction, commands, chat
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!pendingAuthManager.isPending(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() == null || event.getFrom() == null) {
            return;
        }
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            // Belt-and-suspenders: cancelling alone is supposed to revert the player to
            // getFrom() on its own, but explicitly setting it too costs nothing and avoids
            // relying on that being true across every server fork/version.
            event.setTo(event.getFrom());
            event.setCancelled(true);
        }
    }

    /**
     * Called right after a successful /login. Restores gravity and forces a hard
     * position resync (see the comment in onJoin for why the resync matters) before
     * the player is handed back normal movement.
     */
    public void completeAuth(Player player) {
        try {
            player.setGravity(true);
        } catch (Throwable ignored) {
        }
        try {
            player.teleport(player.getLocation());
        } catch (Throwable ignored) {
            // Best-effort -- never let this stop the player from actually being let in.
        }
    }

    /**
     * Broadcasts a (color-coded) message to everyone holding nexusgate.watch, and logs a
     * plain-text copy to the server console. Safe to call from off the main thread (e.g. from
     * {@link #onPreLogin}, which runs async) -- hops onto the main thread itself since sending
     * messages and iterating online players are both main-thread-only Bukkit calls.
     */
    public void notifyWatchers(String rawMessage) {
        String colored = colorize(rawMessage);
        Runnable broadcast = () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("nexusgate.watch")) {
                    p.sendMessage(colored);
                }
            }
            plugin.getLogger().info("[NexusGate] " + ChatColor.stripColor(colored));
        };
        if (Bukkit.isPrimaryThread()) {
            broadcast.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, broadcast);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (pendingAuthManager.isPending(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player) {
            Player p = (Player) event.getDamager();
            if (pendingAuthManager.isPending(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (pendingAuthManager.isPending(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (pendingAuthManager.isPending(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (pendingAuthManager.isPending(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            Player p = (Player) event.getEntity();
            if (pendingAuthManager.isPending(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            Player p = (Player) event.getWhoClicked();
            if (pendingAuthManager.isPending(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player) {
            Player p = (Player) event.getPlayer();
            if (pendingAuthManager.isPending(p.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!pendingAuthManager.isPending(player.getUniqueId())) {
            return;
        }
        String message = event.getMessage().toLowerCase();
        if (message.startsWith("/login")) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(format("must-authenticate-first", null));
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (pendingAuthManager.isPending(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(format("must-authenticate-first", null));
        }
    }

    // ---------------------------------------------------------------
    // Helpers shared with the command executors
    // ---------------------------------------------------------------

    public static String formatDuration(long totalSeconds) {
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }
}

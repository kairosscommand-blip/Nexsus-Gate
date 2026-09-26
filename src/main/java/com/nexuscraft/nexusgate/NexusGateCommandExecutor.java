package com.nexuscraft.nexusgate;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class NexusGateCommandExecutor implements CommandExecutor {

    private final JavaPlugin plugin;
    private final PasswordManager passwordManager;
    private final PendingAuthManager pendingAuthManager;
    private final LockoutManager lockoutManager;
    private final GateListener gateListener;
    private final AccessManager accessManager;
    private final InviteManager inviteManager;

    public NexusGateCommandExecutor(JavaPlugin plugin, PasswordManager passwordManager, PendingAuthManager pendingAuthManager,
                                     LockoutManager lockoutManager, GateListener gateListener, AccessManager accessManager,
                                     InviteManager inviteManager) {
        this.plugin = plugin;
        this.passwordManager = passwordManager;
        this.pendingAuthManager = pendingAuthManager;
        this.lockoutManager = lockoutManager;
        this.gateListener = gateListener;
        this.accessManager = accessManager;
        this.inviteManager = inviteManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "setpassword": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate setpassword [java|bedrock] <password>"));
                    return true;
                }

                // /nexusgate setpassword java|bedrock <password...> -- the (v0.2.0) form.
                PasswordManager.PasswordKind kind;
                int passwordStart;
                String maybeKind = args[1].toLowerCase();
                if (maybeKind.equals("java") || maybeKind.equals("bedrock")) {
                    if (args.length < 3) {
                        sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate setpassword " + maybeKind + " <password>"));
                        return true;
                    }
                    kind = maybeKind.equals("bedrock") ? PasswordManager.PasswordKind.BEDROCK : PasswordManager.PasswordKind.JAVA;
                    passwordStart = 2;
                } else if (!passwordManager.isSet(PasswordManager.PasswordKind.JAVA)
                        && !passwordManager.isSet(PasswordManager.PasswordKind.BEDROCK)) {
                    // Bare old-style form (/nexusgate setpassword <password>), with NEITHER
                    // password configured yet -- a genuinely fresh install, so it's unambiguous
                    // to assume this is the first-time Java setup, same as v0.1.0 always did.
                    kind = PasswordManager.PasswordKind.JAVA;
                    passwordStart = 1;
                } else {
                    // At least one password already exists (an upgraded server, most likely),
                    // so the bare form is now REFUSED rather than silently guessing which
                    // platform you meant -- that guess used to default to Java no matter what,
                    // which is exactly the kind of silent surprise that can overwrite a
                    // password you didn't mean to touch. Spell it out from here on.
                    sender.sendMessage(GateListener.colorize("&cBoth Java and Bedrock passwords are supported now -- say which one:"));
                    sender.sendMessage(GateListener.colorize("&7/nexusgate setpassword java <password>"));
                    sender.sendMessage(GateListener.colorize("&7/nexusgate setpassword bedrock <password>"));
                    return true;
                }

                String newPassword = String.join(" ", Arrays.copyOfRange(args, passwordStart, args.length));
                passwordManager.setPassword(kind, newPassword);
                sender.sendMessage(GateListener.colorize("&aNexusGate " + kind.sectionKey() + " password updated."));
                if (sender instanceof Player) {
                    sender.sendMessage(GateListener.colorize("&7Tip: run this from the server console next time so the password doesn't sit in chat/player logs."));
                }
                plugin.getLogger().info("[NexusGate] " + kind.name() + " password changed by " + sender.getName() + ".");
                return true;
            }
            case "reload": {
                gateListener.loadSettingsFromConfig();
                sender.sendMessage(GateListener.colorize("&aNexusGate config reloaded."));
                return true;
            }
            case "ban": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate ban <player> [reason...]"));
                    return true;
                }
                String targetName = args[1];
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
                accessManager.ban(target.getUniqueId(), targetName, reason, sender.getName());

                Player online = Bukkit.getPlayer(target.getUniqueId());
                if (online != null) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("reason", reason == null || reason.isBlank() ? "(no reason given)" : reason);
                    online.kickPlayer(gateListener.format("banned-kick", ph));
                }

                sender.sendMessage(GateListener.colorize("&aBanned " + targetName
                        + " from NexusGate" + (online != null ? " and kicked them." : ".")));
                plugin.getLogger().info("[NexusGate] " + targetName + " banned by " + sender.getName()
                        + (reason != null ? " (" + reason + ")" : ""));
                return true;
            }
            case "unban": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate unban <player>"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                boolean removed = accessManager.unban(target.getUniqueId());
                sender.sendMessage(GateListener.colorize(removed
                        ? "&aUnbanned " + args[1] + " from NexusGate."
                        : "&7" + args[1] + " wasn't NexusGate-banned."));
                return true;
            }
            case "banlist": {
                List<AccessManager.BanEntry> bans = accessManager.listBans();
                if (bans.isEmpty()) {
                    sender.sendMessage(GateListener.colorize("&7No NexusGate bans."));
                    return true;
                }
                sender.sendMessage(GateListener.colorize("&6NexusGate bans (&f" + bans.size() + "&6):"));
                for (AccessManager.BanEntry b : bans) {
                    sender.sendMessage(GateListener.colorize("&7- &f" + b.name + " &7- " + b.reason
                            + " &7(by " + b.bannedBy + ", " + AccessManager.formatTime(b.bannedAtMillis) + ")"));
                }
                return true;
            }
            case "knownusers": {
                List<AccessManager.KnownPlayer> known = accessManager.listKnownPlayers();
                if (known.isEmpty()) {
                    sender.sendMessage(GateListener.colorize("&7Nobody has typed a correct password yet."));
                    return true;
                }
                int shown = Math.min(known.size(), 50);
                sender.sendMessage(GateListener.colorize("&6Players who have used the password (&f" + known.size()
                        + "&6" + (known.size() > shown ? ", showing most recent " + shown : "") + "):"));
                for (int i = 0; i < shown; i++) {
                    AccessManager.KnownPlayer kp = known.get(i);
                    boolean banned = accessManager.isBanned(kp.uuid);
                    sender.sendMessage(GateListener.colorize("&7- &f" + kp.name + " &7[" + kp.kind + "] logins: &f" + kp.logins
                            + " &7last: &f" + AccessManager.formatTime(kp.lastSeenMillis)
                            + (banned ? " &c[BANNED]" : "")));
                }
                return true;
            }
            case "banip": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate banip <ip> [reason...]"));
                    return true;
                }
                String ip = args[1];
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
                accessManager.banIp(ip, reason, sender.getName());

                int kicked = 0;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (ip.equals(GateListener.ipOf(online))) {
                        Map<String, String> ph = new HashMap<>();
                        ph.put("reason", reason == null || reason.isBlank() ? "(no reason given)" : reason);
                        online.kickPlayer(gateListener.format("ip-banned-kick", ph));
                        kicked++;
                    }
                }

                sender.sendMessage(GateListener.colorize("&aBanned IP " + ip + " from NexusGate"
                        + (kicked > 0 ? " and kicked " + kicked + " connected player(s)." : ".")));
                plugin.getLogger().info("[NexusGate] IP " + ip + " banned by " + sender.getName()
                        + (reason != null ? " (" + reason + ")" : ""));
                return true;
            }
            case "unbanip": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate unbanip <ip>"));
                    return true;
                }
                boolean removed = accessManager.unbanIp(args[1]);
                sender.sendMessage(GateListener.colorize(removed
                        ? "&aUnbanned IP " + args[1] + " from NexusGate."
                        : "&7IP " + args[1] + " wasn't NexusGate-banned."));
                return true;
            }
            case "ipbanlist": {
                List<AccessManager.IpBanEntry> ipBans = accessManager.listIpBans();
                if (ipBans.isEmpty()) {
                    sender.sendMessage(GateListener.colorize("&7No NexusGate IP bans."));
                    return true;
                }
                sender.sendMessage(GateListener.colorize("&6NexusGate IP bans (&f" + ipBans.size() + "&6):"));
                for (AccessManager.IpBanEntry b : ipBans) {
                    sender.sendMessage(GateListener.colorize("&7- &f" + b.ip + " &7- " + b.reason
                            + " &7(by " + b.bannedBy + ", " + AccessManager.formatTime(b.bannedAtMillis) + ")"));
                }
                return true;
            }
            case "altcheck": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate altcheck <player>"));
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                List<String> ips = accessManager.getKnownIps(target.getUniqueId());
                if (ips.isEmpty()) {
                    sender.sendMessage(GateListener.colorize("&7No IP history on file for " + args[1]
                            + " (they may have never logged in successfully)."));
                    return true;
                }
                sender.sendMessage(GateListener.colorize("&6IP history for &f" + args[1] + "&6:"));
                for (String ip : ips) {
                    StringBuilder others = new StringBuilder();
                    for (AccessManager.KnownPlayer kp : accessManager.listKnownPlayersByIp(ip)) {
                        if (kp.uuid.equals(target.getUniqueId())) {
                            continue;
                        }
                        if (others.length() > 0) {
                            others.append("&7, &f");
                        }
                        others.append(kp.name);
                        if (accessManager.isBanned(kp.uuid)) {
                            others.append(" &c[BANNED]&f");
                        }
                    }
                    boolean ipBanned = accessManager.isIpBanned(ip);
                    sender.sendMessage(GateListener.colorize("&7- &f" + ip + (ipBanned ? " &c[IP BANNED]" : "")
                            + (others.length() > 0 ? " &7-- also used by: &f" + others : "")));
                }
                return true;
            }
            case "invite": {
                if (!gateListener.isInvitesEnabled()) {
                    sender.sendMessage(GateListener.colorize("&cInvite codes are disabled (invites.enabled: false in config.yml)."));
                    return true;
                }

                int uses = gateListener.getInviteDefaultUses();
                int minutes = gateListener.getInviteDefaultMinutes();
                String note = null;

                if (args.length >= 2) {
                    try {
                        uses = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate invite [uses] [minutes] [note...]"));
                        return true;
                    }
                }
                if (args.length >= 3) {
                    try {
                        minutes = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate invite [uses] [minutes] [note...]"));
                        return true;
                    }
                }
                if (args.length >= 4) {
                    note = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                }

                int maxUsesCap = gateListener.getInviteMaxUsesCap();
                int maxMinutes = gateListener.getInviteMaxMinutes();
                if (uses < 1) {
                    uses = 1;
                }
                if (uses > maxUsesCap) {
                    sender.sendMessage(GateListener.colorize("&7Capping uses at &f" + maxUsesCap + "&7 (see invites.max-uses-cap in config.yml)."));
                    uses = maxUsesCap;
                }
                if (minutes < 1) {
                    minutes = 1;
                }
                if (minutes > maxMinutes) {
                    sender.sendMessage(GateListener.colorize("&7Capping expiry at &f" + maxMinutes + "&7 minutes (see invites.max-minutes in config.yml)."));
                    minutes = maxMinutes;
                }

                InviteManager.InviteEntry entry = inviteManager.create(sender.getName(), uses, minutes, note);
                sender.sendMessage(GateListener.colorize("&aInvite code created: &e&l" + InviteManager.formatForDisplay(entry.code)));
                sender.sendMessage(GateListener.colorize("&7Uses: &f" + entry.maxUses + " &7Expires: &f"
                        + InviteManager.formatTime(entry.expiresAtMillis) + " &7(in " + minutes + " minute(s))"));
                if (note != null && !note.isBlank()) {
                    sender.sendMessage(GateListener.colorize("&7Note: &f" + note));
                }
                sender.sendMessage(GateListener.colorize("&7Give this to ONE person -- typing it as their &f/login&7 password gets them in."
                        + " Leaked? &f/nexusgate revokeinvite " + entry.code + "&7 kills just this code; nobody else is affected."));
                plugin.getLogger().info("[NexusGate] Invite code " + entry.code + " created by " + sender.getName()
                        + " (uses=" + entry.maxUses + ", minutes=" + minutes + (note != null ? ", note=" + note : "") + ")");
                return true;
            }
            case "invites": {
                boolean showAll = args.length >= 2 && args[1].equalsIgnoreCase("all");
                long now = System.currentTimeMillis();
                List<InviteManager.InviteEntry> shown = new ArrayList<>();
                for (InviteManager.InviteEntry e : inviteManager.listAll()) {
                    if (showAll || e.isActive(now)) {
                        shown.add(e);
                    }
                }
                if (shown.isEmpty()) {
                    sender.sendMessage(GateListener.colorize(showAll
                            ? "&7No invite codes have ever been created."
                            : "&7No active invite codes. (&f/nexusgate invites all&7 also shows expired/used-up/revoked ones.)"));
                    return true;
                }
                sender.sendMessage(GateListener.colorize("&6" + (showAll ? "All invite codes" : "Active invite codes")
                        + " (&f" + shown.size() + "&6):"));
                for (InviteManager.InviteEntry e : shown) {
                    String state = e.revoked ? "&cREVOKED" : e.isExpired(now) ? "&7EXPIRED" : e.isExhausted() ? "&7USED UP" : "&aACTIVE";
                    sender.sendMessage(GateListener.colorize("&7- &e" + InviteManager.formatForDisplay(e.code) + " &7[" + state
                            + "&7] uses: &f" + e.usesCount + "/" + (e.maxUses > 0 ? String.valueOf(e.maxUses) : "unlimited")
                            + " &7expires: &f" + InviteManager.formatTime(e.expiresAtMillis)
                            + " &7by: &f" + e.createdBy
                            + (e.note != null && !e.note.isBlank() ? " &7note: &f" + e.note : "")));
                }
                return true;
            }
            case "revokeinvite": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate revokeinvite <code> [kick|ban] [reason...]"));
                    sender.sendMessage(GateListener.colorize("&7No 2nd argument: just stops the code from working again -- "
                            + "anyone who already used it keeps playing. &fkick&7: also removes them right now (not banned --"
                            + " they can rejoin with the real password or a new code). &fban&7: also NexusGate-bans every one"
                            + " of them permanently (independent of the shared password), same as /nexusgate ban would, one at a time."));
                    return true;
                }

                // Look the code up BEFORE revoking -- revoke() only flips a flag, it never deletes
                // the entry or its redemption history, so this ordering doesn't lose anything, but
                // fetching it first means a single lookup serves both the revoke and the group
                // kick/ban below instead of two round trips.
                InviteManager.InviteEntry entry = inviteManager.get(args[1]);
                if (entry == null) {
                    sender.sendMessage(GateListener.colorize("&7No invite code found matching " + args[1] + "."));
                    return true;
                }

                boolean alreadyRevoked = entry.revoked;
                boolean justRevoked = inviteManager.revoke(args[1]);
                sender.sendMessage(GateListener.colorize(justRevoked
                        ? "&aRevoked invite code " + InviteManager.formatForDisplay(entry.code) + "."
                        : (alreadyRevoked ? "&7That code was already revoked." : "&7That code doesn't exist.")));

                String mode = args.length >= 3 ? args[2].toLowerCase() : "";
                boolean doKick = mode.equals("kick") || mode.equals("ban");
                boolean doBan = mode.equals("ban");

                if (!doKick) {
                    if (!entry.redemptions.isEmpty()) {
                        sender.sendMessage(GateListener.colorize("&7" + entry.redemptions.size()
                                + " past use(s) of this code were left untouched. Add &fkick&7 or &fban&7 to also remove"
                                + " everyone who used it: &f/nexusgate revokeinvite " + entry.code + " ban"));
                    }
                    return true;
                }

                String reason = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                        : "Invite code " + entry.code + " group removed" + (entry.note != null && !entry.note.isBlank()
                                ? " (" + entry.note + ")" : "");

                int affected = purgeInviteGroup(sender, entry, doBan, reason);

                sender.sendMessage(GateListener.colorize((doBan ? "&aBanned and kicked " : "&aKicked ") + affected
                        + " player(s) who used this code" + (doBan ? "." : " (not banned -- they can still get back in with"
                                + " the real password or a new invite code).")));
                plugin.getLogger().info("[NexusGate] Invite code " + entry.code + " group-" + (doBan ? "banned" : "kicked")
                        + " (" + affected + " player(s)) by " + sender.getName());
                return true;
            }
            case "inviteinfo": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate inviteinfo <code>"));
                    return true;
                }
                InviteManager.InviteEntry e = inviteManager.get(args[1]);
                if (e == null) {
                    sender.sendMessage(GateListener.colorize("&7No invite code found matching " + args[1] + "."));
                    return true;
                }
                printInviteInfo(sender, e);
                return true;
            }
            case "teamcode":
            case "teamrotate": {
                boolean rotate = args[0].equalsIgnoreCase("teamrotate");
                if (args.length < 3) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate " + args[0].toLowerCase()
                            + " <teamId> <teamName> [memberUuid...]"));
                    sender.sendMessage(GateListener.colorize("&7teamName: use underscores instead of spaces (e.g. Iron_Wolves)."
                            + " Any listed member UUID who's online right now gets the team's password sent to them"
                            + " privately (nobody else sees it); anyone offline gets it the moment they next join."));
                    return true;
                }

                String teamId = args[1];
                String teamName = args[2].replace('_', ' ');

                List<UUID> memberUuids = new ArrayList<>();
                for (int i = 3; i < args.length; i++) {
                    try {
                        memberUuids.add(UUID.fromString(args[i]));
                    } catch (IllegalArgumentException badUuid) {
                        sender.sendMessage(GateListener.colorize("&7Skipped invalid UUID: &f" + args[i]));
                    }
                }

                InviteManager.InviteEntry entry = rotate
                        ? inviteManager.rotateTeamCode(teamId, teamName, sender.getName())
                        : inviteManager.ensureTeamCode(teamId, teamName, sender.getName());

                announceTeamCode(entry, memberUuids);

                sender.sendMessage(GateListener.colorize("&a" + (rotate ? "Rotated" : "Ensured") + " the team code for &f"
                        + teamName + " &7(" + teamId + "): &e&l" + InviteManager.formatForDisplay(entry.code)));
                sender.sendMessage(GateListener.colorize("&7Notified " + memberUuids.size() + " member(s) (sent now if"
                        + " online, queued for their next join otherwise). Leaked? &f/nexusgate teamrotate " + teamId
                        + " " + args[2] + "&7 replaces it -- the real password and every other code stay untouched."));
                plugin.getLogger().info("[NexusGate] Team code for " + teamId + " (" + teamName + ") "
                        + (rotate ? "rotated" : "ensured") + " by " + sender.getName());
                return true;
            }
            case "teamremove": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate teamremove <teamId> [kick|ban] [reason...]"));
                    sender.sendMessage(GateListener.colorize("&7Revokes and unlinks the team's code (a future /nexusgate"
                            + " teamcode for this team starts completely fresh). No 2nd argument: just kills the code --"
                            + " current members keep playing with whatever access they already have. &fkick&7: also"
                            + " removes everyone who's used this team's code right now (not banned -- they can rejoin"
                            + " with the real password or a new code). &fban&7: also NexusGate-bans every one of them"
                            + " permanently, one at a time."));
                    return true;
                }

                String teamId = args[1];
                InviteManager.InviteEntry entry = inviteManager.getForTeam(teamId);
                if (entry == null) {
                    sender.sendMessage(GateListener.colorize("&7No team code has ever been issued for team " + teamId + "."));
                    return true;
                }

                boolean justRevoked = inviteManager.revoke(entry.code);
                inviteManager.unlinkTeam(teamId);
                sender.sendMessage(GateListener.colorize(justRevoked
                        ? "&aRevoked and unlinked the team code for " + teamId + "."
                        : "&7That team's code was already revoked; unlinked it anyway."));

                String mode = args.length >= 3 ? args[2].toLowerCase() : "";
                boolean doKick = mode.equals("kick") || mode.equals("ban");
                boolean doBan = mode.equals("ban");

                if (!doKick) {
                    if (!entry.redemptions.isEmpty()) {
                        sender.sendMessage(GateListener.colorize("&7" + entry.redemptions.size()
                                + " past use(s) of this team's code were left untouched. Add &fkick&7 or &fban&7 to also"
                                + " remove everyone who used it: &f/nexusgate teamremove " + teamId + " ban"));
                    }
                    return true;
                }

                String reason = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                        : "Team " + teamId + " code removed" + (entry.note != null && !entry.note.isBlank()
                                ? " (" + entry.note + ")" : "");

                int affected = purgeInviteGroup(sender, entry, doBan, reason);
                sender.sendMessage(GateListener.colorize((doBan ? "&aBanned and kicked " : "&aKicked ") + affected
                        + " player(s) who used this team's code" + (doBan ? "." : " (not banned -- they can still get"
                                + " back in with the real password or a new invite/team code).")));
                plugin.getLogger().info("[NexusGate] Team code for " + teamId + " group-" + (doBan ? "banned" : "kicked")
                        + " (" + affected + " player(s)) by " + sender.getName());
                return true;
            }
            case "teaminfo": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate teaminfo <teamId>"));
                    return true;
                }
                InviteManager.InviteEntry e = inviteManager.getForTeam(args[1]);
                if (e == null) {
                    sender.sendMessage(GateListener.colorize("&7No team code has ever been issued for team " + args[1] + "."));
                    return true;
                }
                printInviteInfo(sender, e);
                return true;
            }
            case "personalcode":
            case "personalrotate": {
                boolean rotate = args[0].equalsIgnoreCase("personalrotate");
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate " + args[0].toLowerCase() + " <playerName>"));
                    sender.sendMessage(GateListener.colorize("&7Issues a permanent, unlimited-use code for this ONE player --"
                            + " unlike an invite or team code, it locks to whichever uuid types it first, so it's a real"
                            + " permanent password just for them. Works even if they've never joined before: hand it to"
                            + " them yourself (Discord, text, whatever) before their first login."));
                    return true;
                }

                String playerName = args[1];
                InviteManager.InviteEntry entry = rotate
                        ? inviteManager.rotatePersonalCode(playerName, sender.getName())
                        : inviteManager.ensurePersonalCode(playerName, sender.getName());

                sender.sendMessage(GateListener.colorize("&a" + (rotate ? "Rotated" : "Issued") + " &f" + playerName
                        + "&a's permanent personal code: &d&l" + InviteManager.formatForDisplay(entry.code)));
                if (entry.boundUuid == null) {
                    sender.sendMessage(GateListener.colorize("&7Not claimed yet -- give this to " + playerName
                            + " directly (it's not sent in-game, since they may never have joined). The first account that"
                            + " types it at &f/login&7 owns it forever after; nobody else can ever use it, even if it leaks later."));
                } else {
                    sender.sendMessage(GateListener.colorize("&7Already claimed by uuid &f" + entry.boundUuid
                            + "&7. " + (rotate ? "That binding is now gone -- this is a fresh, unclaimed code."
                                    : "Re-running this without rotating just showed you the existing one; nothing changed.")));
                }
                sender.sendMessage(GateListener.colorize("&7Leaked before being claimed, or need to replace it later? &f/nexusgate personalrotate "
                        + playerName + "&7 kills the old one and issues a fresh, unclaimed code."));
                plugin.getLogger().info("[NexusGate] Personal code for " + playerName + " "
                        + (rotate ? "rotated" : "ensured") + " by " + sender.getName());
                return true;
            }
            case "personalremove": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate personalremove <playerName> [kick|ban] [reason...]"));
                    sender.sendMessage(GateListener.colorize("&7Revokes and unlinks this player's permanent personal code (a future"
                            + " /nexusgate personalcode for this name starts completely fresh). No 2nd argument: if they'd"
                            + " already claimed it, they keep whatever access they already have -- the code just stops"
                            + " working for a future login. &fkick&7: also removes them right now if they're online (not"
                            + " banned). &fban&7: also NexusGate-bans them, same as /nexusgate ban."));
                    return true;
                }

                String playerName = args[1];
                InviteManager.InviteEntry entry = inviteManager.getForPersonal(playerName);
                if (entry == null) {
                    sender.sendMessage(GateListener.colorize("&7No personal code has ever been issued for " + playerName + "."));
                    return true;
                }

                boolean justRevoked = inviteManager.revoke(entry.code);
                inviteManager.unlinkPersonal(playerName);
                sender.sendMessage(GateListener.colorize(justRevoked
                        ? "&aRevoked and unlinked " + playerName + "'s personal code."
                        : "&7That code was already revoked; unlinked it anyway."));

                String mode = args.length >= 3 ? args[2].toLowerCase() : "";
                boolean doKick = mode.equals("kick") || mode.equals("ban");
                boolean doBan = mode.equals("ban");

                if (!doKick) {
                    if (entry.boundUuid != null) {
                        sender.sendMessage(GateListener.colorize("&7" + playerName
                                + " already claimed this code and keeps their current access. Add &fkick&7 or &fban&7 to"
                                + " also remove them: &f/nexusgate personalremove " + playerName + " ban"));
                    }
                    return true;
                }

                String reason = args.length >= 4 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
                        : "Personal code for " + playerName + " removed";

                int affected = purgeInviteGroup(sender, entry, doBan, reason);
                sender.sendMessage(GateListener.colorize((doBan ? "&aBanned and kicked " : "&aKicked ") + affected
                        + " player(s) who had claimed this code" + (doBan ? "." : " (not banned -- they could still get"
                                + " back in with the real password or a new code).")));
                plugin.getLogger().info("[NexusGate] Personal code for " + playerName + " " + (doBan ? "banned" : "kicked")
                        + " (" + affected + " player(s)) by " + sender.getName());
                return true;
            }
            case "personalinfo": {
                if (args.length < 2) {
                    sender.sendMessage(GateListener.colorize("&cUsage: /nexusgate personalinfo <playerName>"));
                    return true;
                }
                InviteManager.InviteEntry e = inviteManager.getForPersonal(args[1]);
                if (e == null) {
                    sender.sendMessage(GateListener.colorize("&7No personal code has ever been issued for " + args[1] + "."));
                    return true;
                }
                printInviteInfo(sender, e);
                return true;
            }
            case "status": {
                sender.sendMessage(GateListener.colorize("&6NexusGate status:"));
                sender.sendMessage(GateListener.colorize("&7Java password: "
                        + (passwordManager.isSet(PasswordManager.PasswordKind.JAVA)
                                ? "&a" + passwordManager.currentValue(PasswordManager.PasswordKind.JAVA)
                                : "&c(not set)")));
                sender.sendMessage(GateListener.colorize("&7Bedrock password: "
                        + (passwordManager.isSet(PasswordManager.PasswordKind.BEDROCK)
                                ? "&a" + passwordManager.currentValue(PasswordManager.PasswordKind.BEDROCK)
                                : "&c(not set)")));
                sender.sendMessage(GateListener.colorize("&7(Both are also visible/editable directly in config.yml under 'passwords:'.)"));
                sender.sendMessage(GateListener.colorize("&7Timeout: &f" + gateListener.getTimeoutSeconds() + "s&7, Max attempts: &f" + gateListener.getMaxAttempts()));
                sender.sendMessage(GateListener.colorize("&7Players currently pending auth: &f" + pendingAuthManager.pendingCount()));
                sender.sendMessage(GateListener.colorize("&7Active IP lockouts: &f" + lockoutManager.activeLockoutCount()));
                String altMode = gateListener.getAltDetectionMode();
                sender.sendMessage(GateListener.colorize("&7Alt-detection: " + ("autoban".equals(altMode) ? "&c" : "&a") + altMode
                        + (("autoban".equals(altMode)) ? " &7(ban-ip-too: &f" + gateListener.isAltDetectionBanIpToo() + "&7)" : "")));
                long now = System.currentTimeMillis();
                int activeInvites = 0;
                for (InviteManager.InviteEntry e : inviteManager.listAll()) {
                    if (e.isActive(now)) {
                        activeInvites++;
                    }
                }
                sender.sendMessage(GateListener.colorize("&7Invite codes: " + (gateListener.isInvitesEnabled() ? "&aenabled" : "&cdisabled")
                        + " &7(&f" + activeInvites + " active&7, &f/nexusgate invites&7 to list)"));
                return true;
            }
            default: {
                sendUsage(sender);
                return true;
            }
        }
    }

    /** De-duplicates by UUID (a multi-use/team code can have several redemption records from the
     * SAME player, e.g. they disconnected and rejoined with it) and bans (if {@code doBan}) plus
     * kicks online players who are recorded as having redeemed {@code entry}. Returns how many
     * distinct players were affected. Shared by "/nexusgate revokeinvite ... kick|ban" and
     * "/nexusgate teamremove ... kick|ban" so both group-removal paths behave identically. */
    private int purgeInviteGroup(CommandSender sender, InviteManager.InviteEntry entry, boolean doBan, String reason) {
        Set<UUID> handled = new HashSet<>();
        int affected = 0;
        for (InviteManager.Redemption r : entry.redemptions) {
            if (r.uuid == null || !handled.add(r.uuid)) {
                continue;
            }
            affected++;
            if (doBan) {
                accessManager.ban(r.uuid, r.name, reason, sender.getName());
            }
            Player online = Bukkit.getPlayer(r.uuid);
            if (online != null) {
                if (doBan) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("reason", reason);
                    online.kickPlayer(gateListener.format("banned-kick", ph));
                } else {
                    online.kickPlayer(GateListener.colorize("&cYour access was removed by staff."));
                }
            }
        }
        return affected;
    }

    /** Full status/history printout for one invite entry -- shared by "/nexusgate inviteinfo"
     * (looked up by raw code) and "/nexusgate teaminfo" (looked up by team id) so both display
     * identically. */
    private void printInviteInfo(CommandSender sender, InviteManager.InviteEntry e) {
        long now = System.currentTimeMillis();
        String state = e.revoked ? "&cREVOKED" : e.isExpired(now) ? "&7EXPIRED" : e.isExhausted() ? "&7USED UP" : "&aACTIVE";
        sender.sendMessage(GateListener.colorize("&6Invite &e" + InviteManager.formatForDisplay(e.code) + " &7[" + state + "&7]"));
        sender.sendMessage(GateListener.colorize("&7Created by &f" + e.createdBy + " &7at &f" + InviteManager.formatTime(e.createdAtMillis)));
        sender.sendMessage(GateListener.colorize("&7Expires: &f" + InviteManager.formatTime(e.expiresAtMillis)
                + " &7Uses: &f" + e.usesCount + "/" + (e.maxUses > 0 ? String.valueOf(e.maxUses) : "unlimited")));
        if (e.note != null && !e.note.isBlank()) {
            sender.sendMessage(GateListener.colorize("&7Note: &f" + e.note));
        }
        if (e.personalName != null) {
            sender.sendMessage(GateListener.colorize("&7Personal code for &f" + e.personalName + "&7: "
                    + (e.boundUuid != null ? "&aclaimed by uuid &f" + e.boundUuid : "&enot claimed yet")));
        }
        if (e.redemptions.isEmpty()) {
            sender.sendMessage(GateListener.colorize("&7Nobody has used this code yet."));
        } else {
            sender.sendMessage(GateListener.colorize("&7Used by:"));
            for (InviteManager.Redemption r : e.redemptions) {
                sender.sendMessage(GateListener.colorize("&7- &f" + r.name + " &7(uuid " + r.uuid + ", ip " + r.ip
                        + ", " + InviteManager.formatTime(r.atMillis) + ")"));
            }
            sender.sendMessage(GateListener.colorize("&7If any of those shouldn't have access: &f/nexusgate ban <player>&7"
                    + " removes just them -- the shared password and this code (for anyone else who has it) are untouched."));
        }
    }

    /** Sends {@code entry}'s code privately to every currently-online member in {@code memberUuids}
     * (nobody else sees it), and queues it for anyone offline so they get it the moment they next
     * join (see InviteManager.takePendingAnnouncements / GateListener.onJoin). Used by both
     * "/nexusgate teamcode" and "/nexusgate teamrotate". */
    private void announceTeamCode(InviteManager.InviteEntry entry, List<UUID> memberUuids) {
        Map<String, String> ph = new HashMap<>();
        ph.put("code", InviteManager.formatForDisplay(entry.code));
        String message = gateListener.format("team-code-announce", ph);
        for (UUID uuid : memberUuids) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                online.sendMessage(message);
            } else {
                inviteManager.queuePendingAnnouncement(uuid, entry.code);
            }
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(GateListener.colorize("&6NexusGate &7-- /nexusgate <setpassword [java|bedrock] <password>|reload|status"
                + "|ban <player> [reason]|unban <player>|banlist|knownusers"
                + "|banip <ip> [reason]|unbanip <ip>|ipbanlist|altcheck <player>"
                + "|invite [uses] [minutes] [note]|invites [all]|revokeinvite <code> [kick|ban]|inviteinfo <code>"
                + "|teamcode <teamId> <teamName> [memberUuid...]|teamrotate <teamId> <teamName> [memberUuid...]"
                + "|teamremove <teamId> [kick|ban]|teaminfo <teamId>"
                + "|personalcode <playerName>|personalrotate <playerName>|personalremove <playerName> [kick|ban]"
                + "|personalinfo <playerName>>"));
    }
}

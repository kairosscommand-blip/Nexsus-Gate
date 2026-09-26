package com.nexuscraft.nexusgate;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LoginCommandExecutor implements CommandExecutor {

    private final JavaPlugin plugin;
    private final PasswordManager passwordManager;
    private final PendingAuthManager pendingAuthManager;
    private final LockoutManager lockoutManager;
    private final AuditLogger auditLogger;
    private final GateListener gateListener;
    private final AccessManager accessManager;
    private final InviteManager inviteManager;

    public LoginCommandExecutor(JavaPlugin plugin, PasswordManager passwordManager, PendingAuthManager pendingAuthManager,
                                 LockoutManager lockoutManager, AuditLogger auditLogger, GateListener gateListener,
                                 AccessManager accessManager, InviteManager inviteManager) {
        this.plugin = plugin;
        this.passwordManager = passwordManager;
        this.pendingAuthManager = pendingAuthManager;
        this.lockoutManager = lockoutManager;
        this.auditLogger = auditLogger;
        this.gateListener = gateListener;
        this.accessManager = accessManager;
        this.inviteManager = inviteManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use /login.");
            return true;
        }
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();

        if (!pendingAuthManager.isPending(uuid)) {
            player.sendMessage(gateListener.format("not-pending", null));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(gateListener.format("usage-login", null));
            return true;
        }

        String ip = GateListener.ipOf(player);
        long lockedRemaining = lockoutManager.remainingLockoutSeconds(ip);
        if (lockedRemaining > 0) {
            Map<String, String> ph = new HashMap<>();
            ph.put("remaining", GateListener.formatDuration(lockedRemaining));
            player.kickPlayer(gateListener.format("locked-out-kick", ph));
            return true;
        }

        PasswordManager.PasswordKind kind = BedrockUtil.kindOf(player, plugin.getLogger());
        if (passwordManager.verify(kind, args[0])) {
            pendingAuthManager.removeAndCancel(uuid);
            lockoutManager.clear(ip);
            gateListener.completeAuth(player);
            boolean firstTime = accessManager.recordSuccess(uuid, player.getName(), kind.sectionKey(), ip);
            player.sendMessage(gateListener.format("success", null));
            auditLogger.log("SUCCESS  player=" + player.getName() + " uuid=" + uuid + " ip=" + ip + " kind=" + kind
                    + (firstTime ? " first-time=true" : ""));
            if (firstTime) {
                gateListener.notifyWatchers("&b[NexusGate] &f" + player.getName()
                        + " &7just logged in for the &bfirst time ever&7 (" + kind.sectionKey() + ", ip " + ip + ").");
            }
            return true;
        }

        // Not the platform password -- try it as a temporary invite code before counting it as a
        // wrong attempt. Deliberately checked AFTER the real password, and with the exact same
        // "wrong-password" message on failure below, so a guess never reveals whether it was
        // close to a real invite code or not.
        if (gateListener.isInvitesEnabled()) {
            InviteManager.RedeemResult invite = inviteManager.tryRedeem(args[0], uuid, player.getName(), ip);
            if (invite.status == InviteManager.RedeemStatus.SUCCESS) {
                InviteManager.InviteEntry entry = invite.entry;
                pendingAuthManager.removeAndCancel(uuid);
                lockoutManager.clear(ip);
                gateListener.completeAuth(player);
                boolean firstTime = accessManager.recordSuccess(uuid, player.getName(), kind.sectionKey(), ip);
                // A personal code's very first successful use is the moment it stops being "a
                // code an admin handed out" and becomes "this player's own permanent password" --
                // worth its own message instead of the generic invite-accepted one.
                player.sendMessage(gateListener.format(invite.justClaimed ? "personal-code-claimed" : "invite-accepted", null));
                String usesLeft = entry.maxUses > 0 ? String.valueOf(Math.max(0, entry.maxUses - entry.usesCount)) : "unlimited";
                auditLogger.log("INVITE-SUCCESS  player=" + player.getName() + " uuid=" + uuid + " ip=" + ip + " kind=" + kind
                        + " code=" + entry.code + " created-by=" + entry.createdBy + " uses-left=" + usesLeft
                        + (entry.personalName != null ? " personal-for=" + entry.personalName : "")
                        + (invite.justClaimed ? " claimed=true" : "")
                        + (firstTime ? " first-time=true" : ""));
                if (invite.justClaimed) {
                    gateListener.notifyWatchers("&d[NexusGate] &f" + player.getName() + " &7just claimed their permanent"
                            + " personal code &d" + InviteManager.formatForDisplay(entry.code)
                            + " &7(issued by &f" + entry.createdBy + "&7) -- it's now locked to just them.");
                } else {
                    gateListener.notifyWatchers("&e[NexusGate] &f" + player.getName() + " &7got in using invite code &e"
                            + InviteManager.formatForDisplay(entry.code) + " &7(created by &f" + entry.createdBy + "&7"
                            + (entry.note != null && !entry.note.isBlank() ? ", note: " + entry.note : "")
                            + "&7). Uses left: &f" + usesLeft);
                }
                if (firstTime) {
                    gateListener.notifyWatchers("&b[NexusGate] &f" + player.getName()
                            + " &7just logged in for the &bfirst time ever&7 (" + kind.sectionKey() + ", ip " + ip + ").");
                }
                return true;
            }
            if (invite.status == InviteManager.RedeemStatus.EXPIRED || invite.status == InviteManager.RedeemStatus.REVOKED
                    || invite.status == InviteManager.RedeemStatus.EXHAUSTED) {
                // This IS a real invite code, just no longer valid -- worth flagging even though
                // it's being rejected, since "someone still trying a code after it stopped
                // working" is exactly the shape of a leaked-credential attempt this feature
                // exists to surface.
                auditLogger.log("INVITE-FAIL  player=" + player.getName() + " uuid=" + uuid + " ip=" + ip
                        + " code=" + invite.entry.code + " status=" + invite.status);
                gateListener.notifyWatchers("&6[NexusGate] &f" + player.getName() + " &7tried invite code &6"
                        + InviteManager.formatForDisplay(invite.entry.code) + " &7which is now &6" + invite.status
                        + "&7 -- if that code leaked, this is worth a look.");
                HeartbeatBridge.report(plugin, "SECURITY", 1, "A no-longer-valid NexusGate invite code was tried and rejected.");
            }
            if (invite.status == InviteManager.RedeemStatus.OWNER_MISMATCH) {
                // Someone typed a real, still-active personal code that belongs to a DIFFERENT
                // player -- this is a much sharper signal than an ordinary expired/used-up code,
                // since a personal code should only ever be known by the one person it was issued
                // to and whoever it's now locked to. Worth flagging loudly.
                auditLogger.log("PERSONAL-MISMATCH  player=" + player.getName() + " uuid=" + uuid + " ip=" + ip
                        + " code=" + invite.entry.code + " personal-for=" + invite.entry.personalName
                        + " bound-to=" + invite.entry.boundUuid);
                gateListener.notifyWatchers("&4[NexusGate] &f" + player.getName() + " &7tried &f"
                        + invite.entry.personalName + "&7's permanent personal code &4"
                        + InviteManager.formatForDisplay(invite.entry.code) + " &7-- it's locked to a different account."
                        + " Possible leaked credential, worth a look.");
                HeartbeatBridge.report(plugin, "SECURITY", 1, "Someone tried a NexusGate personal code that belongs to a different player.");
            }
        }

        int attempts = pendingAuthManager.incrementAttempt(uuid);
        int maxAttempts = gateListener.getMaxAttempts();
        auditLogger.log("FAIL     player=" + player.getName() + " uuid=" + uuid + " ip=" + ip
                + " attempt=" + attempts + "/" + maxAttempts + " kind=" + kind);

        if (attempts >= maxAttempts) {
            pendingAuthManager.removeAndCancel(uuid);
            lockoutManager.registerFailure(ip);
            player.kickPlayer(gateListener.format("too-many-attempts-kick", null));
            return true;
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("remaining", String.valueOf(maxAttempts - attempts));
        player.sendMessage(gateListener.format("wrong-password", ph));
        return true;
    }
}

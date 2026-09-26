package com.nexuscraft.nexusgate;

import org.bukkit.plugin.java.JavaPlugin;

public final class NexusGate extends JavaPlugin {

    private PasswordManager passwordManager;
    private PendingAuthManager pendingAuthManager;
    private LockoutManager lockoutManager;
    private AuditLogger auditLogger;
    private AccessManager accessManager;
    private InviteManager inviteManager;
    private GateListener gateListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.passwordManager = new PasswordManager(this);
        this.pendingAuthManager = new PendingAuthManager();
        this.lockoutManager = new LockoutManager();
        this.auditLogger = new AuditLogger(this);
        this.accessManager = new AccessManager(this);
        this.inviteManager = new InviteManager(this);

        this.gateListener = new GateListener(this, passwordManager, pendingAuthManager, lockoutManager, auditLogger, accessManager, inviteManager);
        gateListener.loadSettingsFromConfig();
        getServer().getPluginManager().registerEvents(gateListener, this);

        LoginCommandExecutor loginExecutor = new LoginCommandExecutor(this, passwordManager, pendingAuthManager, lockoutManager, auditLogger, gateListener, accessManager, inviteManager);
        getCommand("login").setExecutor(loginExecutor);

        NexusGateCommandExecutor adminExecutor = new NexusGateCommandExecutor(this, passwordManager, pendingAuthManager, lockoutManager, gateListener, accessManager, inviteManager);
        getCommand("nexusgate").setExecutor(adminExecutor);

        if (!passwordManager.isSet(PasswordManager.PasswordKind.JAVA)) {
            getLogger().warning("No Java password is set yet! Java players are joining WITHOUT the gate. Run: /nexusgate setpassword java <password> (from console recommended).");
        }
        if (!passwordManager.isSet(PasswordManager.PasswordKind.BEDROCK)) {
            getLogger().warning("No Bedrock/Xbox password is set yet! Bedrock players are joining WITHOUT the gate (unless exempt-bedrock is true). Run: /nexusgate setpassword bedrock <password> (from console recommended).");
        }

        getLogger().info("NexusGate enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("NexusGate disabled.");
    }
}

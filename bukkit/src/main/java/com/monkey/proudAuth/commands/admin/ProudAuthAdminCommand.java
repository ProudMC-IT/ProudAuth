package com.monkey.proudAuth.commands.admin;

import com.monkey.proudAuth.commands.admin.subcommands.*;
import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class ProudAuthAdminCommand implements CommandExecutor, TabCompleter {

    private final LangConfig langConfig;
    private final List<AdminSubcommand> primarySubcommands;
    private final Map<String, AdminSubcommand> subcommandsByName;

    public ProudAuthAdminCommand(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            LangConfig langConfig,
            AuthService authService,
            PlayerProtection playerProtection,
            BruteForceGuard bruteForceGuard,
            StorageProvider storage,
            SessionManager sessionManager,
            IdentityClaimService identityClaimService,
            PremiumVerifier premiumVerifier,
            Runnable reloadAction
    ) {
        this.langConfig = langConfig;
        AdminCommandContext context = new AdminCommandContext(
                plugin,
                pluginConfig,
                langConfig,
                authService,
                playerProtection,
                bruteForceGuard,
                storage,
                sessionManager,
                identityClaimService,
                premiumVerifier,
                reloadAction
        );
        this.primarySubcommands = List.of(
                new ForceLoginAdminSubcommand(context),
                new ForceLogoutAdminSubcommand(context),
                new ResetPasswordAdminSubcommand(context),
                new BanIpAdminSubcommand(context),
                new UnbanIpAdminSubcommand(context),
                new TrustIpAdminSubcommand(context),
                new WhitelistIpAdminSubcommand(context),
                new ClaimAdminSubcommand(context),
                new SessionAdminSubcommand(context),
                new PremiumCheckAdminSubcommand(context),
                new AuthInfoAdminSubcommand(context),
                new TwoFactorAdminSubcommand(context),
                new UnlockAdminSubcommand(context),
                new HistoryAdminSubcommand(context),
                new BridgeAdminSubcommand(context),
                new DebugAdminSubcommand(context),
                new StatsAdminSubcommand(context),
                new ReloadAdminSubcommand(context)
        );
        this.subcommandsByName = indexSubcommands(primarySubcommands);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("proudauth.admin")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            langConfig.send(sender, "error-usage",
                    Placeholder.unparsed("usage",
                            "/proudauth <forcelogin|forcelogout|resetpassword|banip|unbanip|trustip|whitelistip|claim|session|premiumcheck|authinfo|2fa|unlock|history|bridge|debug|stats|reload>"));
            return true;
        }

        AdminSubcommand subcommand = subcommandsByName.get(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            langConfig.send(sender, "admin-unknown-subcommand");
            return true;
        }
        return subcommand.execute(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            for (AdminSubcommand subcommand : primarySubcommands) {
                names.add(subcommand.name());
                names.addAll(subcommand.aliases());
            }
            return AdminCommandSupport.filter(names, args[0]);
        }
        AdminSubcommand subcommand = subcommandsByName.get(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            return List.of();
        }
        return subcommand.tabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    private Map<String, AdminSubcommand> indexSubcommands(List<AdminSubcommand> subcommands) {
        Map<String, AdminSubcommand> mapping = new LinkedHashMap<>();
        for (AdminSubcommand subcommand : subcommands) {
            mapping.put(subcommand.name().toLowerCase(Locale.ROOT), subcommand);
            for (String alias : subcommand.aliases()) {
                mapping.put(alias.toLowerCase(Locale.ROOT), subcommand);
            }
        }
        return Collections.unmodifiableMap(mapping);
    }
}

package com.zenith.command.impl;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.Proxy;
import com.zenith.command.api.*;
import com.zenith.discord.Embed;
import com.zenith.network.client.Authenticator;
import com.zenith.util.config.Config;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.zenith.Globals.CONFIG;
import static com.zenith.command.brigadier.CustomStringArgumentType.getString;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static java.util.Arrays.asList;

public class AuthCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("auth")
            .category(CommandCategory.MANAGE)
            .description("""
            Configures the proxy's authentication settings.

            To switch accounts, use the `clear` command.

            `attempts` configures the number of login attempts before wiping the cache.

            `alwaysRefreshOnLogin` will always refresh the token on login instead of trusting the cache. This can cause
            Microsoft to rate limit your account. Auth tokens will always refresh in the background even if this is off.

            `deviceCode` is the default and recommended authentication type.
            If authentication fails, try logging into the account on the vanilla MC launcher and joining a server. Then try again in Zenith.
            If this still fails, try one of the alternate auth types.

            For cracked/offline servers (like 6b6t):
            1. `auth type offline`
            2. `auth username YourBotName`
            3. `auth serverPassword YourPassword`
            4. `auth serverLoginRequired on`
            Then use `connect` to join.

            """)
            .usageLines(
                "clear",
                "attempts <int>",
                "alwaysRefreshOnLogin on/off",
                "type <deviceCode/emailAndPassword/prism/offline>",
                "email <email>",
                "password <password>",
                "username <name>",
                "serverPassword <password>",
                "serverLoginRequired on/off",
                "mention on/off",
                "openBrowser on/off",
                "maxRefreshIntervalMins <minutes>",
                "useClientConnectionProxy on/off",
                "chatSigning on/off",
                "chatSigning force on/off",
                "chatSigning commands on/off"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("auth").requires(Command::validateAccountOwner)
            /**
             * Lets us reset the current authentication state
             * Can be used to switch accounts if using device code auth
             */
            .then(literal("clear").executes(c -> {
                Proxy.getInstance().cancelLogin();
                Authenticator.INSTANCE.clearAuthCache();
                c.getSource().getEmbed()
                    .title("Authentication Cleared")
                    .description("Cached tokens and authentication state cleared. Full re-auth will occur on next login.")
                    .primaryColor();
            }))
            .then(literal("attempts").then(argument("attempts", integer(1)).executes(c -> {
                CONFIG.authentication.msaLoginAttemptsBeforeCacheWipe = c.getArgument("attempts", Integer.class);
                c.getSource().getEmbed()
                    .title("Authentication Max Attempts Set")
                    .primaryColor();
                return OK;
            })))
            .then(literal("alwaysRefreshOnLogin").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.authentication.alwaysRefreshOnLogin = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Always Refresh On Login " + toggleStrCaps(CONFIG.authentication.alwaysRefreshOnLogin))
                    .primaryColor();
                return OK;
            })))
            .then(literal("type").requires(this::validateDiscordOrTerminalSource)
                .then(argument("typeArg", enumStrings("deviceCode", "emailAndPassword", "prism", "offline")).executes(c -> {
                    String type = getString(c, "typeArg");
                    Config.Authentication.AccountType accountType = switch (type) {
                        case "deviceCode" -> Config.Authentication.AccountType.DEVICE_CODE;
                        case "emailAndPassword" -> Config.Authentication.AccountType.MSA;
                        case "prism" -> Config.Authentication.AccountType.PRISM;
                        case "offline" -> Config.Authentication.AccountType.OFFLINE;
                        default -> null;
                    };
                    if (accountType == null) {
                        c.getSource().getEmbed()
                            .title("Invalid Type")
                            .errorColor();
                        return ERROR;
                    }
                    CONFIG.authentication.accountType = accountType;
                    c.getSource().getEmbed()
                        .title("Authentication Type Set")
                        .primaryColor();
                    Proxy.getInstance().cancelLogin();
                    Authenticator.INSTANCE.clearAuthCache();
                    return OK;
                })))
            .then(literal("email").requires(this::validateTerminalSource)
                .then(argument("email", wordWithChars()).executes(c -> {
                    c.getSource().setSensitiveInput(true);
                    var emailStr = getString(c, "email").trim();
                    // validate email str is an email
                    if (!emailStr.contains("@") || emailStr.length() < 3) {
                        c.getSource().getEmbed()
                            .title("Invalid Email")
                            .errorColor();
                        return OK;
                    }
                    CONFIG.authentication.email = emailStr;
                    c.getSource().getEmbed()
                        .title("Authentication Email Set")
                        .primaryColor();
                    return OK;
                })))
            .then(literal("password").requires(this::validateTerminalSource)
                .then(argument("password", wordWithChars()).executes(c -> {
                    c.getSource().setSensitiveInput(true);
                    var passwordStr = getString(c, "password").trim();
                    // validate password str is a password
                    if (passwordStr.isBlank()) {
                        c.getSource().getEmbed()
                            .title("Invalid Password")
                            .errorColor();
                        return OK;
                    }
                    CONFIG.authentication.password = passwordStr;
                    c.getSource().getEmbed()
                        .title("Authentication Password Set")
                        .primaryColor();
                    return OK;
                })))
            .then(literal("mention")
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.discord.mentionRoleOnDeviceCodeAuth = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Mention Role " + toggleStrCaps(CONFIG.discord.mentionRoleOnDeviceCodeAuth))
                        .primaryColor();
                    return OK;
                })))
            .then(literal("openBrowser").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.authentication.openBrowserOnLogin = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Open Browser On Login " + toggleStrCaps(CONFIG.authentication.openBrowserOnLogin))
                    .primaryColor();
                return OK;
            })))
            .then(literal("maxRefreshInterval").then(argument("minutes", integer(5, 500)).executes(c -> {
                CONFIG.authentication.maxRefreshIntervalMins = c.getArgument("minutes", Integer.class);
                c.getSource().getEmbed()
                    .title("Max Refresh Interval Set")
                    .primaryColor();
                return OK;
            })))
            .then(literal("useClientConnectionProxy").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.authentication.useClientConnectionProxy = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Use Client Connection Proxy " + toggleStrCaps(CONFIG.authentication.useClientConnectionProxy))
                    .primaryColor();
                return OK;
            })))
            .then(literal("username")
                .then(argument("username", wordWithChars()).executes(c -> {
                    var usernameStr = getString(c, "username").trim();
                    if (usernameStr.isBlank() || usernameStr.length() < 3 || usernameStr.length() > 16) {
                        c.getSource().getEmbed()
                            .title("Invalid Username")
                            .description("Username must be 3-16 characters")
                            .errorColor();
                        return ERROR;
                    }
                    CONFIG.authentication.username = usernameStr;
                    c.getSource().getEmbed()
                        .title("Username Set")
                        .description("Username set to: " + usernameStr)
                        .primaryColor();
                    return OK;
                })))
            .then(literal("serverPassword")
                .then(argument("serverPassword", wordWithChars()).executes(c -> {
                    c.getSource().setSensitiveInput(true);
                    var passStr = getString(c, "serverPassword").trim();
                    if (passStr.isBlank()) {
                        c.getSource().getEmbed()
                            .title("Invalid Server Password")
                            .errorColor();
                        return ERROR;
                    }
                    CONFIG.authentication.serverPassword = passStr;
                    c.getSource().getEmbed()
                        .title("Server Password Set")
                        .description("The /login password for the cracked server has been saved")
                        .primaryColor();
                    return OK;
                })))
            .then(literal("serverLoginRequired").then(argument("toggle", toggle()).executes(c -> {
                CONFIG.authentication.serverLoginRequired = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Server Login Required " + toggleStrCaps(CONFIG.authentication.serverLoginRequired))
                    .description("When ON, the bot will wait for /login or /register prompts before marking itself as online")
                    .primaryColor();
                return OK;
            })))
            .then(literal("chatSigning")
                .then(argument("toggle", toggle()).executes(c -> {
                    CONFIG.client.chatSigning.enabled = getToggle(c, "toggle");
                    c.getSource().getEmbed()
                        .title("Chat Signing " + toggleStrCaps(CONFIG.client.chatSigning.enabled))
                        .primaryColor();
                    return OK;
                }))
                .then(literal("force").then(argument("forceToggle", toggle()).executes(c -> {
                    CONFIG.client.chatSigning.force = getToggle(c, "forceToggle");
                    c.getSource().getEmbed()
                        .title("Chat Signing Force " + toggleStrCaps(CONFIG.client.chatSigning.force))
                        .primaryColor();
                    return OK;
                })))
                .then(literal("commands").then(argument("commandsToggle", toggle()).executes(c -> {
                    CONFIG.client.chatSigning.signCommands = getToggle(c, "commandsToggle");
                    c.getSource().getEmbed()
                        .title("Chat Signing Commands " + toggleStrCaps(CONFIG.client.chatSigning.signCommands))
                        .primaryColor();
                    return OK;
                }))));
    }

    private boolean validateTerminalSource(CommandContext c) {
        return Command.validateCommandSource(c, CommandSources.TERMINAL);
    }

    private boolean validateDiscordOrTerminalSource(CommandContext c) {
        return Command.validateCommandSource(c, asList(CommandSources.TERMINAL, CommandSources.DISCORD));
    }

    @Override
    public void defaultEmbed(final Embed builder) {
        builder
            .addField("Account Type", authTypeToString(CONFIG.authentication.accountType))
            .addField("Username", CONFIG.authentication.username)
            .addField("Attempts", CONFIG.authentication.msaLoginAttemptsBeforeCacheWipe)
            .addField("Always Refresh On Login", toggleStr(CONFIG.authentication.alwaysRefreshOnLogin))
            .addField("Server Login Required", toggleStr(CONFIG.authentication.serverLoginRequired))
            .addField("Server Password", CONFIG.authentication.serverPassword.isEmpty() ? "Not Set" : "Set (hidden)")
            .addField("Mention", toggleStr(CONFIG.discord.mentionRoleOnDeviceCodeAuth))
            .addField("Open Browser", toggleStr(CONFIG.authentication.openBrowserOnLogin))
            .addField("Max Refresh Interval", CONFIG.authentication.maxRefreshIntervalMins + " minutes")
            .addField("Use Client Connection Proxy", toggleStr(CONFIG.authentication.useClientConnectionProxy))
            .addField("Chat Signing", toggleStr(CONFIG.client.chatSigning.enabled))
            .addField("Chat Signing Force", toggleStr(CONFIG.client.chatSigning.force))
            .addField("Chat Signing Commands", toggleStr(CONFIG.client.chatSigning.signCommands));
    }

    private String authTypeToString(Config.Authentication.AccountType type) {
        return switch (type) {
            case DEVICE_CODE -> "deviceCode";
            case MSA -> "emailAndPassword";
            case DEVICE_CODE_WITHOUT_DEVICE_TOKEN -> "deviceCode2";
            case PRISM -> "prism";
            case OFFLINE -> "offline";
        };
    }
}

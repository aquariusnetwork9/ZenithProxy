package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.module.api.Module;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

/**
 * Handles /login and /register for cracked servers like 6b6t.
 *
 * HOW TO USE:
 *   1. Set authentication.accountType to "offline" in config.json
 *   2. Set authentication.username to your bot's name
 *   3. Set authentication.serverPassword to your /login password
 *   4. Set authentication.serverLoginRequired to true
 *
 * HOW TO CUSTOMIZE:
 *   The strings this module looks for in chat messages (like "/register", "/login",
 *   "successfully") may need to be changed to match your server's exact messages.
 *
 *   To find out what your server sends:
 *     - Connect with this module enabled
 *     - Look at the log output for lines starting with [LOBBY DEBUG]
 *     - Those are the exact chat messages the server sends
 *     - Update the string checks below to match
 */
public class ServerLogin extends Module {
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean sentLoginCommand = new AtomicBoolean(false);

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(SystemChatEvent.class, this::handleSystemChat),
            of(ClientConnectEvent.class, this::handleConnect),
            of(ClientDisconnectEvent.class, this::handleDisconnect)
        );
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.authentication.serverLoginRequired;
    }

    /**
     * Reset state when we start a new connection
     */
    private void handleConnect(ClientConnectEvent event) {
        authenticated.set(false);
        sentLoginCommand.set(false);
        info("ServerLogin module ready, waiting for lobby auth prompt...");
    }

    /**
     * Reset state when we disconnect
     */
    private void handleDisconnect(ClientDisconnectEvent event) {
        authenticated.set(false);
        sentLoginCommand.set(false);
    }

    /**
     * Listen to every system chat message from the server.
     * When we see the login/register prompt, send the appropriate command.
     * When we see the success message, mark ourselves as online.
     *
     * =====================================================================
     * IMPORTANT: The strings checked below are GUESSES.
     * You MUST connect once, read the [LOBBY DEBUG] log lines,
     * and update these strings to match what 6b6t actually sends.
     * =====================================================================
     */
    private void handleSystemChat(SystemChatEvent event) {
        // Don't do anything if we already logged in
        if (authenticated.get()) return;

        // Get the server's chat message as plain text, lowercased for easy matching
        String msg = event.message().toLowerCase();

        // Check if the server is asking us to register (first time joining with this username)
        // Look for messages containing "/register"
        if (msg.contains("/register") && !sentLoginCommand.get()) {
            String pass = CONFIG.authentication.serverPassword;
            if (pass.isEmpty()) {
                error("Server requires /register but no serverPassword is set in config!");
                return;
            }
            info("Server requesting registration, sending /register command...");
            sentLoginCommand.set(true);
            // Wait a moment before sending to avoid being too fast
            EXECUTOR.schedule(() -> {
                sendClientPacketAsync(new ServerboundChatPacket("/register " + pass + " " + pass));
            }, 1, TimeUnit.SECONDS);
            return;
        }

        // Check if the server is asking us to log in (returning player)
        // Look for messages containing "/login"
        if (msg.contains("/login") && !sentLoginCommand.get()) {
            String pass = CONFIG.authentication.serverPassword;
            if (pass.isEmpty()) {
                error("Server requires /login but no serverPassword is set in config!");
                return;
            }
            info("Server requesting login, sending /login command...");
            sentLoginCommand.set(true);
            // Wait a moment before sending to avoid being too fast
            EXECUTOR.schedule(() -> {
                sendClientPacketAsync(new ServerboundChatPacket("/login " + pass));
            }, 1, TimeUnit.SECONDS);
            return;
        }

        // Check if the server confirmed we logged in successfully
        // Common success messages - update these to match your server
        if (sentLoginCommand.get() && (
                msg.contains("successfully logged in")
                || msg.contains("successfully registered")
                || msg.contains("authenticated")
                || msg.contains("has been registered")
                || msg.contains("logged in!"))) {
            info("Server login successful!");
            authenticated.set(true);

            // Now we can mark the session as online
            // This allows all other modules (AutoEat, KillAura, etc.) to start working
            var client = Proxy.getInstance().getClient();
            if (client != null && !client.isOnline()) {
                client.setOnline(true);
                EVENT_BUS.post(new ClientOnlineEvent());
            }
        }
    }
}

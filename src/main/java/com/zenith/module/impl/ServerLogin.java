package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.feature.player.*;
import com.zenith.mc.block.BlockPos;
import com.zenith.module.api.Module;
import com.zenith.util.math.MathHelper;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

/**
 * Handles /login and /register for cracked servers like 6b6t.
 * After successful login, walks forward ~15 blocks into the portal
 * to reach the main game server.
 *
 * HOW TO USE:
 *   1. Set authentication.accountType to "offline" in config or via Discord
 *   2. Set authentication.username to your bot's name
 *   3. Set authentication.serverPassword to your /login password
 *   4. Set authentication.serverLoginRequired to true
 *
 * STATES:
 *   WAITING        - Connected, waiting for login/register prompt
 *   AUTHENTICATING - Sent /login or /register, waiting for success message
 *   WALKING        - Login succeeded, walking toward portal
 *   DONE           - Through the portal, fully online
 */
public class ServerLogin extends Module {

    private enum State {
        WAITING,
        AUTHENTICATING,
        WALKING,
        DONE
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
    private boolean sentLoginCommand = false;

    // Walk phase variables
    private BlockPos walkGoal;
    private double spawnX, spawnZ;
    private int walkTicks = 0;
    // 10 seconds at 20tps — safety timeout in case portal detection fails
    private static final int MAX_WALK_TICKS = 200;
    // If position jumps more than this many blocks from spawn, we hit the portal
    private static final double PORTAL_TELEPORT_THRESHOLD = 20.0;
    // How many blocks to walk forward toward the portal
    private static final int WALK_DISTANCE = 15;

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(SystemChatEvent.class, this::handleSystemChat),
            of(ClientConnectEvent.class, this::handleConnect),
            of(ClientDisconnectEvent.class, this::handleDisconnect),
            of(ClientBotTick.class, this::handleTick)
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
        resetState();
        info("ServerLogin module ready, waiting for lobby auth prompt...");
    }

    /**
     * Reset state when we disconnect
     */
    private void handleDisconnect(ClientDisconnectEvent event) {
        resetState();
    }

    private void resetState() {
        state.set(State.WAITING);
        sentLoginCommand = false;
        walkGoal = null;
        walkTicks = 0;
    }

    /**
     * Listen to every system chat message from the server.
     * 6b6t sends: "Melon_kits, please login with the command: /login <password>"
     * After success, transitions to WALKING state.
     */
    private void handleSystemChat(SystemChatEvent event) {
        State currentState = state.get();
        if (currentState == State.DONE || currentState == State.WALKING) return;

        String msg = event.message().toLowerCase();

        // ===== LOGIN PROMPT =====
        // 6b6t: "<username>, please login with the command: /login <password>"
        if (currentState == State.WAITING
                && msg.contains("please login with the command")
                && !sentLoginCommand) {
            String pass = CONFIG.authentication.serverPassword;
            if (pass.isEmpty()) {
                error("Server requires /login but no serverPassword is set in config!");
                return;
            }
            info("Server requesting login, sending /login command...");
            sentLoginCommand = true;
            state.set(State.AUTHENTICATING);
            EXECUTOR.schedule(() -> {
                sendClientPacketAsync(new ServerboundChatPacket("/login " + pass));
            }, 1, TimeUnit.SECONDS);
            return;
        }

        // ===== REGISTER PROMPT =====
        if (currentState == State.WAITING
                && (msg.contains("please register") || msg.contains("/register"))
                && !sentLoginCommand) {
            String pass = CONFIG.authentication.serverPassword;
            if (pass.isEmpty()) {
                error("Server requires /register but no serverPassword is set in config!");
                return;
            }
            info("Server requesting registration, sending /register command...");
            sentLoginCommand = true;
            state.set(State.AUTHENTICATING);
            EXECUTOR.schedule(() -> {
                sendClientPacketAsync(new ServerboundChatPacket("/register " + pass + " " + pass));
            }, 1, TimeUnit.SECONDS);
            return;
        }

        // ===== SUCCESS MESSAGE =====
        if (currentState == State.AUTHENTICATING && (
                msg.contains("successfully logged in")
                || msg.contains("successfully registered")
                || msg.contains("successful")
                || msg.contains("authenticated")
                || msg.contains("has been registered")
                || msg.contains("logged in")
                || msg.contains("login successful"))) {
            info("Server login successful! Starting walk to portal...");
            startWalkPhase();
        }
    }

    /**
     * After login succeeds, record current position and calculate a walk goal
     * in the direction the bot is currently facing.
     */
    private void startWalkPhase() {
        state.set(State.WALKING);
        walkTicks = 0;

        // Record spawn position
        spawnX = World.getCurrentPlayerX();
        spawnZ = World.getCurrentPlayerZ();

        // Get the direction the bot is facing
        float yaw = CACHE.getPlayerCache().getYaw();

        // Calculate goal position WALK_DISTANCE blocks forward
        // Minecraft yaw: 0 = south (+Z), 90 = west (-X), 180 = north (-Z), 270 = east (+X)
        double rad = Math.toRadians(yaw);
        double goalX = spawnX - Math.sin(rad) * WALK_DISTANCE;
        double goalZ = spawnZ + Math.cos(rad) * WALK_DISTANCE;

        walkGoal = new BlockPos(
            MathHelper.floorI(goalX),
            MathHelper.floorI(World.getCurrentPlayerY()),
            MathHelper.floorI(goalZ)
        );

        info("Walking from ({}, {}) toward portal at ({}, {})",
            MathHelper.floorI(spawnX), MathHelper.floorI(spawnZ),
            walkGoal.x(), walkGoal.z());
    }

    /**
     * Each tick during the WALKING state:
     * - Walk toward the portal goal
     * - Check if we've been teleported (= portal detected)
     * - Safety timeout after MAX_WALK_TICKS
     */
    private void handleTick(ClientBotTick event) {
        if (state.get() != State.WALKING) return;
        if (walkGoal == null) return;

        walkTicks++;

        double currentX = World.getCurrentPlayerX();
        double currentZ = World.getCurrentPlayerZ();

        // Check if we were teleported far away from spawn (portal!)
        double distFromSpawn = Math.sqrt(
            Math.pow(currentX - spawnX, 2) +
            Math.pow(currentZ - spawnZ, 2)
        );

        // If position jumped way beyond our walk path, we went through the portal
        if (distFromSpawn > WALK_DISTANCE + PORTAL_TELEPORT_THRESHOLD) {
            info("Portal detected! Teleported to ({}, {}). Now fully online.",
                MathHelper.floorI(currentX), MathHelper.floorI(currentZ));
            finishLogin();
            return;
        }

        // Safety timeout
        if (walkTicks > MAX_WALK_TICKS) {
            info("Walk phase timed out after {} ticks. Marking as online.", walkTicks);
            finishLogin();
            return;
        }

        // Submit movement input: walk forward, facing the goal
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder()
                .pressingForward(true)
                .build())
            .yaw(RotationHelper.yawToXZ(walkGoal.x() + 0.5, walkGoal.z() + 0.5))
            .priority(1000)
            .build());
    }

    /**
     * Portal reached or timeout — mark the session as fully online
     * so all other modules (AutoEat, KillAura, etc.) start working.
     */
    private void finishLogin() {
        state.set(State.DONE);
        walkGoal = null;

        var client = Proxy.getInstance().getClient();
        if (client != null && !client.isOnline()) {
            client.setOnline(true);
            EVENT_BUS.post(new ClientOnlineEvent());
        }
        info("ServerLogin complete. Bot is fully online.");
    }
}

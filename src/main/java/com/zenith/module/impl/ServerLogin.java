package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.RotationHelper;
import com.zenith.feature.player.World;
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
 *
 * Flow:
 *   1. WAITING        - Waits for login/register prompt
 *   2. AUTHENTICATING - Sent /login, waiting for success
 *   3. WAITING_TELEPORT - Login succeeded, waiting for teleport to portal lobby
 *   4. WALKING        - Marked online, using proper physics to walk through portal
 *   5. DONE           - Through the portal (or walk finished)
 *
 * Key insight: we mark the bot as "online" BEFORE walking through the portal.
 * This starts ZenithProxy's full physics simulation (Bot ticks, INPUTS system),
 * which properly handles gravity, ground detection, and movement the server accepts.
 * Raw position packets get rejected by anti-cheat without physics simulation.
 */
public class ServerLogin extends Module {

    private enum State {
        WAITING,
        AUTHENTICATING,
        WAITING_TELEPORT,
        WALKING,
        DONE
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
    private boolean sentLoginCommand = false;

    // Walk phase
    private BlockPos walkGoal;
    private double startX, startZ;
    private int walkTicks = 0;

    // How far to walk toward the portal
    private static final int WALK_DISTANCE = 15;
    // If position jumps this far from walk start, portal was reached
    private static final double PORTAL_DETECT_DISTANCE = 30.0;
    // Max ticks to walk (20 seconds at 20tps)
    private static final int MAX_WALK_TICKS = 400;
    // Seconds to wait after login for teleport to portal lobby
    private static final int TELEPORT_WAIT_SECONDS = 4;

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

    private void handleConnect(ClientConnectEvent event) {
        resetState();
        info("ServerLogin module ready, waiting for lobby auth prompt...");
    }

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
     * Listen for lobby auth prompts and success messages.
     */
    private void handleSystemChat(SystemChatEvent event) {
        State currentState = state.get();
        if (currentState == State.DONE || currentState == State.WALKING || currentState == State.WAITING_TELEPORT) return;

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
        // 6b6t: "Melon_kits, you are now logged in! Please enter the server through the portal."
        if (currentState == State.AUTHENTICATING && (
                msg.contains("you are now logged in")
                || msg.contains("successfully logged in")
                || msg.contains("successfully registered")
                || msg.contains("has been registered")
                || msg.contains("logged in!"))) {
            info("Server login successful! Waiting {}s for teleport to portal lobby...", TELEPORT_WAIT_SECONDS);
            state.set(State.WAITING_TELEPORT);

            // 6b6t teleports to a portal lobby after login
            // Wait for that teleport, then mark online and start walking with proper physics
            EXECUTOR.schedule(this::startWalkPhase, TELEPORT_WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * After the teleport to the portal lobby:
     * 1. Mark bot as ONLINE (starts Bot ticks, INPUTS system, full physics simulation)
     * 2. Set a walk goal forward
     * 3. Walk using the INPUTS system (same as AntiAFK) which the server accepts
     */
    private void startWalkPhase() {
        // Read position AFTER the teleport to portal lobby
        startX = CACHE.getPlayerCache().getX();
        startZ = CACHE.getPlayerCache().getZ();
        float yaw = CACHE.getPlayerCache().getYaw();

        info("Portal lobby position: ({}, {}, {}), yaw: {}",
            String.format("%.1f", startX),
            String.format("%.1f", (double) CACHE.getPlayerCache().getY()),
            String.format("%.1f", startZ),
            String.format("%.1f", (double) yaw));

        // Calculate walk goal WALK_DISTANCE blocks forward from current position
        double rad = Math.toRadians(yaw);
        double goalX = startX - Math.sin(rad) * WALK_DISTANCE;
        double goalZ = startZ + Math.cos(rad) * WALK_DISTANCE;

        walkGoal = new BlockPos(
            MathHelper.floorI(goalX),
            MathHelper.floorI(CACHE.getPlayerCache().getY()),
            MathHelper.floorI(goalZ)
        );

        info("Walking toward portal at ({}, {})...", walkGoal.x(), walkGoal.z());

        // Mark bot as ONLINE — this starts Bot ticks and the INPUTS system
        // which provide full physics simulation (gravity, ground detection, etc.)
        // Raw position packets get rejected by anti-cheat without this
        var client = Proxy.getInstance().getClient();
        if (client != null && !client.isOnline()) {
            client.setOnline(true);
            EVENT_BUS.post(new ClientOnlineEvent());
            info("Bot marked online. Physics simulation started.");
        }

        // Now set state to WALKING — handleTick() will pick this up via ClientBotTick
        state.set(State.WALKING);
        walkTicks = 0;
    }

    /**
     * Each bot tick during WALKING state:
     * - Submit forward movement via INPUTS (proper physics)
     * - Check if portal teleported us
     */
    private void handleTick(ClientBotTick event) {
        if (state.get() != State.WALKING) return;
        if (walkGoal == null) return;

        walkTicks++;

        double currentX = World.getCurrentPlayerX();
        double currentZ = World.getCurrentPlayerZ();

        // Check if we were teleported far from where we started (portal!)
        double distFromStart = Math.sqrt(
            Math.pow(currentX - startX, 2) +
            Math.pow(currentZ - startZ, 2)
        );

        if (distFromStart > PORTAL_DETECT_DISTANCE) {
            info("Portal detected! Teleported to ({}, {}). Distance from start: {}",
                String.format("%.1f", currentX), String.format("%.1f", currentZ),
                String.format("%.1f", distFromStart));
            state.set(State.DONE);
            walkGoal = null;
            info("ServerLogin complete.");
            return;
        }

        // Check if we reached the walk goal
        int px = MathHelper.floorI(currentX);
        int pz = MathHelper.floorI(currentZ);
        if (px == walkGoal.x() && pz == walkGoal.z()) {
            info("Reached walk goal. Portal should have triggered.");
            state.set(State.DONE);
            walkGoal = null;
            info("ServerLogin complete.");
            return;
        }

        // Timeout
        if (walkTicks > MAX_WALK_TICKS) {
            info("Walk phase timed out after {} ticks. Continuing as online.", walkTicks);
            state.set(State.DONE);
            walkGoal = null;
            return;
        }

        // Submit movement: walk forward toward the goal using proper physics
        // This is the same system AntiAFK uses — the server accepts this movement
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder()
                .pressingForward(true)
                .build())
            .yaw(RotationHelper.yawToXZ(walkGoal.x() + 0.5, walkGoal.z() + 0.5))
            .priority(10000) // Very high priority — override AntiAFK etc.
            .build());
    }
}

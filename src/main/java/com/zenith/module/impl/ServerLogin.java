package com.zenith.module.impl;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.module.api.Module;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;

/**
 * Handles /login and /register for cracked servers like 6b6t.
 *
 * Flow:
 *   1. WAITING      - Waits for login/register prompt
 *   2. AUTHENTICATING - Sent /login, waiting for success
 *   3. WAITING_TELEPORT - Login succeeded, waiting for 6b6t to teleport us to the portal lobby
 *   4. WALKING      - Walking forward toward the portal using raw position packets
 *   5. DONE         - Through the portal, fully online
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
    private ScheduledFuture<?> walkTask;
    private double walkX, walkY, walkZ;     // current walk position
    private double walkDirX, walkDirZ;      // direction to walk (unit vector)
    private float walkYaw, walkPitch;       // facing direction
    private double startX, startZ;          // position when walk started
    private int walkTicks = 0;

    // Walking speed: ~4.3 blocks/sec = ~0.215 blocks/tick at 20tps
    private static final double WALK_SPEED = 0.215;
    // Max blocks to walk before giving up
    private static final int MAX_WALK_DISTANCE = 20;
    // If position jumps more than this from walk start, portal detected
    private static final double PORTAL_DETECT_DISTANCE = 30.0;
    // Max ticks to walk (15 seconds)
    private static final int MAX_WALK_TICKS = 300;
    // Seconds to wait after login for teleport to portal lobby
    private static final int TELEPORT_WAIT_SECONDS = 4;

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
        walkTicks = 0;
        stopWalkTask();
    }

    private void stopWalkTask() {
        if (walkTask != null && !walkTask.isDone()) {
            walkTask.cancel(false);
            walkTask = null;
        }
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
        if (currentState == State.AUTHENTICATING && (
                msg.contains("successfully logged in")
                || msg.contains("successfully registered")
                || msg.contains("successful")
                || msg.contains("authenticated")
                || msg.contains("has been registered")
                || msg.contains("logged in")
                || msg.contains("login successful"))) {
            info("Server login successful! Waiting {}s for teleport to portal lobby...", TELEPORT_WAIT_SECONDS);
            state.set(State.WAITING_TELEPORT);

            // 6b6t teleports us to a second lobby after login
            // Wait for that teleport to complete, then start walking
            EXECUTOR.schedule(this::startWalkPhase, TELEPORT_WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * After the teleport to the portal lobby, read current position
     * and start walking forward using raw position packets.
     */
    private void startWalkPhase() {
        state.set(State.WALKING);
        walkTicks = 0;

        // Read position AFTER the teleport
        walkX = CACHE.getPlayerCache().getX();
        walkY = CACHE.getPlayerCache().getY();
        walkZ = CACHE.getPlayerCache().getZ();
        walkYaw = CACHE.getPlayerCache().getYaw();
        walkPitch = 0.0f; // look straight ahead

        startX = walkX;
        startZ = walkZ;

        // Calculate forward direction from yaw
        double rad = Math.toRadians(walkYaw);
        walkDirX = -Math.sin(rad);
        walkDirZ = Math.cos(rad);

        info("Portal lobby position: ({}, {}, {}), yaw: {}", 
            String.format("%.1f", walkX), String.format("%.1f", walkY), String.format("%.1f", walkZ),
            String.format("%.1f", (double) walkYaw));
        info("Walking forward {} blocks toward portal...", MAX_WALK_DISTANCE);

        // Schedule walk ticks at 50ms intervals (20 ticks/sec) to match MC tick rate
        walkTask = EXECUTOR.scheduleAtFixedRate(this::walkTick, 0, 50, TimeUnit.MILLISECONDS);
    }

    /**
     * Each walk tick:
     * - Move position forward slightly
     * - Send position packet to server
     * - Check if we've been teleported (portal)
     */
    private void walkTick() {
        try {
            if (state.get() != State.WALKING) {
                stopWalkTask();
                return;
            }

            walkTicks++;

            // Check if server teleported us (portal detection)
            double serverX = CACHE.getPlayerCache().getX();
            double serverZ = CACHE.getPlayerCache().getZ();
            double distFromStart = Math.sqrt(
                Math.pow(serverX - startX, 2) +
                Math.pow(serverZ - startZ, 2)
            );

            if (distFromStart > PORTAL_DETECT_DISTANCE) {
                info("Portal detected! Server moved us to ({}, {}). Distance from start: {}",
                    String.format("%.1f", serverX), String.format("%.1f", serverZ),
                    String.format("%.1f", distFromStart));
                finishLogin();
                return;
            }

            // Check walk distance limit
            double walkedDistance = Math.sqrt(
                Math.pow(walkX - startX, 2) +
                Math.pow(walkZ - startZ, 2)
            );

            if (walkedDistance >= MAX_WALK_DISTANCE) {
                info("Reached max walk distance ({}). Checking if portal was missed...", MAX_WALK_DISTANCE);
                // Give a few more seconds for the server to process
                if (walkTicks > MAX_WALK_TICKS) {
                    info("Walk timeout. Marking as online anyway.");
                    finishLogin();
                    return;
                }
                // Stop moving forward but keep checking for teleport
                return;
            }

            // Safety timeout
            if (walkTicks > MAX_WALK_TICKS) {
                info("Walk phase timed out after {} ticks. Marking as online.", walkTicks);
                finishLogin();
                return;
            }

            // Move forward
            walkX += walkDirX * WALK_SPEED;
            walkZ += walkDirZ * WALK_SPEED;

            // Send position to server
            sendClientPacketAsync(new ServerboundMovePlayerPosRotPacket(
                true,   // onGround
                false,  // horizontalCollision
                walkX,
                walkY,
                walkZ,
                walkYaw,
                walkPitch
            ));

        } catch (Exception e) {
            error("Error during walk tick: {}", e.getMessage());
            finishLogin();
        }
    }

    /**
     * Portal reached or timeout — mark the session as fully online.
     */
    private void finishLogin() {
        stopWalkTask();
        state.set(State.DONE);

        var client = Proxy.getInstance().getClient();
        if (client != null && !client.isOnline()) {
            client.setOnline(true);
            EVENT_BUS.post(new ClientOnlineEvent());
        }
        info("ServerLogin complete. Bot is fully online.");
    }
}

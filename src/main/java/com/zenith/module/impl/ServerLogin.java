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
 *
 * After the first successful auth + portal walk, sets a static flag (authComplete)
 * so that subsequent connections (e.g. after a server transfer through the portal)
 * auto-online immediately without re-authenticating.
 *
 * LoginHandler and TabListDataHandler check isAuthComplete() to decide whether
 * to block or allow auto-online.
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

    /**
     * Once auth + portal walk complete, this flag stays true for the lifetime
     * of the process. Subsequent connections (from server transfers) will
     * auto-online via LoginHandler/TabListDataHandler checking this flag.
     */
    private static volatile boolean authComplete = false;

    /**
     * Called by LoginHandler and TabListDataHandler.
     * If true, serverLoginRequired is bypassed and auto-online is allowed.
     */
    public static boolean isAuthComplete() {
        return authComplete;
    }

    // Walk phase
    private BlockPos walkGoal;
    private double startX, startZ;
    private int walkTicks = 0;

    private static final int WALK_DISTANCE = 15;
    private static final double PORTAL_DETECT_DISTANCE = 30.0;
    private static final int MAX_WALK_TICKS = 400;
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
        if (authComplete) {
            state.set(State.DONE);
            sentLoginCommand = false;
            walkGoal = null;
            walkTicks = 0;
            info("Auth already completed. Skipping login for this connection.");
            return;
        }
        resetState();
        info("ServerLogin module ready, waiting for lobby auth prompt...");
    }

    private void handleDisconnect(ClientDisconnectEvent event) {
        // Don't reset authComplete — it persists across transfers
        state.set(State.WAITING);
        sentLoginCommand = false;
        walkGoal = null;
        walkTicks = 0;
    }

    private void resetState() {
        state.set(State.WAITING);
        sentLoginCommand = false;
        walkGoal = null;
        walkTicks = 0;
    }

    private void handleSystemChat(SystemChatEvent event) {
        State currentState = state.get();
        if (currentState == State.DONE || currentState == State.WALKING || currentState == State.WAITING_TELEPORT) return;

        String msg = event.message().toLowerCase();

        // ===== LOGIN PROMPT =====
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
                msg.contains("you are now logged in")
                || msg.contains("successfully logged in")
                || msg.contains("successfully registered")
                || msg.contains("has been registered")
                || msg.contains("logged in!"))) {
            info("Server login successful! Waiting {}s for teleport to portal lobby...", TELEPORT_WAIT_SECONDS);
            state.set(State.WAITING_TELEPORT);
            EXECUTOR.schedule(this::startWalkPhase, TELEPORT_WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void startWalkPhase() {
        // Auth is verified at this point. Set the flag NOW so that if a server
        // transfer happens during the walk (before completeAuth runs),
        // the new connection will auto-online via LoginHandler/TabListDataHandler.
        authComplete = true;

        startX = CACHE.getPlayerCache().getX();
        startZ = CACHE.getPlayerCache().getZ();
        float yaw = CACHE.getPlayerCache().getYaw();

        info("Portal lobby position: ({}, {}, {}), yaw: {}",
            String.format("%.1f", startX),
            String.format("%.1f", (double) CACHE.getPlayerCache().getY()),
            String.format("%.1f", startZ),
            String.format("%.1f", (double) yaw));

        double rad = Math.toRadians(yaw);
        double goalX = startX - Math.sin(rad) * WALK_DISTANCE;
        double goalZ = startZ + Math.cos(rad) * WALK_DISTANCE;

        walkGoal = new BlockPos(
            MathHelper.floorI(goalX),
            MathHelper.floorI(CACHE.getPlayerCache().getY()),
            MathHelper.floorI(goalZ)
        );

        info("Walking toward portal at ({}, {})...", walkGoal.x(), walkGoal.z());

        // Mark online to start physics simulation
        var client = Proxy.getInstance().getClient();
        if (client != null && !client.isOnline()) {
            client.setOnline(true);
            EVENT_BUS.post(new ClientOnlineEvent());
            info("Bot marked online. Physics simulation started.");
        }

        state.set(State.WALKING);
        walkTicks = 0;
    }

    private void handleTick(ClientBotTick event) {
        if (state.get() != State.WALKING) return;
        if (walkGoal == null) return;

        walkTicks++;

        double currentX = World.getCurrentPlayerX();
        double currentZ = World.getCurrentPlayerZ();

        double distFromStart = Math.sqrt(
            Math.pow(currentX - startX, 2) +
            Math.pow(currentZ - startZ, 2)
        );

        if (distFromStart > PORTAL_DETECT_DISTANCE) {
            info("Portal detected! Teleported to ({}, {}). Distance: {}",
                String.format("%.1f", currentX), String.format("%.1f", currentZ),
                String.format("%.1f", distFromStart));
            completeAuth();
            return;
        }

        int px = MathHelper.floorI(currentX);
        int pz = MathHelper.floorI(currentZ);
        if (px == walkGoal.x() && pz == walkGoal.z()) {
            info("Reached walk goal.");
            completeAuth();
            return;
        }

        if (walkTicks > MAX_WALK_TICKS) {
            info("Walk phase timed out. Completing auth anyway.");
            completeAuth();
            return;
        }

        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder()
                .pressingForward(true)
                .build())
            .yaw(RotationHelper.yawToXZ(walkGoal.x() + 0.5, walkGoal.z() + 0.5))
            .priority(10000)
            .build());
    }

    /**
     * Auth + portal walk complete. Set the static flag so that any future
     * connections (server transfers) will auto-online immediately.
     */
    private void completeAuth() {
        state.set(State.DONE);
        walkGoal = null;
        authComplete = true;
        info("ServerLogin complete. Auth flag set for future connections.");
    }
}

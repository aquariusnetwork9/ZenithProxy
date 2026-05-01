package com.zenith.network.client.handler.incoming;

import com.zenith.Proxy;
import com.zenith.event.chat.DeathMessageChatEvent;
import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.client.ClientDeathMessageEvent;
import com.zenith.event.queue.QueueSkipEvent;
import com.zenith.feature.chatschema.ChatSchemaParser;
import com.zenith.feature.deathmessages.DeathMessageParseResult;
import com.zenith.feature.deathmessages.DeathMessagesParser;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.ClientEventLoopPacketHandler;
import com.zenith.util.ComponentSerializer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.Optional;

import static com.zenith.Globals.*;
import static java.util.Objects.nonNull;

public class SystemChatHandler implements ClientEventLoopPacketHandler<ClientboundSystemChatPacket, ClientSession> {
    private static final TextColor DEATH_MSG_COLOR_2b2t = NamedTextColor.DARK_AQUA;

    @Override
    public boolean applyAsync(@NonNull ClientboundSystemChatPacket packet, @NonNull ClientSession session) {
        try {
            if (packet.isOverlay()) {
                return true; // skip action bar text
            }
            logSystemChat(packet);
            final Component component = packet.getContent();
            String messageString = ComponentSerializer.serializePlain(component);

            // DEBUG: Log all system chat messages to help identify lobby/auth prompts
            // Remove this line after you've captured the 6b6t lobby strings
            CLIENT_LOG.info("[LOBBY DEBUG] {}", messageString);
            if (Proxy.getInstance().isOn2b2t()) {
                if ("Reconnecting to server 2b2t.".equals(messageString)
                    && NamedTextColor.GOLD.equals(component.style().color())) {
                    EVENT_BUS.postAsync(QueueSkipEvent.INSTANCE);
                }

                var deathMessageParseResult = parseDeathMessage2b2t(component, messageString);
                if (deathMessageParseResult.isPresent()) {
                    EVENT_BUS.postAsync(new DeathMessageChatEvent(deathMessageParseResult.get(), component, messageString));
                    return true;
                }
            }
            var chatParseResult = ChatSchemaParser.parse(messageString);
            if (chatParseResult != null) {
                switch (chatParseResult.type()) {
                    case PUBLIC_CHAT -> {
                        EVENT_BUS.postAsync(new PublicChatEvent(chatParseResult.sender(), component, chatParseResult.messageContent()));
                    }
                    case WHISPER_INBOUND -> {
                        EVENT_BUS.postAsync(new WhisperChatEvent(
                            false,
                            chatParseResult.sender(),
                            chatParseResult.receiver(),
                            component,
                            chatParseResult.messageContent()
                        ));
                    }
                    case WHISPER_OUTBOUND -> {
                        EVENT_BUS.postAsync(new WhisperChatEvent(
                            true,
                            chatParseResult.sender(),
                            chatParseResult.receiver(),
                            component,
                            chatParseResult.messageContent()
                        ));
                    }
                }
            } else {
                EVENT_BUS.postAsync(new SystemChatEvent(component, messageString));
            }
        } catch (final Exception e) {
            CLIENT_LOG.error("Caught exception in ChatHandler. Packet: {}", packet, e);
        }
        return true;
    }

    private static void logSystemChat(final @NotNull ClientboundSystemChatPacket packet) {
        if (!CONFIG.client.extra.logChatMessages) return;
        var component = packet.getContent();
        if (Proxy.getInstance().isInQueue()) {
            if (CONFIG.client.extra.logOnlyQueuePositionUpdates) return;
            // strip empty lines spam in 2b2t queue messages
            component = component.replaceText(b -> b
                .matchLiteral("\n\n")
                .replacement("")
            );
        }
        CHAT_LOG.info(component);
    }

    private Optional<DeathMessageParseResult> parseDeathMessage2b2t(final Component component, final String messageString) {
        if (component.children().stream().anyMatch(child -> nonNull(child.color())
            && Objects.equals(child.color(), DEATH_MSG_COLOR_2b2t))) { // death message color on 2b
            var deathMessage = DeathMessagesParser.INSTANCE.parse(component, messageString);
            if (deathMessage.isPresent()) {
                if (deathMessage.get().victim().equals(CACHE.getProfileCache().getProfile().getName())) {
                    EVENT_BUS.postAsync(new ClientDeathMessageEvent(messageString));
                }
                return deathMessage;
            } else {
                CLIENT_LOG.warn("Failed to parse death message: {}", messageString);
            }
        }
        return Optional.empty();
    }
}

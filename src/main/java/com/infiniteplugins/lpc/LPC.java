package com.infiniteplugins.lpc;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

@Plugin(
        id = "azurechat",
        name = "AzureChat",
        version = "1.3.2"
)
public class LPC {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private LuckPerms luckPerms;

    // Plugin messaging channel for placeholder requests/responses
    private static final ChannelIdentifier PLACEHOLDERS_CHANNEL = MinecraftChannelIdentifier.create("azurechat", "placeholders");

    private final AtomicLong requestCounter = new AtomicLong(0);
    private final Map<Long, CompletableFuture<String>> pendingPlaceholderResponses = new ConcurrentHashMap<>();
    private final Pattern placeholderPattern = Pattern.compile("%[^%]+%");

    private CommentedConfigurationNode config;
    private YamlConfigurationLoader configLoader;

    // Tracks which backend server each player was last known to be on,
    // so we can send the correct "leave" message when they disconnect from the proxy entirely.
    private final Map<UUID, RegisteredServer> lastKnownServer = new ConcurrentHashMap<>();

    @Inject
    public LPC(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();

        try {
            this.luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            logger.error("LuckPerms not found! AzureChat requires LuckPerms to function.");
            return;
        }

        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("azurechat").build();
        commandManager.register(meta, new LPCCommand());

        CommandMeta msgMeta = commandManager.metaBuilder("msg")
                .aliases("tell", "w", "whisper")
                .build();
        commandManager.register(msgMeta, new MsgCommand());

        server.getChannelRegistrar().register(PLACEHOLDERS_CHANNEL);
        logger.info("AzureChat (LPC for Velocity) has been enabled.");
    }

    private void loadConfig() {
        if (!Files.exists(dataDirectory)) {
            try {
                Files.createDirectories(dataDirectory);
            } catch (IOException e) {
                logger.error("Failed to create plugin directory", e);
            }
        }

        Path configFile = dataDirectory.resolve("config.yml");
        if (!Files.exists(configFile)) {
            try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                if (in != null) {
                    Files.copy(in, configFile);
                }
            } catch (IOException e) {
                logger.error("Failed to save default config", e);
            }
        }

        configLoader = YamlConfigurationLoader.builder().path(configFile).build();
        try {
            config = configLoader.load();
        } catch (IOException e) {
            logger.error("Failed to load config.yml", e);
        }
    }

    // ----------------------------------------------------------------------------
    // Shared placeholder resolution, used by chat AND join/leave messages
    // ----------------------------------------------------------------------------
    private String resolvePlaceholders(String format, Player player, CachedMetaData metaData,
                                        String serverName, String message) {
        String prefix = metaData.getPrefix() != null ? metaData.getPrefix() : "";
        String suffix = metaData.getSuffix() != null ? metaData.getSuffix() : "";

        String prefixes = metaData.getPrefixes().values().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining(""));
        String suffixes = metaData.getSuffixes().values().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining(""));

        String usernameColor = metaData.getMetaValue("username-color");
        String messageColor = metaData.getMetaValue("message-color");

        String result = format
                .replace("{prefix}", prefix)
                .replace("{suffix}", suffix)
                .replace("{prefixes}", prefixes)
                .replace("{suffixes}", suffixes)
                .replace("{username-color}", usernameColor != null ? usernameColor : "")
                .replace("{message-color}", messageColor != null ? messageColor : "")
                .replace("{name}", player.getUsername())
                // Velocity has no native nickname/displayname system; falls back to username.
                .replace("{displayname}", player.getUsername())
                // Velocity has no concept of Minecraft "worlds"; use the backend server name instead.
                .replace("{world}", serverName != null ? serverName : "");

        if (message != null) {
            result = result.replace("{message}", message);
        }

        return result;
    }

    private String getChatFormat(String group, String serverName) {
        CommentedConfigurationNode serverChatFormats = config.node("server-chat-formats");
        CommentedConfigurationNode serverNode = serverChatFormats.node("servers", serverName != null ? serverName : "");
        CommentedConfigurationNode defaultNode = serverChatFormats.node("default");

        if (serverName != null) {
            String format = serverNode.node("group-formats", group).getString();
            if (format != null) {
                return format;
            }

            format = serverNode.node("chat-format").getString();
            if (format != null) {
                return format;
            }
        }

        String format = defaultNode.node("group-formats", group).getString();
        if (format != null) {
            return format;
        }

        return defaultNode.node("chat-format").getString("{prefix}{name}&r: {message}");
    }

    private void broadcastToServer(RegisteredServer target, Component message) {
        for (Player p : target.getPlayersConnected()) {
            p.sendMessage(message);
        }
    }

    private boolean isAzureStaffMuted(Player player) {
        Optional<PluginContainer> container = server.getPluginManager().getPlugin("azurestaff");
        if (container.isEmpty()) {
            return false;
        }

        Optional<?> instance = container.get().getInstance();
        if (instance.isEmpty()) {
            return false;
        }

        try {
            Object api = instance.get();
            Method isMuted = api.getClass().getMethod("isMuted", UUID.class);
            Object result = isMuted.invoke(api, player.getUniqueId());
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException e) {
            logger.warn("AzureStaff API is available but could not be used to check mute status", e);
            return false;
        }
    }

    // ----------------------------------------------------------------------------
    // Chat: now scoped per-server instead of proxy-wide
    // ----------------------------------------------------------------------------
    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isAzureStaffMuted(player)) {
            event.setResult(PlayerChatEvent.ChatResult.denied());
            return;
        }

        String message = event.getMessage();

        CachedMetaData metaData = this.luckPerms.getPlayerAdapter(Player.class).getMetaData(player);
        String group = metaData.getPrimaryGroup();

        Optional<ServerConnection> currentServer = player.getCurrentServer();
        String serverName = currentServer.map(sc -> sc.getServerInfo().getName()).orElse(null);

        String format = getChatFormat(group, serverName);
        String resolved = resolvePlaceholders(format, player, metaData, serverName, message);
        Component finalMessage = LEGACY.deserialize(resolved);

        boolean perServerChat = config.node("per-server-chat").getBoolean(true);

        // If running on Velocity and the message contains PlaceholderAPI-style placeholders
        // forward them to the backend Paper server to be resolved there.
        boolean containsPlaceholders = placeholderPattern.matcher(resolved).find();

        if (perServerChat && currentServer.isPresent() && containsPlaceholders) {
            // Asynchronously request placeholder resolution from the backend server.
            ServerConnection conn = currentServer.get();

            long requestId = requestCounter.incrementAndGet();
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingPlaceholderResponses.put(requestId, future);

            // Build request payload: [long requestId][utf playerUuid][utf text]
            try (ByteArrayOutputStream bout = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bout)) {
                out.writeLong(requestId);
                out.writeUTF(player.getUniqueId().toString());
                out.writeUTF(resolved);
                boolean success = conn.sendPluginMessage(PLACEHOLDERS_CHANNEL, bout.toByteArray());
                if (!success) {
                    logger.warn("Failed to send placeholder request to backend: plugin message not accepted.");
                    pendingPlaceholderResponses.remove(requestId);
                    future.complete(resolved);
                }
            } catch (IOException e) {
                logger.warn("Failed to send placeholder request to backend: " + e.getMessage());
                pendingPlaceholderResponses.remove(requestId);
                future.complete(resolved);
            }

            // Timeout handling: complete with original text if backend doesn't respond.
            long timeoutMs = config.node("placeholder-timeout-ms").getLong(2000);
            server.getScheduler().buildTask(this, () -> {
                CompletableFuture<String> f = pendingPlaceholderResponses.remove(requestId);
                if (f != null && !f.isDone()) {
                    f.complete(resolved);
                }
            }).delay(timeoutMs, TimeUnit.MILLISECONDS).schedule();

            // When response arrives (or timeout), broadcast on proxy thread.
            future.whenComplete((resolvedText, ex) -> {
                String toUse = resolvedText != null ? resolvedText : resolved;
                server.getScheduler().buildTask(this, () -> {
                    Component msgComp = LEGACY.deserialize(toUse);
                    broadcastToServer(conn.getServer(), msgComp);
                }).schedule();
            });
        } else if (perServerChat && currentServer.isPresent()) {
            broadcastToServer(currentServer.get().getServer(), finalMessage);
        } else {
            // Fallback: proxy-wide broadcast (old behavior), used if per-server-chat is disabled
            // or the player isn't connected to any backend server yet.
            server.getAllPlayers().forEach(p -> p.sendMessage(finalMessage));
        }

        // Deny the original event so it doesn't get sent to the backend server
        event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(PLACEHOLDERS_CHANNEL)) return;

        byte[] data = event.getData();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            long requestId = in.readLong();
            String resolved = in.readUTF();

            CompletableFuture<String> f = pendingPlaceholderResponses.remove(requestId);
            if (f != null) {
                f.complete(resolved);
            }
        } catch (IOException e) {
            logger.warn("Failed to read placeholder response: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------------
    // Join / Leave messages, configurable per backend server
    // ----------------------------------------------------------------------------
    private void sendServerLifecycleMessage(RegisteredServer targetServer, Player player, String type) {
        String serverName = targetServer.getServerInfo().getName();

        CommentedConfigurationNode serverMessages = config.node("server-messages");
        CommentedConfigurationNode serverNode = serverMessages.node("servers", serverName);
        CommentedConfigurationNode defaultNode = serverMessages.node("default");

        boolean enabled = serverNode.node(type + "-enabled")
                .getBoolean(defaultNode.node(type + "-enabled").getBoolean(false));

        if (!enabled) {
            return;
        }

        String message = serverNode.node(type + "-message").getString(
                defaultNode.node(type + "-message").getString(""));

        if (message == null || message.isEmpty()) {
            return;
        }

        CachedMetaData metaData = this.luckPerms.getPlayerAdapter(Player.class).getMetaData(player);
        String resolved = resolvePlaceholders(message, player, metaData, serverName, null);
        Component finalMessage = LEGACY.deserialize(resolved);

        broadcastToServer(targetServer, finalMessage);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        RegisteredServer newServer = event.getServer();
        Optional<RegisteredServer> previousServer = event.getPreviousServer();

        // Player switched from one backend server to another: leave message on the old one.
        previousServer.ifPresent(prev -> sendServerLifecycleMessage(prev, player, "leave"));

        long joinDelay = Math.max(0, config.node("join-message-delay-ms").getLong(500));
        server.getScheduler().buildTask(this, () -> {
            Optional<ServerConnection> current = player.getCurrentServer();
            if (current.isPresent() && current.get().getServer().equals(newServer)) {
                sendServerLifecycleMessage(newServer, player, "join");
            }
        }).delay(joinDelay, TimeUnit.MILLISECONDS).schedule();

        lastKnownServer.put(player.getUniqueId(), newServer);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer lastServer = lastKnownServer.remove(player.getUniqueId());

        if (lastServer != null) {
            sendServerLifecycleMessage(lastServer, player, "leave");
        }
    }


    // ----------------------------------------------------------------------------
    // Private messages (/msg): only available when per-server-chat is enabled.
    // ----------------------------------------------------------------------------

    private String getMsgFormat(String key, String fallback) {
        return config.node("msg", key).getString(fallback);
    }

    private String resolveMsgPlaceholders(String format, Player sender, Player target, String message) {
        return format
                .replace("{sender}", sender.getUsername())
                .replace("{sender-displayname}", sender.getUsername())
                .replace("{receiver}", target.getUsername())
                .replace("{receiver-displayname}", target.getUsername())
                .replace("{message}", message);
    }

    private void playMsgSound(Player player) {
        boolean enabled = config.node("msg", "sound", "enabled").getBoolean(true);
        if (!enabled) {
            return;
        }

        String soundName = config.node("msg", "sound", "sound")
                .getString("minecraft:block.note_block.pling");
        float volume = (float) config.node("msg", "sound", "volume").getDouble(1.0);
        float pitch = (float) config.node("msg", "sound", "pitch").getDouble(1.0);

        try {
            Sound sound = Sound.sound(
                    net.kyori.adventure.key.Key.key(soundName),
                    Sound.Source.MASTER,
                    volume,
                    pitch
            );
            player.playSound(sound);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid /msg sound '{}'. Sound will not be played.", soundName);
        }
    }

    private class MsgCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!(invocation.source() instanceof Player sender)) {
                invocation.source().sendMessage(
                        MiniMessage.miniMessage().deserialize("<red>/msg can only be used by players.")
                );
                return;
            }

            boolean perServerChat = config.node("per-server-chat").getBoolean(true);
            if (!perServerChat) {
                sender.sendMessage(LEGACY.deserialize(
                        getMsgFormat("disabled-message",
                                "&c/msg is disabled because per-server-chat is disabled.")
                ));
                return;
            }

            String[] args = invocation.arguments();
            if (args.length < 2) {
                sender.sendMessage(LEGACY.deserialize(
                        getMsgFormat("usage", "&cUsage: /msg <player> <message>")
                ));
                return;
            }

            Optional<ServerConnection> senderConnection = sender.getCurrentServer();
            if (senderConnection.isEmpty()) {
                sender.sendMessage(LEGACY.deserialize(
                        getMsgFormat("no-server-message", "&cYou must be connected to a server to use /msg.")
                ));
                return;
            }

            RegisteredServer senderServer = senderConnection.get().getServer();

            String targetName = args[0];
            Optional<Player> targetOptional = server.getPlayer(targetName);
            if (targetOptional.isEmpty()) {
                sender.sendMessage(LEGACY.deserialize(
                        getMsgFormat("player-not-found-message",
                                "&cThat player could not be found.")
                ));
                return;
            }

            Player target = targetOptional.get();
            Optional<ServerConnection> targetConnection = target.getCurrentServer();

            if (targetConnection.isEmpty()
                    || !targetConnection.get().getServer().equals(senderServer)) {
                sender.sendMessage(LEGACY.deserialize(
                        getMsgFormat("not-on-server-message",
                                "&cThat player is not on your current server.")
                ));
                return;
            }

            String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            if (message.isBlank()) {
                sender.sendMessage(LEGACY.deserialize(
                        getMsgFormat("usage", "&cUsage: /msg <player> <message>")
                ));
                return;
            }

            String senderFormat = getMsgFormat("format-sender",
                    "&d[MSG] &fYou &7-> &f{receiver}&7: &r{message}");
            String receiverFormat = getMsgFormat("format-receiver",
                    "&d[MSG] &f{sender} &7-> &fYou&7: &r{message}");

            Component senderMessage = LEGACY.deserialize(
                    resolveMsgPlaceholders(senderFormat, sender, target, message)
            );
            Component receiverMessage = LEGACY.deserialize(
                    resolveMsgPlaceholders(receiverFormat, sender, target, message)
            );

            sender.sendMessage(senderMessage);
            target.sendMessage(receiverMessage);
            playMsgSound(target);
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length <= 1) {
                if (!(invocation.source() instanceof Player sender)) {
                    return List.of();
                }

                Optional<ServerConnection> current = sender.getCurrentServer();
                if (current.isEmpty()
                        || !config.node("per-server-chat").getBoolean(true)) {
                    return List.of();
                }

                String prefix = args.length == 0 ? "" : args[0].toLowerCase();
                return current.get().getServer().getPlayersConnected().stream()
                        .map(Player::getUsername)
                        .filter(name -> !name.equalsIgnoreCase(sender.getUsername()))
                        .filter(name -> name.toLowerCase().startsWith(prefix))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(Collectors.toList());
            }

            return List.of();
        }
    }

    private class LPCCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (invocation.source().hasPermission("azurechat.reload")) {
                    loadConfig();
                    invocation.source().sendMessage(MiniMessage.miniMessage().deserialize("<green>AzureChat has been reloaded."));
                } else {
                    invocation.source().sendMessage(MiniMessage.miniMessage().deserialize("<red>No permission."));
                }
            }
            // Add clear and debug if needed...
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return List.of("reload", "clear", "debug");
        }
    }
}

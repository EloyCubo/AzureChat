package com.infiniteplugins.lpc;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.miniplaceholders.api.MiniPlaceholders;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Plugin(
        id = "lpc",
        name = "AzureChat",
        version = "3.7.2",
        description = "A fork of LPC for Velocity and features for AzureMC",
        authors = {"EloyCubo"}
)
public class LPC {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private LuckPerms luckPerms;
    
    private CommentedConfigurationNode config;
    private YamlConfigurationLoader configLoader;

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
        CommandMeta meta = commandManager.metaBuilder("lpc").build();
        commandManager.register(meta, new LPCCommand());

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

    @Subscribe
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        
        CachedMetaData metaData = this.luckPerms.getPlayerAdapter(Player.class).getMetaData(player);
        String group = metaData.getPrimaryGroup();
        
        CommentedConfigurationNode formatsNode = config.node("group-formats");
        String format = formatsNode.node(group).getString();
        
        if (format == null) {
            format = config.node("chat-format").getString("{prefix}{name}&r: {message}");
        }

        String prefix = metaData.getPrefix() != null ? metaData.getPrefix() : "";
        String suffix = metaData.getSuffix() != null ? metaData.getSuffix() : "";
        
        // Translate legacy codes for LuckPerms prefix/suffix if they use &
        format = format.replace("{prefix}", prefix.replace("&", "<"))
                       .replace("{suffix}", suffix.replace("&", "<"))
                       .replace("{name}", player.getUsername())
                       .replace("{message}", message);

        // Convert standard legacy & color codes to MiniMessage tags for the rest
        format = format.replace("&", "<").replace(">", ">");

        TagResolver placeholders = MiniPlaceholders.getAudienceGlobalPlaceholders(player);
        Component finalMessage = MiniMessage.miniMessage().deserialize(format, placeholders);
        
        // Broadcast to everyone on the proxy
        server.sendMessage(finalMessage);
        
        // Deny the original event so it doesn't get sent to the backend server
        event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    private class LPCCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (invocation.source().hasPermission("lpc.reload")) {
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

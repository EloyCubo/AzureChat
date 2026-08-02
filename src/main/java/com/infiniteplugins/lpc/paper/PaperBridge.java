package com.infiniteplugins.lpc.paper;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

public class PaperBridge extends JavaPlugin implements PluginMessageListener {

    private static final String CHANNEL = "azurechat:placeholders";

    @Override
    public void onEnable() {
        // Only register plugin messaging channel; do not create files, commands or listeners.
        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
    }

    @Override
    public void onDisable() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this, CHANNEL);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            long requestId = in.readLong();
            String playerUuid = in.readUTF();
            String text = in.readUTF();

            Player target = null;
            try {
                UUID uuid = UUID.fromString(playerUuid);
                target = Bukkit.getPlayer(uuid);
            } catch (Exception ignored) {}

            String result;
            if (target != null) {
                result = PlaceholderAPI.setPlaceholders(target, text);
            } else {
                // No player context available; resolve with no player if API allows
                result = PlaceholderAPI.setPlaceholders(null, text);
            }

            // Send response back using the same player connection
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bout)) {
                out.writeLong(requestId);
                out.writeUTF(result != null ? result : "");
                player.sendPluginMessage(this, CHANNEL, bout.toByteArray());
            }

        } catch (IOException e) {
            getLogger().warning("Failed to handle placeholder request: " + e.getMessage());
        }
    }
}

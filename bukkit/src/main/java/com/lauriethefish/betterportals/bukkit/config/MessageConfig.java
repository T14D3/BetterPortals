package com.lauriethefish.betterportals.bukkit.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.lauriethefish.betterportals.bukkit.command.framework.CommandException;
import com.lauriethefish.betterportals.shared.logging.Logger;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Singleton
public class MessageConfig {
    private static final String PORTAL_WAND_TAG = "portalWand";

    private final Logger logger;
    private final Map<String, Component> messageMap = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final NamespacedKey key;

    private Component portalWandName;
    @Getter private Component prefix;
    @Getter private Component messageColor;

    private ItemStack portalWand = null;

    @Inject
    public MessageConfig(Logger logger) {
        this.logger = logger;
        key = new NamespacedKey("betterportals", PORTAL_WAND_TAG.toLowerCase(Locale.ROOT));
    }

    public void load(FileConfiguration file) {
        ConfigurationSection messagesSection = Objects.requireNonNull(file.getConfigurationSection("chatMessages"), "Missing chat messages section");

        for(String key : messagesSection.getKeys(false)) {
            messageMap.put(key, parseMessage(messagesSection.getString(key)));
        }

        portalWandName = parseMessage(Objects.requireNonNull(file.getString("portalWandName"), "Missing portalWandName"));
        prefix = getRawMessage("prefix");
        messageColor = parseMessage(Objects.requireNonNull(messagesSection.getString("messageColor"), "Missing messageColor"));
    }

    private Component parseMessage(String message) {
        try {
            return miniMessage.deserialize(message);
        } catch(Exception ex) {
            logger.warning("Failed to parse MiniMessage: %s", message);
            return miniMessage.deserialize("<red>Invalid message</red>");
        }
    }

    /**
     * @return The wand with the NBT tags for creating portals
     */
    public @NotNull ItemStack getPortalWand() {
        if(portalWand == null) {
            portalWand = new ItemStack(Material.BLAZE_ROD);
            portalWand.editMeta(meta -> {
                meta.displayName(portalWandName);
                meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
            });
        }

        return portalWand;
    }

    /**
     * Checks if <code>item</code> is a portal wand
     * @param item The item to test
     * @return true if it is a valid portal wand, false otherwise
     */
    public boolean isPortalWand(ItemStack item) {
        return item.hasItemMeta()
                && Boolean.TRUE.equals(item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BOOLEAN));
    }

    /**
     * Finds a chat message with the plugin prefix.
     * @param name The name in the config
     * @return A chat message with the configured plugin prefix
     */
    public Component getChatMessage(String name) {
        return prefix.append(getRawMessage(name));
    }

    /**
     * Finds a chat message without the prefix, for boxing in a {@link CommandException}
     * @param name The name in the config
     * @return A chat message without the prefix.
     */
    public Component getErrorMessage(String name) {
        return getRawMessage(name);
    }

    /**
     * Returns a yellow message for warnings in chat.
     * @param name The name in the config
     * @return The yellow formatted message
     */
    public Component getWarningMessage(String name) {
        Component rawMessage = getRawMessage(name);
        if(rawMessage == null) return Component.empty();
        return Component.text().color(net.kyori.adventure.text.format.NamedTextColor.YELLOW).append(rawMessage).build();
    }

    /**
     * Finds a chat message without the prefix.
     * @param name The name in the config
     * @return A chat message without the prefix.
     */
    public Component getRawMessage(String name) {
        return messageMap.get(name);
    }

    public String getString(String name) {
        return LegacyComponentSerializer.legacySection().serialize(getRawMessage(name));
    }
}

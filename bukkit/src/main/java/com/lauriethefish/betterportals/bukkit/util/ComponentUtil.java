package com.lauriethefish.betterportals.bukkit.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public class ComponentUtil {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Helper to apply MiniMessage placeholders / tag resolvers to a Component template.
     * The MessageConfig is expected to return a Component that can be serialized by MiniMessage.
     *
     * Example:
     *   Component template = messageConfig.getErrorMessage("noWorldExistsWithGivenName");
     *   Component resolved = applyPlaceholders(template, Placeholder.parsed("name", worldName));
     *
     * This method serializes the template (via MiniMessage), then deserializes with the provided resolvers.
     */
    public static Component applyPlaceholders(Component template, TagResolver... resolvers) {
        String serialized = MINI.serialize(template);
        if (resolvers == null || resolvers.length == 0) {
            return MINI.deserialize(serialized);
        }
        return MINI.deserialize(serialized, TagResolver.resolver(resolvers));
    }
}

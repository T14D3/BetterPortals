package com.lauriethefish.betterportals.bukkit.command.framework;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class CommandException extends Exception {
    public CommandException(String message) {
        super(message);
    }

    public CommandException(Component message) {
        super(LegacyComponentSerializer.legacySection().serialize(message));
    }

    public CommandException(Component message, Throwable cause) {
        super(LegacyComponentSerializer.legacySection().serialize(message), cause);
    }

    public CommandException(Throwable cause) {
        super(cause);
    }

    public CommandException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.itemguard.lite;

import com.itemguard.ItemGuard;
import org.bukkit.command.PluginCommand;

/** Separate entry point: shared tracking engine, restricted read-only investigation commands. */
public final class ItemGuardLite extends ItemGuard {
    private LiteCommand commands;

    @Override public boolean isLiteEdition() { return true; }

    @Override protected void registerCommands() {
        commands = new LiteCommand(this);
        PluginCommand command = java.util.Objects.requireNonNull(getCommand("itemguard"));
        command.setExecutor(commands);
        command.setTabCompleter(commands);
        getServer().getPluginManager().registerEvents(commands, this);
    }

    @Override public void onDisable() {
        if (commands != null) commands.close();
        super.onDisable();
    }
}

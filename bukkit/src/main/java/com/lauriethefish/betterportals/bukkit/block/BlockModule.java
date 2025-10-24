package com.lauriethefish.betterportals.bukkit.block;

import com.google.inject.AbstractModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.lauriethefish.betterportals.bukkit.block.bukkit.BukkitBlockMapModule;
import com.lauriethefish.betterportals.bukkit.block.external.BlockChangeWatcher;
import com.lauriethefish.betterportals.bukkit.block.external.ExternalBlockWatcherManager;
import com.lauriethefish.betterportals.bukkit.block.external.IBlockChangeWatcher;
import com.lauriethefish.betterportals.bukkit.block.external.IExternalBlockWatcherManager;
import com.lauriethefish.betterportals.bukkit.block.lighting.DummyLightDataManager;
import com.lauriethefish.betterportals.bukkit.block.lighting.ILightDataManager;
import com.lauriethefish.betterportals.bukkit.block.lighting.LightDataManger;
import com.lauriethefish.betterportals.bukkit.player.view.ViewFactory;
import com.lauriethefish.betterportals.bukkit.player.view.block.IPlayerBlockView;
import com.lauriethefish.betterportals.bukkit.player.view.block.PlayerBlockView;
import com.lauriethefish.betterportals.bukkit.player.view.entity.IPlayerEntityView;
import com.lauriethefish.betterportals.bukkit.player.view.entity.PlayerEntityView;

public class BlockModule extends AbstractModule {

    @Override
    public void configure() {
        install(new FactoryModuleBuilder()
                .implement(IBlockChangeWatcher.class, BlockChangeWatcher.class)
                .build(IBlockChangeWatcher.Factory.class)
        );

        install(new FactoryModuleBuilder()
                .implement(IPlayerBlockView.class, PlayerBlockView.class)
                .implement(IPlayerEntityView.class, PlayerEntityView.class)
                .build(ViewFactory.class)
        );

        bind(IExternalBlockWatcherManager.class).to(ExternalBlockWatcherManager.class);

        try {
            Class.forName("org.bukkit.block.data.type.Light");
            bind(ILightDataManager.class).to(LightDataManger.class);
        } catch (ClassNotFoundException ignored) {
            bind(ILightDataManager.class).to(DummyLightDataManager.class);
        }

        // If using direct NMS, then alternative block map implementations are used
        install(new BukkitBlockMapModule());
    }
}

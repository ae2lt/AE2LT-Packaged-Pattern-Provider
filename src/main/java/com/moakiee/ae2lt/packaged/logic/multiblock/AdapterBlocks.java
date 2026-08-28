package com.moakiee.ae2lt.packaged.logic.multiblock;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Cached adapter helper for looking up a {@link Block}'s {@link ResourceLocation}.
 * Multiblock adapters compare their target block's id against a static RL on every
 * push, so the original {@code BuiltInRegistries.BLOCK.getKey(state.getBlock())}
 * call showed up as a hot allocation. This caches the per-Block lookup; the
 * WeakHashMap ensures unloaded mods' blocks can be GC'd.
 */
public final class AdapterBlocks {
    private static final Map<Block, ResourceLocation> ID_CACHE = new WeakHashMap<>();

    private AdapterBlocks() {
    }

    public static ResourceLocation idOf(BlockState state) {
        return idOf(state.getBlock());
    }

    public static ResourceLocation idOf(Block block) {
        if (block == null) {
            return null;
        }
        ResourceLocation cached;
        synchronized (ID_CACHE) {
            cached = ID_CACHE.get(block);
        }
        if (cached != null) {
            return cached;
        }
        var fresh = BuiltInRegistries.BLOCK.getKey(block);
        synchronized (ID_CACHE) {
            // Re-check inside the lock to avoid clobbering a concurrent put.
            cached = ID_CACHE.get(block);
            if (cached != null) {
                return cached;
            }
            ID_CACHE.put(block, fresh);
        }
        return fresh;
    }
}

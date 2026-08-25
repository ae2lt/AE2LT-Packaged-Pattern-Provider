package com.moakiee.ae2lt.packaged.logic.multiblock;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;

/**
 * Block capability lookup for Forge 1.20.1.
 *
 * <p>NeoForge exposes {@code Level#getCapability(BlockCapability, pos, side)},
 * which resolves through the block itself. Forge 1.20.1 has no level-level
 * entry point: capabilities live on the block entity and come back wrapped in a
 * {@code LazyOptional}. Adapters need the same "give me the handler or null"
 * shape in both worlds, so that difference is isolated here.
 */
public final class BlockCapabilities {

    private BlockCapabilities() {
    }

    /**
     * @return the capability instance exposed at {@code pos} on {@code side}, or
     *         {@code null} when the chunk is unloaded, there is no block entity,
     *         or the block entity does not expose that capability.
     */
    @Nullable
    public static <T> T find(
            Level level, BlockPos pos, Capability<T> capability, @Nullable Direction side) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        return blockEntity.getCapability(capability, side).resolve().orElse(null);
    }
}

package com.moakiee.ae2lt.packaged.logic.multiblock.binding;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.WirelessConnection;

/**
 * Identity of a candidate dispatch target for a packaged pattern.
 * Faces (NORMAL mode) and wireless connections (WIRELESS mode) flow through
 * the same scheduler via this sealed type.
 */
public sealed interface LaneKey permits LaneKey.FaceLane, LaneKey.ConnLane {

    /** True when this lane lives in the provider's own level. */
    boolean isLocal();

    /** The dimension this lane resolves to. */
    ResourceKey<Level> dimension(ResourceKey<Level> providerDimension);

    /** The block position this lane resolves to. */
    BlockPos position(BlockPos providerPos);

    /** The bound face on the target machine (for wireless connections); null for face lanes. */
    @Nullable
    Direction boundFace();

    record FaceLane(Direction face) implements LaneKey {
        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public ResourceKey<Level> dimension(ResourceKey<Level> providerDimension) {
            return providerDimension;
        }

        @Override
        public BlockPos position(BlockPos providerPos) {
            return providerPos.relative(face);
        }

        @Override
        @Nullable
        public Direction boundFace() {
            return null;
        }
    }

    record ConnLane(WirelessConnection connection) implements LaneKey {
        @Override
        public boolean isLocal() {
            return false;
        }

        @Override
        public ResourceKey<Level> dimension(ResourceKey<Level> providerDimension) {
            return connection.dimension();
        }

        @Override
        public BlockPos position(BlockPos providerPos) {
            return connection.pos();
        }

        @Override
        public Direction boundFace() {
            return connection.boundFace();
        }
    }
}

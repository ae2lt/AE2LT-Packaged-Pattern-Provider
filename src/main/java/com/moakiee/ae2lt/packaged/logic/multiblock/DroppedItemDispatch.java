package com.moakiee.ae2lt.packaged.logic.multiblock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;

public final class DroppedItemDispatch {

    private DroppedItemDispatch() {
    }

    public static Vec3 dropPosition(BlockPos origin, Direction facing) {
        var pos = DroppedItemGeometry.dropPosition(toGeometry(origin), toGeometry(facing));
        return new Vec3(pos.x(), pos.y(), pos.z());
    }

    public static BlockPos dropBlock(BlockPos origin, Direction facing) {
        var pos = DroppedItemGeometry.inputDropBlock(toGeometry(origin), toGeometry(facing));
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    public static AABB dropAabb(BlockPos origin, Direction facing) {
        var box = DroppedItemGeometry.inputDropAabb(toGeometry(origin), toGeometry(facing));
        return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    public static AABB beamAabb(BlockPos origin, BlockPos hitBlock, Direction facing) {
        var box = DroppedItemGeometry.beamAabb(toGeometry(origin), toGeometry(hitBlock), toGeometry(facing));
        return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
    }

    public static boolean spawnInput(ServerLevel level, Vec3 position, ItemStack stack, String marker) {
        if (stack.isEmpty()) {
            return false;
        }

        var entity = new ItemEntity(level, position.x, position.y, position.z, stack.copy());
        entity.setDeltaMovement(0, 0, 0);
        entity.setPickUpDelay(20);
        if (marker != null && !marker.isBlank()) {
            entity.getPersistentData().putBoolean(marker, true);
        }
        return level.addFreshEntity(entity);
    }

    public static List<GenericStack> collectOutputs(ServerLevel level,
                                                    AABB bounds,
                                                    AllowedOutputFilter filter,
                                                    Predicate<ItemEntity> entityPredicate) {
        var outputs = new ArrayList<GenericStack>();
        for (var entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (!entity.isAlive() || !entityPredicate.test(entity)) {
                continue;
            }

            var stack = entity.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            var key = AEItemKey.of(stack);
            if (!filter.matches(key)) {
                continue;
            }

            outputs.add(new GenericStack(key, stack.getCount()));
            entity.discard();
        }
        return List.copyOf(outputs);
    }

    private static DroppedItemGeometry.BlockPos3 toGeometry(BlockPos pos) {
        return new DroppedItemGeometry.BlockPos3(pos.getX(), pos.getY(), pos.getZ());
    }

    private static DroppedItemGeometry.AxisDirection toGeometry(Direction direction) {
        return switch (direction) {
            case DOWN -> DroppedItemGeometry.AxisDirection.DOWN;
            case UP -> DroppedItemGeometry.AxisDirection.UP;
            case NORTH -> DroppedItemGeometry.AxisDirection.NORTH;
            case SOUTH -> DroppedItemGeometry.AxisDirection.SOUTH;
            case WEST -> DroppedItemGeometry.AxisDirection.WEST;
            case EAST -> DroppedItemGeometry.AxisDirection.EAST;
        };
    }
}

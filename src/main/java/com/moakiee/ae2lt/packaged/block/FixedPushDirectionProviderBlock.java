package com.moakiee.ae2lt.packaged.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

import appeng.block.AEBaseEntityBlock;
import appeng.block.crafting.PatternProviderBlock;
import appeng.block.crafting.PushDirection;
import appeng.menu.locator.MenuLocators;
import appeng.util.InteractionUtil;

import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity;

class FixedPushDirectionProviderBlock<T extends StablePatternProviderBlockEntity>
        extends AEBaseEntityBlock<T> {

    FixedPushDirectionProviderBlock() {
        super(metalProps().forceSolidOn());
        registerDefaultState(defaultBlockState().setValue(
                PatternProviderBlock.PUSH_DIRECTION, PushDirection.ALL));
    }

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(PatternProviderBlock.PUSH_DIRECTION);
    }

    public void setSide(Level level, BlockPos pos, Direction facing) {
        forceAllPushDirection(level, pos);
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos,
                                Block block, BlockPos fromPos, boolean isMoving) {
        forceAllPushDirection(level, pos);
        var blockEntity = getBlockEntity(level, pos);
        if (blockEntity != null) {
            blockEntity.getLogic().updateRedstoneState();
            blockEntity.onNeighborChanged();
        }
    }

    /**
     * 1.20.1 routes every block interaction through AE2's single
     * {@code onActivated} hook, so wrench rotation and menu opening share one
     * override here. Mirrors {@link PatternProviderBlock#onActivated}.
     */
    @Override
    public InteractionResult onActivated(
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            @Nullable ItemStack heldItem,
            BlockHitResult hit) {
        if (InteractionUtil.isInAlternateUseMode(player)) {
            return InteractionResult.PASS;
        }

        if (heldItem != null && InteractionUtil.canWrenchRotate(heldItem)) {
            setSide(level, pos, hit.getDirection());
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        var blockEntity = getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            blockEntity.openMenu(player, MenuLocators.forBlockEntity(blockEntity));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    private void forceAllPushDirection(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!state.hasProperty(PatternProviderBlock.PUSH_DIRECTION)
                || state.getValue(PatternProviderBlock.PUSH_DIRECTION) == PushDirection.ALL) {
            return;
        }

        level.setBlockAndUpdate(pos, state.setValue(PatternProviderBlock.PUSH_DIRECTION, PushDirection.ALL));
        var be = getBlockEntity(level, pos);
        if (be != null) {
            be.onNeighborChanged();
        }
    }
}

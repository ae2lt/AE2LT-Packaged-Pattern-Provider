package com.moakiee.ae2lt.packaged.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import com.moakiee.ae2lt.item.OverloadedWirelessConnectorItem;
import com.moakiee.ae2lt.logic.WirelessConnectorTargetHelper;
import com.moakiee.ae2lt.network.WirelessConnectorUsePacket;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapterRegistry;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity;

@Mixin(value = WirelessConnectorUsePacket.class, remap = false)
public abstract class WirelessConnectorPacketMixin {
    @Shadow public abstract BlockPos pos();
    @Shadow public abstract InteractionHand hand();
    @Shadow private void handleProviderConnection(ServerPlayer player, Level level, ItemStack stack) {}

    @Inject(method = "handleOnServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            remap = true), cancellable = true)
    private void ae2ltpp$blockOnlyTarget(ServerPlayer player, CallbackInfo callback) {
        // This point is after AE2LT's loaded-position, held-item and player-reach guards.
        var level = player.serverLevel();
        var stack = player.getItemInHand(hand());
        if (level.getBlockEntity(pos()) != null
                || !OverloadedWirelessConnectorItem.hasSelection(stack)
                || !OverloadedWirelessConnectorItem.HOST_PROVIDER.equals(
                        OverloadedWirelessConnectorItem.getSelectedHostType(stack))
                || !OverloadedWirelessConnectorItem.isSelectionInCurrentDimension(level, stack)
                || !(OverloadedWirelessConnectorItem.getSelectedProvider(level, stack)
                        instanceof StablePatternProviderBlockEntity provider)
                || !provider.isWirelessProvider()
                || MultiblockAdapterRegistry.find(level, pos(), null) == null) {
            return;
        }
        handleProviderConnection(player, level, stack);
        callback.cancel();
    }

    @Redirect(method = "handleProviderConnection", at = @At(value = "INVOKE",
            target = "Lcom/moakiee/ae2lt/logic/WirelessConnectorTargetHelper;collectTargets(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Z)Ljava/util/Set;"))
    private Set<BlockPos> ae2ltpp$collectBlockOnlyTarget(Level level, BlockPos pos, boolean contiguous,
            ServerPlayer player, Level callerLevel, ItemStack stack) {
        if (OverloadedWirelessConnectorItem.getSelectedProvider(level, stack)
                    instanceof StablePatternProviderBlockEntity provider && provider.isWirelessProvider()
                && level instanceof ServerLevel serverLevel && level.isLoaded(pos)
                && level.getBlockEntity(pos) == null
                && MultiblockAdapterRegistry.find(serverLevel, pos, null) != null) {
            // No BE type exists for AE2LT's contiguous collector; bind the clicked block only.
            return Set.of(pos.immutable());
        }
        return WirelessConnectorTargetHelper.collectTargets(level, pos, contiguous);
    }
}

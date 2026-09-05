package com.moakiee.ae2lt.packaged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;

import com.moakiee.ae2lt.item.OverloadedWirelessConnectorItem;
import com.moakiee.ae2lt.network.NetworkInit;
import com.moakiee.ae2lt.network.WirelessConnectorUsePacket;
import com.moakiee.ae2lt.packaged.client.WirelessConnectorBlockOnlyTarget;

@Mixin(value = OverloadedWirelessConnectorItem.class, remap = false)
public abstract class WirelessConnectorItemMixin {
    @Inject(method = "handleBlockUse", at = @At("HEAD"), cancellable = true)
    private void ae2ltpp$blockOnlyClick(UseOnContext context,
            CallbackInfoReturnable<InteractionResult> callback) {
        var level = context.getLevel();
        var pos = context.getClickedPos();
        if (!level.isClientSide() || context.getPlayer() == null || !level.isLoaded(pos)) {
            return;
        }
        var stack = context.getItemInHand();
        boolean providerSelected = OverloadedWirelessConnectorItem.hasSelection(stack)
                && OverloadedWirelessConnectorItem.HOST_PROVIDER.equals(
                        OverloadedWirelessConnectorItem.getSelectedHostType(stack))
                && OverloadedWirelessConnectorItem.isSelectionInCurrentDimension(level, stack);
        if (!WirelessConnectorBlockOnlyTarget.shouldSubmit(providerSelected,
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()),
                level.getBlockEntity(pos) != null)) {
            return;
        }
        // Selection metadata survives client chunk unloads; only the server can verify the PP host.
        NetworkInit.sendToServer(new WirelessConnectorUsePacket(
                context.getHand(), pos, context.getClickedFace(),
                net.minecraft.client.gui.screens.Screen.hasControlDown()));
        callback.setReturnValue(InteractionResult.sidedSuccess(true));
    }
}

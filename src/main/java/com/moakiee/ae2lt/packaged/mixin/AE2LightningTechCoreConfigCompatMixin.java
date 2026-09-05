package com.moakiee.ae2lt.packaged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Bridges the removed beta4 Thunderbolt method used by the 1.20.1 AE2LT port. */
@Mixin(targets = "com.moakiee.ae2lt." + "AE2LightningTech", remap = false)
public abstract class AE2LightningTechCoreConfigCompatMixin {
    @Redirect(
            method = "commonSetup",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/moakiee/thunderbolt/CoreConfig;requireChannelMaxFlow()V"),
            require = 0)
    private void ae2ltpp$ignoreRemovedChannelMaxFlowRequirement() {
        // Thunderbolt beta4 applies its current channel policy without this declaration.
    }
}

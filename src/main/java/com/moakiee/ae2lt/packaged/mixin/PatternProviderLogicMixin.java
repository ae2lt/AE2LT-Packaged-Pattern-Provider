package com.moakiee.ae2lt.packaged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.stacks.GenericStack;
import appeng.helpers.patternprovider.PatternProviderLogic;

import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderLogic;

/**
 * PP-owned bridge for overload-aware lock-until-result matching.
 */
@Mixin(PatternProviderLogic.class)
public abstract class PatternProviderLogicMixin {
    @Inject(
            method = "onStackReturnedToNetwork",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private void ae2ltpp$handleReturnedOverloadOutput(
            GenericStack stack, CallbackInfo callback) {
        if ((Object) this instanceof StablePatternProviderLogic logic
                && logic.handleReturnedStack(stack)) {
            callback.cancel();
        }
    }
}

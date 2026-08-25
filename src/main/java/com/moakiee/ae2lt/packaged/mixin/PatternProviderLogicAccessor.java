package com.moakiee.ae2lt.packaged.mixin;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import appeng.util.inv.AppEngInternalInventory;

/**
 * The single AE2-private bridge owned by PP's provider implementation.
 *
 * <p>Every member targeted here belongs to AE2, not to Minecraft, so all
 * accessors and invokers opt out of SRG remapping.
 */
@Mixin(PatternProviderLogic.class)
public interface PatternProviderLogicAccessor {
    @Invoker(value = "onPushPatternSuccess", remap = false)
    void ae2ltpp$onPushPatternSuccess(IPatternDetails pattern);

    @Invoker(value = "doWork", remap = false)
    boolean ae2ltpp$doWork();

    @Invoker(value = "hasWorkToDo", remap = false)
    boolean ae2ltpp$hasWorkToDo();

    @Accessor(value = "patternInventory", remap = false)
    AppEngInternalInventory ae2ltpp$getPatternInventory();

    @Mutable
    @Accessor(value = "returnInv", remap = false)
    void ae2ltpp$setReturnInventory(PatternProviderReturnInventory inventory);

    @Accessor(value = "patterns", remap = false)
    List<IPatternDetails> ae2ltpp$getPatterns();

    @Accessor(value = "patternInputs", remap = false)
    Set<AEKey> ae2ltpp$getPatternInputs();

    @Accessor(value = "unlockStack", remap = false)
    void ae2ltpp$setUnlockStack(appeng.api.stacks.GenericStack unlockStack);
}

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
 */
@Mixin(PatternProviderLogic.class)
public interface PatternProviderLogicAccessor {
    @Invoker("onPushPatternSuccess")
    void ae2ltpp$onPushPatternSuccess(IPatternDetails pattern);

    @Invoker("doWork")
    boolean ae2ltpp$doWork();

    @Invoker("hasWorkToDo")
    boolean ae2ltpp$hasWorkToDo();

    @Accessor("patternInventory")
    AppEngInternalInventory ae2ltpp$getPatternInventory();

    @Mutable
    @Accessor("returnInv")
    void ae2ltpp$setReturnInventory(PatternProviderReturnInventory inventory);

    @Accessor("patterns")
    List<IPatternDetails> ae2ltpp$getPatterns();

    @Accessor("patternInputs")
    Set<AEKey> ae2ltpp$getPatternInputs();

    @Accessor("unlockStack")
    void ae2ltpp$setUnlockStack(appeng.api.stacks.GenericStack unlockStack);
}

package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;

import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchExecutor;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.testsupport.MinecraftTestBootstrap;

class TerraPlateAdapterInsertionTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void simulationDoesNotSpawnAndModulationHonorsSpawnResult() {
        var stack = new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2);
        var spawnCalls = new AtomicLong();
        var rejecting = TerraPlateAdapter.terraInserter(() -> {
            spawnCalls.incrementAndGet();
            return false;
        });

        assertEquals(2L, rejecting.apply(stack, Actionable.SIMULATE).longValue());
        assertEquals(0, spawnCalls.get());
        assertEquals(0L, rejecting.apply(stack, Actionable.MODULATE).longValue());
        assertEquals(1, spawnCalls.get());
        assertEquals(2L, TerraPlateAdapter.terraInserter(() -> true)
                .apply(stack, Actionable.MODULATE).longValue());
    }

    @Test
    void firstSpawnRejectionLeavesInputsWithCaller() {
        var spawnCalls = new AtomicLong();
        var commitCalls = new AtomicLong();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });
        var residuals = new ArrayList<GenericStack>();
        var plan = new DispatchPlan(List.of(
                target(1, () -> {
                    spawnCalls.incrementAndGet();
                    return false;
                }),
                target(2, () -> {
                    spawnCalls.incrementAndGet();
                    return true;
                })), commitCalls::incrementAndGet);

        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.failure());
        assertEquals(1, spawnCalls.get());
        assertEquals(0, commitCalls.get());
        assertEquals(0, returnInventory.getAmount(0));
        assertTrue(residuals.isEmpty());
    }

    @Test
    void laterSpawnRejectionRetainsRejectedAndUnattemptedInputsOnly() {
        var spawnCalls = new AtomicLong();
        var spawnedAmount = new AtomicLong();
        var commitCalls = new AtomicLong();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });
        var residuals = new ArrayList<GenericStack>();
        var plan = new DispatchPlan(List.of(
                target(1, () -> {
                    spawnCalls.incrementAndGet();
                    spawnedAmount.incrementAndGet();
                    return true;
                }),
                target(2, () -> {
                    spawnCalls.incrementAndGet();
                    return false;
                }),
                target(3, () -> {
                    spawnCalls.incrementAndGet();
                    spawnedAmount.addAndGet(3);
                    return true;
                })), commitCalls::incrementAndGet);

        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.success());
        assertEquals(2, spawnCalls.get());
        assertEquals(1, spawnedAmount.get());
        assertEquals(0, commitCalls.get());
        assertEquals(5, returnInventory.getAmount(0));
        assertEquals(AEItemKey.of(Items.IRON_INGOT), returnInventory.getKey(0));
        assertTrue(residuals.isEmpty());
        assertEquals(6, spawnedAmount.get() + returnInventory.getAmount(0));
    }

    private static TargetSlot target(long amount, BooleanSupplier spawnInput) {
        return new TargetSlot(null, BlockPos.ZERO, null,
                List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), amount)),
                InsertionStrategy.CUSTOM, TerraPlateAdapter.terraInserter(spawnInput));
    }
}

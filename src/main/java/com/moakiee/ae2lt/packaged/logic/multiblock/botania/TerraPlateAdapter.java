package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Real-mode adapter for the Terrestrial Agglomeration Plate &mdash; the
 * Botania multi-block that fuses Terrasteel from Manasteel ingots,
 * Mana Pearl, and Mana Diamond while consuming a fixed amount of mana
 * from the surrounding mana sources.
 *
 * <p>The plate has a genuine in-world delay (mana must be accumulated
 * before the output spawns) and produces an {@link ItemEntity} when
 * done, so it can't be virtualised faithfully without inventing mana.
 * We therefore route it through the REAL dispatch path:
 *
 * <ol>
 *   <li>{@link #bind}: locate the matching {@code TerrestrialAgglomerationRecipe}
 *       by pattern output.</li>
 *   <li>{@link #canDispatch}: plate must be idle <i>and</i> its 1x1x1
 *       inhabit volume must contain no {@link ItemEntity} &mdash;
 *       Botania's {@code tryStartProcessing} has a latch tick between
 *       "items dropped" and "manaToGet set", so the bare
 *       {@code terraIsIdle} would happily re-dispatch into a plate
 *       that already has ingredients waiting to be picked up.</li>
 *   <li>{@link #planWithBinding}: spawn each ingredient as an
 *       {@link ItemEntity} <i>inside</i> the plate's own 1x1x1 AABB
 *       (Botania scans {@code new AABB(BlockPos)} = [Y, Y+1) for
 *       inputs &mdash; spawning higher than that is invisible to
 *       {@code tryStartProcessing}). The entity is marked with a
 *       persistent tag so {@link #extractOutputs} can ignore stray
 *       items from other automation.</li>
 *   <li>{@link #extractOutputs}: once the plate goes idle again, sweep
 *       the air column above it for the recipe's result item and pull
 *       it back into the provider. The scan starts at the plate's
 *       <i>own</i> Y level &mdash; Botania spawns the output at
 *       {@code pos.Y + 0.2} (the plate is only 0.1875 blocks tall),
 *       so anything above {@code pos.Y + 0.5} would already be
 *       outside the search window.</li>
 * </ol>
 *
 * <p>Batch policy: K=1 only. Each push commits one terra plate cycle;
 * cycles cannot overlap because the plate only holds one recipe state
 * at a time.
 */
public final class TerraPlateAdapter implements MultiblockAdapter {

    private static final String INPUT_MARKER = "ae2ltpp.botania.terra_plate_input";
    private static final double EXTRACT_RADIUS = 2.5d;
    // Plate is 0.1875 blocks tall; Botania spawns its product at
    // pos.Y + 0.2 (just above the visible top). We mirror that so the
    // dropped inputs sit on the plate exactly where the player would
    // have tossed them, which is also inside the plate's 1x1x1 input
    // scan AABB.
    private static final double SPAWN_Y_OFFSET = 0.2d;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        return BotaniaReflection.isTerraPlate(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.BOTANIA_TERRA_PLATE;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (!recognizesMain(level, mainPos, be)) {
            return null;
        }
        var holder = BotaniaRecipeLookup.findTerraPlateByOutput(level, pattern);
        if (holder == null) {
            return null;
        }
        // Enforce K=1 at bind time: pattern.amount must equal the recipe's
        // own result count (one terra plate cycle = one craft).
        var result = holder.getResultItem(level.registryAccess());
        if (result == null || result.isEmpty()) {
            return null;
        }
        if (BotaniaRecipeLookup.patternPrimaryOutputAmount(pattern) != result.getCount()) {
            return null;
        }
        return new BindingResult(new TerraBindHandle(holder), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof TerraBindHandle)) {
            return false;
        }
        var be = level.getBlockEntity(mainPos);
        if (!BotaniaReflection.isTerraPlate(be) || !BotaniaReflection.terraIsIdle(be)) {
            return false;
        }
        // Block re-dispatch while items are still sitting on the plate
        // waiting for tryStartProcessing to latch them. terraIsIdle
        // only inspects mana/manaToGet which stay at 0 for at least one
        // server tick after a dispatch &mdash; the bare check would
        // happily keep stacking duplicates onto a plate that's about
        // to start its first cycle.
        return !plateHasItemEntities(level, mainPos);
    }

    private static boolean plateHasItemEntities(ServerLevel level, BlockPos mainPos) {
        var plateAabb = new AABB(mainPos);
        var items = level.getEntitiesOfClass(ItemEntity.class, plateAabb);
        for (var entity : items) {
            if (entity.isAlive() && !entity.getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof TerraBindHandle bind)) {
            return null;
        }
        var be = level.getBlockEntity(mainPos);
        if (!BotaniaReflection.isTerraPlate(be) || !BotaniaReflection.terraIsIdle(be)) {
            return null;
        }

        var recipe = bind.holder();
        var ingredients = recipe.getIngredients();
        var assignment = pickKeyCounterStacks(ingredients, inputs);
        if (assignment == null || assignment.isEmpty()) {
            return null;
        }

        // Spawn inputs inside the plate's own 1x1x1 AABB &mdash;
        // tryStartProcessing's getItemEntities() scans new AABB(pos),
        // which is [Y, Y+1). Using mainPos.above() (Y+1.5) would
        // place the items above that range and the plate would
        // never see them.
        var spawnPos = new Vec3(
                mainPos.getX() + 0.5,
                mainPos.getY() + SPAWN_Y_OFFSET,
                mainPos.getZ() + 0.5);
        var targets = new ArrayList<TargetSlot>(assignment.size());
        for (var stack : assignment) {
            targets.add(new TargetSlot(
                    level, mainPos, null,
                    List.of(new GenericStack(AEItemKey.of(stack), stack.getCount())),
                    InsertionStrategy.CUSTOM,
                    terraInserter(level, spawnPos, stack)));
        }
        return new DispatchPlan(List.copyOf(targets), null);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (!BotaniaReflection.isTerraPlate(be) || !BotaniaReflection.terraIsIdle(be)) {
            return List.of();
        }
        // Scan the air column + small neighbourhood centred on the
        // plate. Lower bound is mainPos.Y so the recipe's product at
        // Y+0.2 is inside the window; upper bound is generous so a
        // bounced product (e.g. landed on a piston-adjacent block) is
        // still picked up.
        var bounds = new AABB(
                mainPos.getX() + 0.5 - EXTRACT_RADIUS, mainPos.getY(),
                mainPos.getZ() + 0.5 - EXTRACT_RADIUS,
                mainPos.getX() + 0.5 + EXTRACT_RADIUS, mainPos.getY() + 3.0,
                mainPos.getZ() + 0.5 + EXTRACT_RADIUS);
        var outputs = new ArrayList<GenericStack>();
        for (var entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (!entity.isAlive()) {
                continue;
            }
            // Skip the items we spawned as inputs — only outputs lack the marker.
            if (entity.getPersistentData().getBoolean(INPUT_MARKER)) {
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

    private java.util.function.BiFunction<GenericStack, Actionable, Long> terraInserter(
            ServerLevel level, Vec3 spawnPos, ItemStack stack) {
        return terraInserter(() -> {
            var entity = new ItemEntity(level, spawnPos.x, spawnPos.y, spawnPos.z, stack.copy());
            entity.setDeltaMovement(0, 0, 0);
            entity.setPickUpDelay(20);
            entity.getPersistentData().putBoolean(INPUT_MARKER, true);
            return level.addFreshEntity(entity);
        });
    }

    static java.util.function.BiFunction<GenericStack, Actionable, Long> terraInserter(
            java.util.function.BooleanSupplier spawnInput) {
        return (target, actionable) -> {
            if (actionable.isSimulate()) {
                return target.amount();
            }
            return spawnInput.getAsBoolean() ? target.amount() : 0L;
        };
    }

    /**
     * Greedy per-ingredient assignment from the runtime {@link KeyCounter}.
     * Returns the list of stacks (one per non-empty ingredient) to be
     * dropped onto the plate, or {@code null} when something can't be
     * satisfied or the KeyCounter has leftover items.
     */
    @Nullable
    private static List<ItemStack> pickKeyCounterStacks(List<Ingredient> ingredients, KeyCounter[] inputs) {
        var available = new LinkedHashMap<AEItemKey, Long>();
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long amt = entry.getLongValue();
                if (amt <= 0) {
                    continue;
                }
                long previous = available.getOrDefault(itemKey, 0L);
                long merged;
                try {
                    merged = Math.addExact(previous, amt);
                } catch (ArithmeticException overflow) {
                    return null;
                }
                available.put(itemKey, merged);
            }
        }
        if (available.isEmpty()) {
            return null;
        }
        var stacks = new ArrayList<ItemStack>();
        for (var ing : ingredients) {
            if (ing.isEmpty()) {
                continue;
            }
            AEItemKey chosenKey = null;
            for (var entry : available.entrySet()) {
                if (entry.getValue() < 1L || !ing.test(entry.getKey().toStack(1))) {
                    continue;
                }
                chosenKey = entry.getKey();
                break;
            }
            if (chosenKey == null) {
                return null;
            }
            long remaining = available.get(chosenKey) - 1L;
            if (remaining == 0) {
                available.remove(chosenKey);
            } else {
                available.put(chosenKey, remaining);
            }
            stacks.add(chosenKey.toStack(1));
        }
        for (var amount : available.values()) {
            if (amount > 0) {
                return null;
            }
        }
        return stacks;
    }

    private record TerraBindHandle(Recipe<?> holder) {
    }
}

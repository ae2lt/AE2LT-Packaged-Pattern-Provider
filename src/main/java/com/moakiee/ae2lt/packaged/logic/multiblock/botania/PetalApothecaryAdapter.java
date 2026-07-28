package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Virtual adapter for the Petal Apothecary &mdash; the basin where flower
 * petals + a seed become a Botania mystical flower. Apothecary crafting
 * has no in-world delay; the only world effect is the one-water-per-craft
 * consumption, which the player can also satisfy by pouring water into
 * the basin.
 *
 * <p><b>Recipe shape:</b> Botania's {@code PetalApothecaryRecipe} extends
 * {@code RecipeWithReagent}. Its {@code getIngredients()} returns the
 * petal list, but the seed/reagent is a separate {@code getReagent()}
 * ingredient that is <i>also consumed per craft</i>. We treat the
 * reagent as one additional ingredient and consume K copies of it
 * together with K of every petal.
 *
 * <p><b>Water source selection (per push):</b> the adapter inspects the
 * runtime {@link KeyCounter} for the player's chosen water source:
 * <ol>
 *   <li><b>{@code minecraft:water_bucket}</b>: if K copies are present,
 *       we consume K buckets and return K {@code minecraft:bucket}s as
 *       additional outputs &mdash; matching vanilla Botania, where the
 *       player right-clicks a water bucket onto the basin and gets the
 *       empty bucket back. The basin's water state is NOT touched.</li>
 *   <li><b>{@code minecraft:water} fluid via {@link AEFluidKey}</b>: if
 *       K&times;1000&thinsp;mB of water fluid is present (1B per craft),
 *       we consume that amount and the basin's water state is NOT
 *       touched. No empty container is returned (the fluid lives on the
 *       AE network, not in a physical bucket).</li>
 *   <li><b>Basin's own water</b>: when neither of the above is provided
 *       the basin must already be filled with water; we flip it to
 *       {@code EMPTY} for the whole batch, exactly as the player's
 *       right-click would do for a single craft. Larger K values still
 *       cost one charge because Botania itself drains one charge per
 *       interaction regardless of how many petals are present.</li>
 * </ol>
 *
 * <p>Anti-duplication path:
 * <ul>
 *   <li>bind only locks in a recipe whose result key matches the
 *       pattern's declared output (NBT-strict under STRICT,
 *       item-id-only under ID_ONLY) <i>and</i> whose recipe ingredients
 *       are coverable by the pattern's declared inputs.</li>
 *   <li>plan re-runs strict {@code ingredient.test} on each KeyCounter
 *       key (petals AND reagent), so a NBT-loose pattern (ID_ONLY)
 *       cannot upgrade a cheaper variant into the recipe's NBT-bearing
 *       output.</li>
 *   <li>The KeyCounter must be exactly drained after water + reagent +
 *       petals are consumed (no swallowing): patterns that route stray
 *       items through this provider fail the plan and don't dead-lock
 *       the CPU.</li>
 *   <li>The returned primary stack is {@code AEItemKey.of(recipe.result)}
 *       verbatim &mdash; never the pattern's declared key.</li>
 * </ul>
 */
public final class PetalApothecaryAdapter implements VirtualCraftingAdapter {

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        return BotaniaReflection.isPetalApothecary(be);
    }

    // Note: flushSoundId() intentionally not overridden. onVirtualBatchFlush
    // already triggers the apothecary's own altarCraft sound via the
    // blockEvent path; adding a second "ding" at the provider would
    // overlap the same tick and produce audible dissonance.

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.BOTANIA_PETAL_APOTHECARY;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (!recognizesMain(level, mainPos, be)) {
            return null;
        }
        var holder = BotaniaRecipeLookup.findPetalApothecaryByOutput(level, pattern);
        if (holder == null) {
            return null;
        }
        // Early sanity gate: every recipe ingredient (including the
        // reagent) must be satisfiable by at least one declared pattern
        // input. Wrong-machine patterns that happen to share an output
        // id with a real apothecary recipe would otherwise bind and
        // then hang the CPU on a plan that never succeeds. Water source
        // inputs (bucket / fluid) live outside the recipe and are
        // therefore not checked here.
        if (!patternInputsCoverRecipe(pattern, holder.value())) {
            return null;
        }
        return new BindingResult(new PetalBindHandle(holder), BindingMode.VIRTUAL);
    }

    /**
     * Lightweight, per-ingredient existence test: for each recipe
     * ingredient (petals + reagent), there must be at least one pattern
     * input whose item key passes {@code ingredient.test(key.toStack(1))}.
     * Quantities are not yet enforced (that is plan-time work); this is
     * only a "does the pattern look anything like a recipe of this
     * machine" gate.
     */
    private static boolean patternInputsCoverRecipe(IPatternDetails pattern, Recipe<?> recipe) {
        var patternInputs = pattern.getInputs();
        if (patternInputs == null || patternInputs.length == 0) {
            return false;
        }
        var allIngredients = new ArrayList<Ingredient>(recipe.getIngredients());
        var reagent = BotaniaReflection.recipeReagent(recipe);
        if (reagent != null && !reagent.isEmpty()) {
            allIngredients.add(reagent);
        }
        for (var ing : allIngredients) {
            if (ing.isEmpty()) {
                continue;
            }
            boolean satisfied = false;
            for (var patternInput : patternInputs) {
                if (patternInput == null) {
                    continue;
                }
                var possible = patternInput.getPossibleInputs();
                if (possible == null) {
                    continue;
                }
                for (var stack : possible) {
                    if (!(stack.what() instanceof AEItemKey itemKey)) {
                        continue;
                    }
                    if (ing.test(itemKey.toStack(1))) {
                        satisfied = true;
                        break;
                    }
                }
                if (satisfied) {
                    break;
                }
            }
            if (!satisfied) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        // VIRTUAL pushes never go through canDispatch (see
        // PackagedPatternProviderLogic#tryVirtualPush). We still
        // implement it for completeness, gating only on the BE itself:
        // the actual water-source decision happens at plan time once we
        // can see the KeyCounter.
        var be = level.getBlockEntity(mainPos);
        return BotaniaReflection.isPetalApothecary(be);
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        return null;
    }

    @Override
    @Nullable
    public VirtualCraftingResult planVirtualWithBinding(ServerLevel level, BlockPos mainPos,
                                                        IPatternDetails pattern, KeyCounter[] inputs,
                                                        Object handle, IActionSource source) {
        if (!(handle instanceof PetalBindHandle bind)) {
            return null;
        }
        var be = level.getBlockEntity(mainPos);
        if (!recognizesMain(level, mainPos, be)) {
            return null;
        }
        var recipe = bind.holder().value();
        var result = recipe.getResultItem(level.registryAccess());
        if (result == null || result.isEmpty()) {
            return null;
        }
        long patternAmount = BotaniaRecipeLookup.patternPrimaryOutputAmount(pattern);
        int resultCount = result.getCount();
        if (resultCount <= 0 || patternAmount <= 0 || patternAmount % resultCount != 0) {
            return null;
        }
        long craftRatio = patternAmount / resultCount;
        if (craftRatio <= 0 || craftRatio > Integer.MAX_VALUE) {
            return null;
        }

        // Materialize the KeyCounter into two mutable maps (items +
        // fluids). Every consumed key is debited from these maps; at
        // the end both maps must be exactly zero (no swallowing).
        var itemAvailable = new LinkedHashMap<Item, Long>();
        var itemKeys = new HashMap<Item, AEItemKey>();
        var fluidAvailable = new LinkedHashMap<Fluid, Long>();
        var fluidKeys = new HashMap<Fluid, AEFluidKey>();
        for (var counter : inputs) {
            for (var entry : counter) {
                long amt = entry.getLongValue();
                if (amt <= 0) {
                    continue;
                }
                if (entry.getKey() instanceof AEItemKey itemKey) {
                    var item = itemKey.getItem();
                    itemAvailable.merge(item, amt, Long::sum);
                    itemKeys.putIfAbsent(item, itemKey);
                } else if (entry.getKey() instanceof AEFluidKey fluidKey) {
                    var fluid = fluidKey.getFluid();
                    fluidAvailable.merge(fluid, amt, Long::sum);
                    fluidKeys.putIfAbsent(fluid, fluidKey);
                } else {
                    // Unknown key kind: refuse rather than mistakenly
                    // committing a craft that AE wouldn't be able to
                    // recover from.
                    return null;
                }
            }
        }
        if (itemAvailable.isEmpty() && fluidAvailable.isEmpty()) {
            return null;
        }

        // Choose water source (priority: bucket > fluid > basin).
        var water = chooseWaterSource(itemAvailable, fluidAvailable, craftRatio, be);
        if (water == null) {
            return null;
        }

        // Consume recipe ingredients + reagent strictly from itemAvailable.
        var ingredients = new ArrayList<Ingredient>(recipe.getIngredients());
        var reagent = BotaniaReflection.recipeReagent(recipe);
        if (reagent != null && !reagent.isEmpty()) {
            ingredients.add(reagent);
        }
        if (!consumeIngredientsStrict(ingredients, craftRatio, itemAvailable, itemKeys)) {
            return null;
        }

        // No-swallow check across both maps.
        for (var v : itemAvailable.values()) {
            if (v > 0) {
                return null;
            }
        }
        for (var v : fluidAvailable.values()) {
            if (v > 0) {
                return null;
            }
        }

        long totalCount;
        try {
            totalCount = Math.multiplyExact(craftRatio, (long) resultCount);
        } catch (ArithmeticException ignored) {
            return null;
        }
        if (!BotaniaRecipeLookup.validatePatternBatchOutput(pattern, result, totalCount)) {
            return null;
        }

        // Commit the basin side-effect only when the basin's own water
        // is what's being consumed. In bucket / fluid modes the basin
        // keeps its current state so a "bucket-fed" pattern can run on
        // an empty basin and an explicit "fluid-fed" pattern doesn't
        // accidentally drain a basin a player just refilled.
        if (water == WaterSource.BASIN) {
            BotaniaReflection.apothecarySetEmpty(be);
        }

        // Build the output list: always the primary recipe result; with
        // bucket-mode we additionally return K empty buckets to mirror
        // vanilla's water_bucket->bucket conversion.
        var outputs = new ArrayList<GenericStack>(2);
        outputs.add(new GenericStack(AEItemKey.of(result), totalCount));
        if (water == WaterSource.BUCKET) {
            outputs.add(new GenericStack(AEItemKey.of(Items.BUCKET), craftRatio));
        }
        return new VirtualCraftingResult(outputs);
    }

    /**
     * Picks the water source for this batch. Mutates the supplied
     * availability maps in-place so the chosen source is debited and
     * the downstream "no swallow" check still passes.
     *
     * <p>Returns {@code null} when none of the three sources can cover
     * the batch (caller should fail the plan).
     */
    @Nullable
    private static WaterSource chooseWaterSource(LinkedHashMap<Item, Long> itemAvailable,
                                                 LinkedHashMap<Fluid, Long> fluidAvailable,
                                                 long craftRatio,
                                                 BlockEntity be) {
        long bucketsAvail = itemAvailable.getOrDefault(Items.WATER_BUCKET, 0L);
        if (bucketsAvail >= craftRatio) {
            long remaining = bucketsAvail - craftRatio;
            if (remaining == 0) {
                itemAvailable.remove(Items.WATER_BUCKET);
            } else {
                itemAvailable.put(Items.WATER_BUCKET, remaining);
            }
            return WaterSource.BUCKET;
        }
        long fluidAvail = fluidAvailable.getOrDefault(Fluids.WATER, 0L);
        long fluidNeed;
        try {
            fluidNeed = Math.multiplyExact(craftRatio, (long) AEFluidKey.AMOUNT_BUCKET);
        } catch (ArithmeticException ignored) {
            return null;
        }
        if (fluidAvail >= fluidNeed) {
            long remaining = fluidAvail - fluidNeed;
            if (remaining == 0) {
                fluidAvailable.remove(Fluids.WATER);
            } else {
                fluidAvailable.put(Fluids.WATER, remaining);
            }
            return WaterSource.FLUID;
        }
        // No bucket / fluid available: fall back to the basin's own
        // water. If even that is empty we can't craft.
        if (!BotaniaReflection.apothecaryHasWater(be)) {
            return null;
        }
        return WaterSource.BASIN;
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter, IActionSource source) {
        return List.of();
    }

    /**
     * Mirror of the vanilla {@code PetalApothecaryBlockEntity#collideEntityItem}
     * "craft complete" branch: emit a {@code BLOCK_ACTIVATE} game event so
     * Allay/sculk listeners react, then dispatch
     * {@code triggerEvent(1, 0)} via {@code blockEvent} so each connected
     * client plays Botania's own 25-sparkle particle burst and the
     * {@code botania:altarCraft} subtitle. We delegate to the BE's
     * existing trigger logic instead of replicating the particle loop so
     * any future Botania tweak (different particle, modded subtitle, etc.)
     * automatically applies here.
     */
    @Override
    public void onVirtualBatchFlush(ServerLevel level, BlockPos mainPos, Object handle, IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (!BotaniaReflection.isPetalApothecary(be)) {
            return;
        }
        var state = be.getBlockState();
        level.gameEvent(null, GameEvent.BLOCK_ACTIVATE, mainPos);
        level.blockEvent(mainPos, state.getBlock(), 1, 0);
    }

    /**
     * Per-ingredient strict assignment: for each non-empty ingredient
     * (petals + the reagent/seed), consumes K matching items from the
     * supplied item-availability map using NBT-strict
     * {@code ingredient.test(key.toStack(1))}. Returns false when an
     * ingredient cannot be satisfied. Leftover-check (no swallowing) is
     * the caller's responsibility.
     */
    private static boolean consumeIngredientsStrict(List<Ingredient> ingredients, long k,
                                                    LinkedHashMap<Item, Long> available,
                                                    HashMap<Item, AEItemKey> keysByItem) {
        for (var ing : ingredients) {
            if (ing.isEmpty()) {
                continue;
            }
            boolean matched = false;
            for (var it = available.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (entry.getValue() < k) {
                    continue;
                }
                var key = keysByItem.get(entry.getKey());
                if (key == null) {
                    continue;
                }
                if (!ing.test(key.toStack(1))) {
                    continue;
                }
                long remaining = entry.getValue() - k;
                if (remaining == 0) {
                    it.remove();
                } else {
                    entry.setValue(remaining);
                }
                matched = true;
                break;
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bind-time cache: matched {@link RecipeHolder}. The recipe object
     * drives plan-time ingredient validation and output stacking. The
     * water-source choice is per-push and lives outside the handle.
     */
    private record PetalBindHandle(RecipeHolder<?> holder) {
    }

    private enum WaterSource {
        BUCKET,
        FLUID,
        BASIN
    }
}

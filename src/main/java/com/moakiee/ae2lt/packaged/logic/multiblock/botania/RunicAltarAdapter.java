package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterPersistentScope;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Hybrid adapter for the Runic Altar &mdash; REAL dispatch (we feed
 * recipe ingredients + catalysts into the altar's actual inventory so
 * it accumulates mana from the player's mana spreaders), <i>virtual</i>
 * extract (once the altar's own state machine signals "ready", we
 * synthesise the output stack from the recipe and reset altar state
 * directly without waiting for the player to right-click with a wand).
 *
 * <p><b>Recipe shape:</b> Botania's {@code RunicAltarRecipe}
 * implements both {@code RecipeWithReagent} and
 * {@code RecipeWithCatalysts}. Three logical input groups:
 * <ol>
 *   <li><b>ingredients</b> ({@code getIngredients()}) &mdash; runes,
 *       petals, dusts that are <i>consumed</i> by the craft.</li>
 *   <li><b>catalysts</b> ({@code getCatalysts()}) &mdash; helper
 *       runes that sit in the inventory alongside the ingredients
 *       and are <i>returned</i> after the craft (via
 *       {@code getRemainingItems(input)}).</li>
 *   <li><b>reagent</b> ({@code getReagent()}, always livingrock)
 *       &mdash; tossed as a player-held item / on-altar
 *       {@code ItemEntity} to trigger the craft. <i>Never</i> placed
 *       in the altar inventory.</li>
 * </ol>
 * <p>Botania's {@code matches()} requires
 * {@code input.size() == ingredients.size() + catalysts.size()} (no
 * more, no less). Dispatching the reagent into a slot, or omitting
 * the catalysts, would make {@code updateRecipe()} fail and the altar
 * would sit idle forever.
 *
 * <p><b>Reagent semantics in vanilla:</b> the player places the
 * ingredients + catalysts into the altar inventory (right-clicks =
 * {@code addItem} path B), waits for the surrounding spreaders to fill
 * the mana bar, then tosses a livingrock onto the altar (which sits as
 * an {@code ItemEntity} via {@code addItem} path A) and right-clicks
 * with the Wand of the Forest. The wand handler
 * ({@code onUsedByWand}) consumes one livingrock from the entity list,
 * assembles the output, drops it as an {@code ItemEntity} above the
 * altar and clears the inventory + resets state, restoring catalysts
 * via {@code getRemainingItems(input)}.
 *
 * <p><b>Adapter behaviour:</b>
 * <ul>
 *   <li><b>dispatch (REAL)</b>: writes
 *       {@code ingredients + catalysts} into the altar inventory slots
 *       (in that exact order). The reagent gets its own
 *       {@link TargetSlot} with a "void" inserter that debits one
 *       livingrock from the AE {@link KeyCounter} <i>without</i>
 *       placing it anywhere &mdash; this models the player consuming
 *       a livingrock to trigger the craft.</li>
 *   <li><b>extract (VIRTUAL)</b>: when
 *       {@code currentRecipe != null && mana >= manaToGet}, we
 *       assemble the output, reflectively call
 *       {@code recipe.getRemainingItems(getRecipeInput())} to gather
 *       the catalysts that have to flow back to the network, clear
 *       the inventory, zero the mana fields and drop
 *       {@code currentRecipe} back to null. The player sees the
 *       altar animate once but never needs to swing a wand.</li>
 * </ul>
 *
 * <p>Batch policy: K=1 only. The altar's internal state machine runs
 * one recipe at a time and the player-visible craft is a single cycle;
 * batching would require multiple sequential dispatches anyway.
 *
 * <p>Anti-duplication path:
 * <ul>
 *   <li>bind validates the pattern's declared output key against the
 *       recipe's real {@code getResultItem(...)} key (NBT-strict under
 *       STRICT, item-id-only under ID_ONLY) and that the pattern's
 *       declared inputs cover every recipe ingredient + catalyst +
 *       reagent &mdash; wrong-machine patterns refuse to bind so the
 *       CPU does not dead-lock on a plan that will never succeed.</li>
 *   <li>plan picks each ingredient + catalyst + reagent stack from
 *       the runtime {@link KeyCounter} via NBT-strict
 *       {@code ingredient.test} and hands the actual key.toStack(1)
 *       either to the altar inventory (ingredients + catalysts) or to
 *       the void inserter (reagent).</li>
 *   <li>Output stacks are {@code AEItemKey.of(...)} verbatim &mdash;
 *       the recipe's primary result plus any catalysts reflected back
 *       by {@code getRemainingItems(...)}.</li>
 * </ul>
 */
public final class RunicAltarAdapter implements MultiblockAdapter {

    /**
     * Namespaced flag key, stored on the provider via the
     * {@link AdapterPersistentScope} handed in by
     * {@link #planWithBinding(ServerLevel, BlockPos, IPatternDetails, KeyCounter[], Object, IActionSource, AdapterPersistentScope)}
     * / {@link #extractOutputs(ServerLevel, BlockPos, AllowedOutputFilter, IActionSource, AdapterPersistentScope)}.
     * Set when the dispatch successfully debits a livingrock from AE; cleared
     * by {@code extractOutputs} after it finishes harvesting the recipe.
     *
     * <p>We deliberately store this on the provider rather than on the altar
     * BE: Botania's {@code SimpleInventoryBlockEntity#saveAdditional} doesn't
     * call {@code super}, which silently drops NeoForge's {@code NeoForgeData}
     * + {@code attachments} blobs on disk save &mdash; any flag we tried to
     * stash on the altar would survive memory only, not the next world load.
     *
     * <p>Anchoring the flag on the provider also captures the right ownership:
     * "this craft was paid for by <i>us</i>" is a fact about the provider's
     * accounting, not about the altar; the altar by itself can't tell whether
     * its ingredients came from AE or a player's hand.
     */
    private static final String REAGENT_DISPATCHED_FLAG = "botania_runic_altar:reagent_dispatched";

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        return BotaniaReflection.isRunicAltar(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.BOTANIA_RUNIC_ALTAR;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (!recognizesMain(level, mainPos, be)) {
            return null;
        }
        // findRunicAltarByOutput validates the pattern outputs against
        // primary + every catalyst (each amount 1, K=1). It returns
        // null when the pattern misses a catalyst, declares an extra
        // entry, or amounts don't match &mdash; so no separate primary
        // amount check is needed here.
        var holder = BotaniaRecipeLookup.findRunicAltarByOutput(level, pattern);
        if (holder == null) {
            return null;
        }
        // Sanity gate: every recipe ingredient (runes + catalysts +
        // reagent) must be coverable by the pattern's inputs; otherwise
        // refuse to bind so the CPU never schedules a craft that plan
        // can't satisfy.
        if (!patternInputsCoverRecipe(pattern, holder.value())) {
            return null;
        }
        return new BindingResult(new RunicBindHandle(holder), BindingMode.REAL);
    }

    /**
     * Per-ingredient existence check: each recipe ingredient (runes +
     * catalysts + reagent) must have at least one pattern input whose
     * item key passes {@code ingredient.test(key.toStack(1))}.
     * Quantities are enforced later at plan time.
     */
    private static boolean patternInputsCoverRecipe(IPatternDetails pattern,
                                                    net.minecraft.world.item.crafting.Recipe<?> recipe) {
        var patternInputs = pattern.getInputs();
        if (patternInputs == null || patternInputs.length == 0) {
            return false;
        }
        var allIngredients = new ArrayList<Ingredient>(recipe.getIngredients());
        allIngredients.addAll(BotaniaReflection.recipeCatalysts(recipe));
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
        if (!(handle instanceof RunicBindHandle)) {
            return false;
        }
        var be = level.getBlockEntity(mainPos);
        if (!BotaniaReflection.isRunicAltar(be)) {
            return false;
        }
        // Idle = no current recipe + zero mana progress + empty inventory.
        if (BotaniaReflection.altarCurrentRecipe(be) != null) {
            return false;
        }
        if (BotaniaReflection.altarMana(be) != 0 || BotaniaReflection.altarManaToGet(be) != 0) {
            return false;
        }
        var inv = BotaniaReflection.simpleInventoryContainer(be);
        return inv != null && containerEmpty(inv);
        // Note: stale {@code REAGENT_DISPATCHED_FLAG} entries on the
        // provider are harmless here. {@link #planWithBinding}'s onCommit
        // calls {@code setFlag} unconditionally, which is idempotent, so a
        // residual flag from a previous craft that was finished externally
        // (player used a wand themselves between dispatch and extract scan)
        // simply gets overwritten on the next successful dispatch.
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        return planWithBinding(level, mainPos, pattern, inputs, handle, source, AdapterPersistentScope.NOOP);
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source,
                                        AdapterPersistentScope scope) {
        if (!(handle instanceof RunicBindHandle bind)) {
            return null;
        }
        var be = level.getBlockEntity(mainPos);
        if (!BotaniaReflection.isRunicAltar(be)) {
            return null;
        }
        var inv = BotaniaReflection.simpleInventoryContainer(be);
        if (inv == null || !containerEmpty(inv)) {
            return null;
        }

        var recipe = bind.holder().value();
        // Build the assignment in two parts:
        //   inventorySlotItems = ingredients ++ catalysts
        //     - go into the altar inventory slots (matches() requires
        //       input.size() == ingredients.size() + catalysts.size()).
        //   reagent (livingrock)
        //     - routed through a void inserter so AE deducts one copy
        //       from the network without ever placing it into the wrong
        //       inventory slot.
        // All three halves share the same KeyCounter pool so the strict
        // "no swallowing" invariant in pickKeyCounterStacks still holds.
        var ingredientsList = new ArrayList<Ingredient>(recipe.getIngredients());
        var catalystsList = BotaniaReflection.recipeCatalysts(recipe);
        var reagent = BotaniaReflection.recipeReagent(recipe);
        boolean hasReagent = reagent != null && !reagent.isEmpty();

        var inventorySlotIngredients = new ArrayList<Ingredient>(ingredientsList);
        inventorySlotIngredients.addAll(catalystsList);

        var allList = new ArrayList<Ingredient>(inventorySlotIngredients);
        if (hasReagent) {
            allList.add(reagent);
        }
        var assignment = pickKeyCounterStacks(allList, inputs);
        if (assignment == null || assignment.isEmpty()) {
            return null;
        }

        int nonEmptyInventoryCount = 0;
        for (var ing : inventorySlotIngredients) {
            if (!ing.isEmpty()) {
                nonEmptyInventoryCount++;
            }
        }
        int expectedAssignmentSize = nonEmptyInventoryCount + (hasReagent ? 1 : 0);
        if (assignment.size() != expectedAssignmentSize) {
            return null;
        }
        if (nonEmptyInventoryCount > inv.getContainerSize()) {
            return null;
        }

        var targets = new ArrayList<TargetSlot>(assignment.size());
        for (int i = 0; i < nonEmptyInventoryCount; i++) {
            var stack = assignment.get(i);
            int slot = i;
            targets.add(new TargetSlot(
                    level, mainPos, null,
                    List.of(new GenericStack(AEItemKey.of(stack), stack.getCount())),
                    InsertionStrategy.CUSTOM,
                    altarSlotInserter(level, mainPos, slot, stack)));
        }
        if (hasReagent) {
            var reagentStack = assignment.get(nonEmptyInventoryCount);
            targets.add(new TargetSlot(
                    level, mainPos, null,
                    List.of(new GenericStack(AEItemKey.of(reagentStack), reagentStack.getCount())),
                    InsertionStrategy.CUSTOM,
                    reagentVoidInserter()));
        }
        return new DispatchPlan(List.copyOf(targets), () -> {
            // After all slots are populated mark the BE dirty so it syncs to
            // clients and re-runs serverTick logic on the next tick. Use
            // sendBlockUpdated so the client repaints the hovering
            // ingredient renderer on the same tick the items appear &mdash;
            // setChanged alone only writes to disk, the player wouldn't
            // see anything until the chunk loaded again.
            var beAfter = level.getBlockEntity(mainPos);
            if (beAfter != null) {
                beAfter.setChanged();
                var state = beAfter.getBlockState();
                level.sendBlockUpdated(mainPos, state, state, 3);
            }
            // Record that *this* dispatch debited a livingrock from AE.
            // extractOutputs will only harvest products when the flag is
            // set on the provider, so a player who hand-tossed materials
            // without a livingrock cannot have their pending craft silently
            // siphoned away by our auto-return scan. Storing on the
            // provider (via scope) rather than on the altar BE side-steps
            // Botania's SimpleInventoryBlockEntity.saveAdditional skipping
            // super, which would otherwise drop NeoForge customPersistentData
            // and attachment NBT on disk save.
            scope.setFlag(mainPos, REAGENT_DISPATCHED_FLAG);
        });
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        return extractOutputs(level, mainPos, filter, source, AdapterPersistentScope.NOOP);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source,
                                             AdapterPersistentScope scope) {
        var be = level.getBlockEntity(mainPos);
        if (!BotaniaReflection.isRunicAltar(be)) {
            return List.of();
        }
        var currentRecipe = BotaniaReflection.altarCurrentRecipe(be);
        if (currentRecipe == null) {
            return List.of();
        }
        int mana = BotaniaReflection.altarMana(be);
        int manaToGet = BotaniaReflection.altarManaToGet(be);
        if (manaToGet <= 0 || mana < manaToGet) {
            return List.of();
        }
        // Gate: only proceed if *we* dispatched the reagent for this craft.
        // The flag is set on the provider in planWithBinding's onCommit.
        // When a player hand-tossed ingredients + catalysts onto the altar
        // (without throwing a livingrock and using their wand), the flag
        // stays false and we leave the altar alone so the player can finish
        // the craft themselves. This also handles the "inv was loaded
        // from disk with no associated dispatch" case after server restart
        // in a way that errs toward the player.
        if (!scope.hasFlag(mainPos, REAGENT_DISPATCHED_FLAG)) {
            return List.of();
        }

        ItemStack result;
        try {
            result = currentRecipe.value().getResultItem(level.registryAccess());
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
        if (result == null || result.isEmpty()) {
            return List.of();
        }
        var resultKey = AEItemKey.of(result);
        // No AllowedOutputFilter check here. The altar is a closed-loop
        // BE we dispatched into ourselves: the {@code currentRecipe} is
        // strictly the one we matched at bind time (the BE only assigns
        // it via {@code updateRecipe} from its own inventory, which we
        // wrote), and the catalyst stacks are pulled directly via
        // {@code RunicAltarRecipe.getRemainingItems(input)}. There is no
        // "shared inventory / broad dropped-item area" the filter would
        // be defending against here, and the bind-time
        // {@code validatePatternRunicOutputs} already enforces the
        // pattern-output allow-list.

        // Snapshot catalysts (copies) BEFORE clearing the inventory:
        // RunicAltarRecipe.getRemainingItems(input) consults the live
        // input but copies each catalyst stack it returns, so doing
        // this before clearContent() is the safe order even though the
        // returned stacks themselves don't alias the inventory.
        var catalystStacks = collectCatalystStacks(currentRecipe.value(), be);

        // Virtual extract: clear inventory + reset state instead of
        // waiting for the player to right-click. The altar's animation
        // had already played for the player to see the cycle.
        var inv = BotaniaReflection.simpleInventoryContainer(be);
        if (inv != null) {
            inv.clearContent();
        }
        BotaniaReflection.altarResetState(be);
        be.setChanged();
        // Clear the dispatch flag now that this craft is fully harvested,
        // freeing the (provider, altarPos) slot in the scope so the next
        // dispatch's onCommit can flip it back on cleanly.
        scope.clearFlag(mainPos, REAGENT_DISPATCHED_FLAG);
        // Force a BE update packet to the client so the rendered
        // inventory contents (the petals + runes hovering above the
        // altar) clear in the same tick the craft "finished".
        // setChanged() alone only marks the chunk dirty; client
        // rendering doesn't refresh until a sendBlockUpdated. Botania
        // itself does the same after addItem path B.
        var altarState = be.getBlockState();
        level.sendBlockUpdated(mainPos, altarState, altarState, 3);

        // Replay the audio/visual cues that Botania's own onUsedByWand
        // emits after finishing a craft:
        //   - BLOCK_ACTIVATE game event so vibration / Allay listeners
        //     pick up the moment of completion.
        //   - blockEvent(eventId=1, data=60): triggerEvent sets
        //     cooldown=60, matching the post-craft "rest" period before
        //     the altar can be used again. Without this, automation
        //     could re-dispatch into a 'still settling' altar.
        //   - blockEvent(eventId=2, data=0): triggerEvent dispatches
        //     25 sparkle particles + the runeAltarCraft subtitle to
        //     every client tracking this chunk. We deliberately delegate
        //     to Botania's existing client-side trigger so any future
        //     particle/sound retune in upstream Botania flows through
        //     unchanged.
        var altarBlock = altarState.getBlock();
        level.gameEvent(null, GameEvent.BLOCK_ACTIVATE, mainPos);
        level.blockEvent(mainPos, altarBlock, 1, 60);
        level.blockEvent(mainPos, altarBlock, 2, 0);

        var outputs = new ArrayList<GenericStack>(1 + catalystStacks.size());
        outputs.add(new GenericStack(resultKey, result.getCount()));
        for (var stack : catalystStacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            outputs.add(new GenericStack(AEItemKey.of(stack), stack.getCount()));
        }
        return List.copyOf(outputs);
    }

    /**
     * Reflectively pulls {@code recipe.getRemainingItems(getRecipeInput())}
     * out of the altar so any catalysts &mdash; e.g. the {@code rune_winter}
     * + {@code rune_fire} that gluttony uses &mdash; flow back to the AE
     * network instead of being voided when we clear the inventory.
     * Empty entries are discarded by the caller.
     */
    private static List<ItemStack> collectCatalystStacks(
            net.minecraft.world.item.crafting.Recipe<?> recipe, BlockEntity be) {
        var input = BotaniaReflection.simpleInventoryRecipeInput(be);
        if (input == null) {
            return List.of();
        }
        return BotaniaReflection.recipeRemainingItems(recipe, input);
    }

    // allowsAutoReturn intentionally not overridden. Default {@code true}
    // applies: the altar is a closed BE and {@link #extractOutputs} only
    // emits stacks derived from the live recipe + getRemainingItems, both
    // of which are pinned by the bind-time validation.

    /**
     * Greedy per-ingredient assignment: each non-empty ingredient
     * consumes one matching item from the runtime {@link KeyCounter}
     * via NBT-strict {@code ingredient.test(key.toStack(1))}. The
     * KeyCounter must be drained exactly (no swallowing).
     */
    @Nullable
    private static List<ItemStack> pickKeyCounterStacks(List<Ingredient> ingredients, KeyCounter[] inputs) {
        var available = new LinkedHashMap<Item, Long>();
        var keysByItem = new HashMap<Item, AEItemKey>();
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long amt = entry.getLongValue();
                if (amt <= 0) {
                    continue;
                }
                var item = itemKey.getItem();
                available.merge(item, amt, Long::sum);
                keysByItem.putIfAbsent(item, itemKey);
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
            ItemStack picked = null;
            Item chosenItem = null;
            for (var entry : available.entrySet()) {
                if (entry.getValue() < 1L) {
                    continue;
                }
                var key = keysByItem.get(entry.getKey());
                if (key == null) {
                    continue;
                }
                var candidate = key.toStack(1);
                if (!ing.test(candidate)) {
                    continue;
                }
                picked = candidate;
                chosenItem = entry.getKey();
                break;
            }
            if (picked == null) {
                return null;
            }
            long remaining = available.get(chosenItem) - 1L;
            if (remaining == 0) {
                available.remove(chosenItem);
            } else {
                available.put(chosenItem, remaining);
            }
            stacks.add(picked);
        }
        for (var v : available.values()) {
            if (v > 0) {
                return null;
            }
        }
        return stacks;
    }

    private java.util.function.BiFunction<GenericStack, Actionable, Long> altarSlotInserter(
            ServerLevel level, BlockPos mainPos, int slot, ItemStack stack) {
        return (target, actionable) -> {
            if (actionable.isSimulate()) {
                return target.amount();
            }
            var be = level.getBlockEntity(mainPos);
            if (!BotaniaReflection.isRunicAltar(be)) {
                return 0L;
            }
            var inv = BotaniaReflection.simpleInventoryContainer(be);
            if (inv == null || slot >= inv.getContainerSize()) {
                return 0L;
            }
            if (!inv.getItem(slot).isEmpty()) {
                return 0L;
            }
            inv.setItem(slot, stack.copy());
            return target.amount();
        };
    }

    /**
     * "Void" inserter for the reagent slot: tells the dispatch
     * executor the slot accepted the full stack so AE debits the
     * livingrock from the network, but never actually writes it
     * anywhere. The altar's inventory therefore stays {@code matches}
     * against the recipe's {@code getIngredients()}, and the reagent
     * counts as "the player held it in their hand and consumed it" for
     * the virtual extract.
     */
    private java.util.function.BiFunction<GenericStack, Actionable, Long> reagentVoidInserter() {
        return (target, actionable) -> target.amount();
    }

    private static boolean containerEmpty(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (!container.getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private record RunicBindHandle(RecipeHolder<?> holder) {
    }
}

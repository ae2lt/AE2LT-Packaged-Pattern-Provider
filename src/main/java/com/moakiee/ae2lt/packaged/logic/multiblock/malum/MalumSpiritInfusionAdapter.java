package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.DroppedItemDispatch;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Runtime adapter for Malum's Spirit Altar infusion.
 * Extra inputs are placed on Malum access point inventories so the pedestals
 * render them; Malum then pulls those stacks into extrasInventory while the
 * altar consumes the recipe.
 */
public final class MalumSpiritInfusionAdapter implements MultiblockAdapter {

    private static final String MOD_ID = "malum";
    private static final ResourceLocation ALTAR_BLOCK = malumId("spirit_altar");
    private static final ResourceLocation RECIPE_TYPE_ID = malumId("spirit_infusion");
    private static final int MAX_INPUT_AMOUNT = 512;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isMalumLoaded()
                && blockId(be.getBlockState()).equals(ALTAR_BLOCK)
                && MalumReflection.isSpiritAltar(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.MALUM_SPIRIT_INFUSION;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return null;
        }
        if (!hasSingleItemOutput(pattern)) {
            return null;
        }
        var recipes = findCandidateRecipes(level);
        return recipes.isEmpty()
                ? null
                : new BindingResult(new InfusionBindHandle(recipes), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof InfusionBindHandle bind) || bind.recipes().isEmpty()) {
            return false;
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return false;
        }
        if (!MalumReflection.isAltarIdle(be)) {
            return false;
        }
        var mainInventory = MalumReflection.altarInventory(be);
        var spiritInventory = MalumReflection.altarSpiritInventory(be);
        var extrasInventory = MalumReflection.altarExtrasInventory(be);
        if (mainInventory == null || spiritInventory == null || extrasInventory == null) {
            return false;
        }
        if (mainInventory.getSlots() < 1) {
            return false;
        }
        if (!MalumAdapterSupport.inventoryEmpty(mainInventory)
                || !MalumAdapterSupport.inventoryEmpty(spiritInventory)
                || !MalumAdapterSupport.inventoryEmpty(extrasInventory)) {
            return false;
        }
        return true;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof InfusionBindHandle bind)) {
            return null;
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return null;
        }
        if (!MalumReflection.isAltarIdle(be)) {
            return null;
        }

        var mainInventory = MalumReflection.altarInventory(be);
        var spiritInventory = MalumReflection.altarSpiritInventory(be);
        var extrasInventory = MalumReflection.altarExtrasInventory(be);
        if (mainInventory == null || spiritInventory == null || extrasInventory == null) {
            return null;
        }
        if (mainInventory.getSlots() < 1) {
            return null;
        }
        if (!MalumAdapterSupport.inventoryEmpty(mainInventory)
                || !MalumAdapterSupport.inventoryEmpty(spiritInventory)
                || !MalumAdapterSupport.inventoryEmpty(extrasInventory)) {
            return null;
        }

        var aggregatedInputs = MalumAdapterSupport.aggregateInputs(inputs, MAX_INPUT_AMOUNT);
        if (aggregatedInputs == null) {
            return null;
        }

        var match = findRecipeMatch(bind.recipes(), level, pattern, aggregatedInputs);
        if (match == null) {
            return null;
        }
        if (match.spirits().size() > spiritInventory.getSlots()) {
            return null;
        }
        var pedestals = loadedPedestals(level, mainPos);
        if (!match.extras().isEmpty() && !pedestals.stream().allMatch(MalumSpiritInfusionAdapter::pedestalEmpty)) {
            return null;
        }
        var extraPlacements = assignExtrasToPedestals(
                match.extras(),
                pedestals,
                MalumAdapterSupport::toItemStack);
        if (extraPlacements == null) {
            return null;
        }

        var mainTarget = new TargetSlot(
                level,
                mainPos,
                null,
                List.of(MalumAdapterSupport.toGenericStack(match.main())),
                InsertionStrategy.CUSTOM,
                altarInserter(level, mainPos, false, 0, match.main()));

        var spiritTargets = new ArrayList<TargetSlot>(match.spirits().size());
        for (int i = 0; i < match.spirits().size(); i++) {
            var assignment = match.spirits().get(i);
            spiritTargets.add(new TargetSlot(
                    level,
                    mainPos,
                    null,
                    List.of(MalumAdapterSupport.toGenericStack(assignment)),
                    InsertionStrategy.CUSTOM,
                    altarInserter(level, mainPos, true, i, assignment)));
        }

        var extraTargets = new ArrayList<TargetSlot>(extraPlacements.size());
        for (var placement : extraPlacements) {
            extraTargets.add(new TargetSlot(
                    level,
                    placement.pos(),
                    null,
                    List.of(MalumAdapterSupport.toGenericStack(placement.assignment())),
                    InsertionStrategy.CUSTOM,
                    pedestalInserter(level, mainPos, placement)));
        }
        var targets = orderTargetsForAltarTrigger(extraTargets, spiritTargets, mainTarget);

        return new DispatchPlan(
                List.copyOf(targets),
                () -> MalumReflection.recalculateAltar(level, mainPos));
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return List.of();
        }
        return DroppedItemDispatch.collectOutputs(
                level,
                altarOutputAabb(mainPos),
                filter,
                entity -> true);
    }

    @Nullable
    private static RecipeMatch findRecipeMatch(
            List<Object> recipes,
            ServerLevel level,
            IPatternDetails pattern,
            List<MalumRecipeInputMatcher.Input<AEItemKey>> inputs) {
        for (var recipe : recipes) {
            var mainInput = MalumReflection.infusionInput(recipe);
            var spiritStacks = MalumReflection.infusionSpirits(recipe);
            var extraInputs = MalumReflection.infusionExtras(recipe);
            if (mainInput == null || spiritStacks == null || extraInputs == null) {
                continue;
            }

            var spiritRequirements = MalumAdapterSupport.requirementsFromStacks(spiritStacks);
            if (spiritRequirements == null) {
                continue;
            }

            var extraRequirements = extraInputs.stream()
                    .map(MalumAdapterSupport::requirement)
                    .toList();

            var match = MalumRecipeInputMatcher.match(
                    inputs,
                    MalumAdapterSupport.requirement(mainInput),
                    spiritRequirements,
                    extraRequirements);
            if (match == null) {
                continue;
            }

            var output = MalumReflection.infusionOutput(
                    recipe,
                    level,
                    MalumAdapterSupport.toItemStack(match.main()));
            if (output != null && !output.isEmpty() && MalumAdapterSupport.outputMatches(pattern, output)) {
                return new RecipeMatch(match.main(), match.spirits(), match.extras());
            }
        }
        return null;
    }

    private static List<Object> findCandidateRecipes(ServerLevel level) {
        var matches = new ArrayList<Object>();
        for (var holder : recipes(level)) {
            var recipe = holder.value();
            var mainInput = MalumReflection.infusionInput(recipe);
            var spiritStacks = MalumReflection.infusionSpirits(recipe);
            var extraInputs = MalumReflection.infusionExtras(recipe);
            if (mainInput == null || spiritStacks == null || extraInputs == null) {
                continue;
            }
            var spiritRequirements = MalumAdapterSupport.requirementsFromStacks(spiritStacks);
            if (spiritRequirements == null) {
                continue;
            }
            matches.add(recipe);
        }
        return List.copyOf(matches);
    }

    private static BiFunction<GenericStack, Actionable, Long> altarInserter(
            ServerLevel level,
            BlockPos mainPos,
            boolean spirit,
            int slot,
            MalumRecipeInputMatcher.Assignment<AEItemKey> assignment) {
        return (stack, mode) -> {
            if (!MalumAdapterSupport.matchesPlannedStack(stack, assignment)) {
                return 0L;
            }

            var be = level.getBlockEntity(mainPos);
            if (be == null
                    || !blockId(be.getBlockState()).equals(ALTAR_BLOCK)
                    || !MalumReflection.isSpiritAltar(be)
                    || !MalumReflection.isAltarInactive(be)) {
                return 0L;
            }

            var inventory = spirit
                    ? MalumReflection.altarSpiritInventory(be)
                    : MalumReflection.altarInventory(be);
            if (inventory == null) {
                return 0L;
            }

            var planned = MalumAdapterSupport.toItemStack(assignment);
            if (!MalumAdapterSupport.canPlace(inventory, slot, planned)) {
                return 0L;
            }

            if (mode == Actionable.MODULATE) {
                long inserted = insertAssignmentOneItemAtATime(
                        level, inventory, slot, assignment, key -> key.toStack(1));
                if (inserted < assignment.amount()) {
                    return inserted;
                }
            }
            return assignment.amount();
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> pedestalInserter(
            ServerLevel level,
            BlockPos altarPos,
            ExtraPlacement<AEItemKey> placement) {
        return (stack, mode) -> {
            if (!MalumAdapterSupport.matchesPlannedStack(stack, placement.assignment())) {
                return 0L;
            }

            var altar = level.getBlockEntity(altarPos);
            if (altar == null
                    || !blockId(altar.getBlockState()).equals(ALTAR_BLOCK)
                    || !MalumReflection.isSpiritAltar(altar)
                    || !MalumReflection.isAltarInactive(altar)) {
                return 0L;
            }

            var pedestal = pedestalAt(level, altarPos, placement.pos());
            if (pedestal == null || !placementFits(pedestal, placement.assignment(), MalumAdapterSupport::toItemStack)) {
                return 0L;
            }

            if (mode == Actionable.MODULATE) {
                var planned = MalumAdapterSupport.toItemStack(placement.assignment());
                if (!MalumReflection.placeItemInSlot(pedestal.inventory(), 0, planned)) {
                    return 0L;
                }
            }
            return placement.assignment().amount();
        };
    }

    private static List<MalumReflection.Pedestal> loadedPedestals(ServerLevel level, BlockPos altarPos) {
        return MalumReflection.capturePedestals(level, altarPos).stream()
                .filter(pedestal -> level.isLoaded(pedestal.pos()))
                .toList();
    }

    @Nullable
    private static MalumReflection.Pedestal pedestalAt(ServerLevel level, BlockPos altarPos, BlockPos pedestalPos) {
        for (var pedestal : loadedPedestals(level, altarPos)) {
            if (pedestal.pos().equals(pedestalPos)) {
                return pedestal;
            }
        }
        return null;
    }

    private static boolean pedestalEmpty(MalumReflection.Pedestal pedestal) {
        return pedestal.inventory().getSlots() >= 1 && pedestal.inventory().getStackInSlot(0).isEmpty();
    }

    static <T> List<T> orderTargetsForAltarTrigger(
            List<T> extraTargets,
            List<T> spiritTargets,
            T mainTarget) {
        var ordered = new ArrayList<T>(extraTargets.size() + spiritTargets.size() + 1);
        ordered.addAll(extraTargets);
        ordered.addAll(spiritTargets);
        ordered.add(mainTarget);
        return List.copyOf(ordered);
    }

    static <T> long insertAssignmentOneItemAtATime(
            ServerLevel level,
            IItemHandlerModifiable inventory,
            int fallbackSlot,
            MalumRecipeInputMatcher.Assignment<T> assignment,
            Function<T, ItemStack> unitStackFactory) {
        long inserted = 0L;
        for (long i = 0; i < assignment.amount(); i++) {
            var unit = unitStackFactory.apply(assignment.value());
            if (unit.isEmpty() || unit.getCount() != 1) {
                return inserted;
            }
            if (!MalumReflection.insertItem(level, inventory, fallbackSlot, unit)) {
                return inserted;
            }
            inserted++;
        }
        return inserted;
    }

    @Nullable
    static <T> List<ExtraPlacement<T>> assignExtrasToPedestals(
            List<MalumRecipeInputMatcher.Assignment<T>> extras,
            List<MalumReflection.Pedestal> pedestals,
            Function<MalumRecipeInputMatcher.Assignment<T>, ItemStack> stackFactory) {
        var simulated = copyPedestalStacks(pedestals);
        var placements = new ArrayList<ExtraPlacement<T>>();

        for (var extra : extras) {
            ExtraPlacement<T> placement = null;
            for (int index = 0; index < pedestals.size(); index++) {
                var pedestal = pedestals.get(index);
                long placeable = maxPlaceableIntoSlot(
                        pedestal.inventory(),
                        simulated.get(index),
                        0,
                        extra.value(),
                        extra.amount(),
                        stackFactory);
                if (placeable != extra.amount()) {
                    continue;
                }

                var stack = stackFactory.apply(extra);
                applyToSimulatedSlot(simulated, index, stack);
                placement = new ExtraPlacement<>(extra, pedestal.pos());
                break;
            }
            if (placement == null) {
                return null;
            }
            placements.add(placement);
        }

        return List.copyOf(placements);
    }

    private static <T> boolean placementFits(
            MalumReflection.Pedestal pedestal,
            MalumRecipeInputMatcher.Assignment<T> assignment,
            Function<MalumRecipeInputMatcher.Assignment<T>, ItemStack> stackFactory) {
        var inventory = pedestal.inventory();
        var existing = inventory.getSlots() < 1 ? ItemStack.EMPTY : inventory.getStackInSlot(0);
        return maxPlaceableIntoSlot(
                inventory,
                existing,
                0,
                assignment.value(),
                assignment.amount(),
                stackFactory) == assignment.amount();
    }

    private static List<ItemStack> copyPedestalStacks(List<MalumReflection.Pedestal> pedestals) {
        var copy = new ArrayList<ItemStack>(pedestals.size());
        for (var pedestal : pedestals) {
            var inventory = pedestal.inventory();
            copy.add(inventory.getSlots() < 1 ? ItemStack.EMPTY : inventory.getStackInSlot(0).copy());
        }
        return copy;
    }

    private static <T> long maxPlaceableIntoSlot(
            IItemHandlerModifiable inventory,
            ItemStack existing,
            int slot,
            T value,
            long remaining,
            Function<MalumRecipeInputMatcher.Assignment<T>, ItemStack> stackFactory) {
        if (slot < 0 || slot >= inventory.getSlots()) {
            return 0L;
        }
        long free = (long) inventory.getSlotLimit(slot) - existing.getCount();
        if (free <= 0) {
            return 0L;
        }

        long max = Math.min(remaining, free);
        for (long count = max; count > 0; count--) {
            var assignment = new MalumRecipeInputMatcher.Assignment<>(value, count);
            var stack = stackFactory.apply(assignment);
            if (stack.isEmpty() || stack.getCount() != count || !inventory.isItemValid(slot, stack)) {
                continue;
            }
            if (existing.isEmpty() || ItemStack.isSameItemSameComponents(existing, stack)) {
                return count;
            }
        }
        return 0L;
    }

    private static void applyToSimulatedSlot(List<ItemStack> simulated, int slot, ItemStack stack) {
        var existing = simulated.get(slot);
        if (existing.isEmpty()) {
            simulated.set(slot, stack.copy());
            return;
        }
        var merged = existing.copy();
        merged.grow(stack.getCount());
        simulated.set(slot, merged);
    }

    private static AABB altarOutputAabb(BlockPos pos) {
        return new AABB(
                pos.getX() - 1.0,
                pos.getY(),
                pos.getZ() - 1.0,
                pos.getX() + 2.0,
                pos.getY() + 2.5,
                pos.getZ() + 2.0);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE_ID)
                .map(type -> (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    private static boolean isMalumLoaded() {
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation malumId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.size() == 1 && outputs.getFirst().what() instanceof AEItemKey;
    }

    record ExtraPlacement<T>(
            MalumRecipeInputMatcher.Assignment<T> assignment,
            BlockPos pos) {
        ExtraPlacement {
            Objects.requireNonNull(assignment, "assignment");
            Objects.requireNonNull(pos, "pos");
        }
    }

    private record RecipeMatch(
            MalumRecipeInputMatcher.Assignment<AEItemKey> main,
            List<MalumRecipeInputMatcher.Assignment<AEItemKey>> spirits,
            List<MalumRecipeInputMatcher.Assignment<AEItemKey>> extras) {
        RecipeMatch {
            Objects.requireNonNull(main, "main");
            spirits = List.copyOf(spirits);
            extras = List.copyOf(extras);
        }
    }

    /** Opaque binding handle returned from {@link #bind}. */
    private record InfusionBindHandle(List<Object> recipes) {
        InfusionBindHandle {
            recipes = List.copyOf(recipes);
        }
    }
}

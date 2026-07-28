package com.moakiee.ae2lt.packaged.logic.multiblock.aa;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Runtime adapter for Actually Additions' Empowerer setup.
 * Pure reflection: Actually Additions is not a compile dependency.
 *
 * Layout: 1 Empowerer + 4 Display Stands exactly 3 blocks away on the
 * horizontal cardinal axes. AA itself uses the same offsets when ticking.
 */
public final class ActuallyAdditionsEmpowererAdapter implements MultiblockAdapter {

    private static final String MOD_ID = "actuallyadditions";
    private static final ResourceLocation EMPOWERER_BLOCK = aaId("empowerer");
    private static final ResourceLocation DISPLAY_STAND_BLOCK = aaId("display_stand");
    private static final ResourceLocation RECIPE_TYPE_ID = aaId("empower");
    private static final int MAX_INPUT_UNITS = 64;

    private static final BlockPos[] DISPLAY_STAND_OFFSETS = {
            new BlockPos(0, 0, 3),
            new BlockPos(-3, 0, 0),
            new BlockPos(0, 0, -3),
            new BlockPos(3, 0, 0),
    };

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isActuallyAdditionsLoaded()
                && blockId(be.getBlockState()).equals(EMPOWERER_BLOCK)
                && AaReflection.isEmpowerer(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.AA_EMPOWERER;
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
        var recipe = findCandidateRecipe(level, pattern);
        if (recipe == null) {
            return null;
        }
        return new BindingResult(new EmpowererBindHandle(recipe), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof EmpowererBindHandle bind)) return false;
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return false;
        if (AaReflection.getProcessTime(be) > 0) return false;

        var inv = AaReflection.getHandler(be);
        if (inv == null || inv.getSlots() < 1 || !inv.getStackInSlot(0).isEmpty()) return false;

        var stands = findEmptyDisplayStands(level, mainPos);
        if (stands == null) return false;
        return standsHaveTickEnergy(level, stands, bind.recipe());
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof EmpowererBindHandle bind)) return null;
        var standPositions = findEmptyDisplayStands(level, mainPos);
        if (standPositions == null) {
            return null;
        }

        var units = expandInputUnits(inputs);
        if (units == null || units.size() != 5) {
            return null;
        }

        var match = assignInputsToRecipe(bind.recipe(), units);
        if (match == null) {
            return null;
        }

        var targets = new ArrayList<TargetSlot>(5);
        targets.add(new TargetSlot(
                level, mainPos, null,
                List.of(match.base().toGenericStack()),
                InsertionStrategy.CUSTOM,
                empowererInserter(level, mainPos, match.base())));

        for (int i = 0; i < match.modifiers().size(); i++) {
            var unit = match.modifiers().get(i);
            var standPos = standPositions.get(i);
            targets.add(new TargetSlot(
                    level, standPos, null,
                    List.of(unit.toGenericStack()),
                    InsertionStrategy.CUSTOM,
                    displayStandInserter(level, standPos, unit)));
        }

        return new DispatchPlan(List.copyOf(targets), null);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)
                || AaReflection.getProcessTime(be) > 0) {
            return List.of();
        }

        if (!displayStandsAreEmpty(level, mainPos)) {
            return List.of();
        }

        var handler = AaReflection.getHandler(be);
        if (handler == null || handler.getSlots() < 1) {
            return List.of();
        }

        var stack = handler.getStackInSlot(0);
        if (stack.isEmpty() || AaReflection.isPossibleInput(stack)) {
            return List.of();
        }

        var key = AEItemKey.of(stack);
        if (!allowsAutoReturn(level, mainPos, filter, key)) {
            return List.of();
        }

        var extracted = handler.extractItem(0, stack.getCount(), false);
        if (extracted.isEmpty()) {
            return List.of();
        }
        return List.of(new GenericStack(AEItemKey.of(extracted), extracted.getCount()));
    }

    /**
     * Bind-time recipe search: locks the recipe by pattern.outputs and the
     * structural shape (input ingredient + 4 modifier ingredients).
     */
    @Nullable
    private static Object findCandidateRecipe(ServerLevel level, IPatternDetails pattern) {
        for (var holder : recipes(level)) {
            var recipe = holder.value();
            var result = resultItem(recipe, level);
            if (result == null || result.isEmpty() || !outputMatches(pattern, result)) {
                continue;
            }
            if (AaReflection.getInput(recipe) == null) {
                continue;
            }
            var modifiers = AaReflection.getModifiers(recipe);
            if (modifiers == null || modifiers.size() != 4) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    /**
     * Per-push input assignment against the recipe locked in by {@link #bind}.
     */
    @Nullable
    private static RecipeMatch assignInputsToRecipe(Object recipe, List<PlannedUnit> units) {
        var input = AaReflection.getInput(recipe);
        var modifiers = AaReflection.getModifiers(recipe);
        if (input == null || modifiers == null || modifiers.size() != 4) {
            return null;
        }

        Predicate<PlannedUnit> basePredicate = unit -> input.test(unit.stack());
        var modifierPredicates = new ArrayList<Predicate<PlannedUnit>>(4);
        for (var modifier : modifiers) {
            modifierPredicates.add(unit -> modifier.test(unit.stack()));
        }

        var match = EmpowererInputMatcher.match(units, basePredicate, modifierPredicates);
        if (match == null) {
            return null;
        }
        if (!AaReflection.recipeMatches(recipe, match.base(), match.modifiers())) {
            return null;
        }
        return new RecipeMatch(recipe, match.base(), match.modifiers());
    }

    @Nullable
    private static List<BlockPos> findEmptyDisplayStands(ServerLevel level, BlockPos mainPos) {
        var positions = new ArrayList<BlockPos>(DISPLAY_STAND_OFFSETS.length);
        for (var offset : DISPLAY_STAND_OFFSETS) {
            var pos = mainPos.offset(offset.getX(), offset.getY(), offset.getZ());
            if (!level.isLoaded(pos)) {
                return null;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(DISPLAY_STAND_BLOCK)
                    || !AaReflection.isDisplayStand(be)) {
                return null;
            }
            var handler = AaReflection.getHandler(be);
            if (handler == null || handler.getSlots() < 1 || !handler.getStackInSlot(0).isEmpty()) {
                return null;
            }
            positions.add(pos);
        }
        return List.copyOf(positions);
    }

    private static boolean displayStandsAreEmpty(ServerLevel level, BlockPos mainPos) {
        for (var offset : DISPLAY_STAND_OFFSETS) {
            var pos = mainPos.offset(offset.getX(), offset.getY(), offset.getZ());
            if (!level.isLoaded(pos)) {
                return false;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(DISPLAY_STAND_BLOCK)
                    || !AaReflection.isDisplayStand(be)) {
                return false;
            }
            var handler = AaReflection.getHandler(be);
            if (handler == null || handler.getSlots() < 1 || !handler.getStackInSlot(0).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean standsHaveTickEnergy(ServerLevel level, List<BlockPos> standPositions,
                                                Object recipe) {
        int time = AaReflection.getTime(recipe);
        if (time <= 0) {
            return false;
        }
        int energyPerTick = AaReflection.getEnergyPerStand(recipe) / time;
        if (energyPerTick <= 0) {
            return true;
        }

        for (var pos : standPositions) {
            var be = level.getBlockEntity(pos);
            if (be == null || AaReflection.getEnergyStored(be) < energyPerTick) {
                return false;
            }
        }
        return true;
    }

    private static BiFunction<GenericStack, Actionable, Long> empowererInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(EMPOWERER_BLOCK)
                    || !AaReflection.isEmpowerer(be)
                    || AaReflection.getProcessTime(be) > 0) {
                return 0L;
            }
            var handler = AaReflection.getHandler(be);
            if (!(handler instanceof IItemHandlerModifiable modifiable)
                    || modifiable.getSlots() < 1
                    || !modifiable.getStackInSlot(0).isEmpty()) {
                return 0L;
            }
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(0, unit.stack());
            }
            return 1L;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> displayStandInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(DISPLAY_STAND_BLOCK)
                    || !AaReflection.isDisplayStand(be)) {
                return 0L;
            }
            var handler = AaReflection.getHandler(be);
            if (!(handler instanceof IItemHandlerModifiable modifiable)
                    || modifiable.getSlots() < 1
                    || !modifiable.getStackInSlot(0).isEmpty()) {
                return 0L;
            }
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(0, unit.stack());
            }
            return 1L;
        };
    }

    @Nullable
    private static List<PlannedUnit> expandInputUnits(KeyCounter[] inputs) {
        var units = new ArrayList<PlannedUnit>();
        long total = 0;
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                total += amount;
                if (total > MAX_INPUT_UNITS) {
                    return null;
                }
                for (long i = 0; i < amount; i++) {
                    units.add(new PlannedUnit(itemKey));
                }
            }
        }
        return units;
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.size() == 1 && outputs.getFirst().what() instanceof AEItemKey;
    }

    private static boolean outputMatches(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.size() != 1) {
            return false;
        }
        var expected = outputs.getFirst();
        if (!(expected.what() instanceof AEItemKey expectedKey) || expected.amount() != result.getCount()) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return outputMode(pattern, 0) == MatchMode.ID_ONLY
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    private static MatchMode outputMode(IPatternDetails pattern, int outputIndex) {
        if (pattern instanceof OverloadedProviderOnlyPatternDetails overload) {
            var outputs = overload.overloadPatternDetailsView().outputs();
            if (outputIndex >= 0 && outputIndex < outputs.size()) {
                return outputs.get(outputIndex).matchMode();
            }
        }
        return MatchMode.STRICT;
    }

    @Nullable
    private static ItemStack resultItem(Object recipe, ServerLevel level) {
        if (!(recipe instanceof Recipe<?> r)) {
            return null;
        }
        try {
            return r.getResultItem(level.registryAccess());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean matchesPlannedStack(GenericStack stack, PlannedUnit unit) {
        return stack.amount() == 1 && unit.key().equals(stack.what());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE_ID)
                .map(type -> (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    private static boolean isActuallyAdditionsLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation aaId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private record PlannedUnit(AEItemKey key) {
        PlannedUnit {
            Objects.requireNonNull(key, "key");
        }

        ItemStack stack() {
            return key.toStack(1);
        }

        GenericStack toGenericStack() {
            return new GenericStack(key, 1);
        }
    }

    private record RecipeMatch(Object recipe, PlannedUnit base, List<PlannedUnit> modifiers) {
    }

    /** Opaque binding handle returned from {@link #bind}. */
    private record EmpowererBindHandle(Object recipe) {
    }

    private static final class AaReflection {
        private static final String EMPOWERER_CLASS =
                "de.ellpeck.actuallyadditions.mod.tile.TileEntityEmpowerer";
        private static final String DISPLAY_STAND_CLASS =
                "de.ellpeck.actuallyadditions.mod.tile.TileEntityDisplayStand";
        private static final String INVENTORY_BASE_CLASS =
                "de.ellpeck.actuallyadditions.mod.tile.TileEntityInventoryBase";
        private static final String EMPOWERER_RECIPE_CLASS =
                "de.ellpeck.actuallyadditions.mod.crafting.EmpowererRecipe";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Class<?> empowererClass;
        private static volatile @Nullable Class<?> displayStandClass;
        private static volatile @Nullable Class<?> empowererRecipeClass;
        private static volatile @Nullable Field invField;
        private static volatile @Nullable Field processTimeField;
        private static volatile @Nullable Field storageField;
        private static volatile @Nullable Method isPossibleInputMethod;
        private static volatile @Nullable Method recipeMatchesMethod;
        private static volatile @Nullable Method getInputMethod;
        private static volatile @Nullable Method getStandOneMethod;
        private static volatile @Nullable Method getStandTwoMethod;
        private static volatile @Nullable Method getStandThreeMethod;
        private static volatile @Nullable Method getStandFourMethod;
        private static volatile @Nullable Method getTimeMethod;
        private static volatile @Nullable Method getEnergyPerStandMethod;

        static boolean isEmpowerer(Object o) {
            ensureLookup();
            return empowererClass != null && empowererClass.isInstance(o);
        }

        static boolean isDisplayStand(Object o) {
            ensureLookup();
            return displayStandClass != null && displayStandClass.isInstance(o);
        }

        @Nullable
        static IItemHandler getHandler(BlockEntity be) {
            ensureLookup();
            if (invField == null) {
                return null;
            }
            try {
                var value = invField.get(be);
                return value instanceof IItemHandler h ? h : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static int getProcessTime(BlockEntity be) {
            ensureLookup();
            if (processTimeField == null || !isEmpowerer(be)) {
                return 0;
            }
            try {
                return processTimeField.getInt(be);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        static boolean isPossibleInput(ItemStack stack) {
            ensureLookup();
            if (isPossibleInputMethod == null) {
                return false;
            }
            try {
                var value = isPossibleInputMethod.invoke(null, stack);
                return value instanceof Boolean b && b;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        static int getEnergyStored(BlockEntity be) {
            ensureLookup();
            if (storageField == null || !isDisplayStand(be)) {
                return 0;
            }
            try {
                var value = storageField.get(be);
                return value instanceof IEnergyStorage storage ? storage.getEnergyStored() : 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        @Nullable
        static Ingredient getInput(Object recipe) {
            ensureLookup();
            if (getInputMethod == null || !isEmpowererRecipe(recipe)) {
                return null;
            }
            try {
                var value = getInputMethod.invoke(recipe);
                return value instanceof Ingredient ingredient ? ingredient : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        static List<Ingredient> getModifiers(Object recipe) {
            ensureLookup();
            if (!isEmpowererRecipe(recipe)
                    || getStandOneMethod == null
                    || getStandTwoMethod == null
                    || getStandThreeMethod == null
                    || getStandFourMethod == null) {
                return null;
            }
            try {
                var one = getStandOneMethod.invoke(recipe);
                var two = getStandTwoMethod.invoke(recipe);
                var three = getStandThreeMethod.invoke(recipe);
                var four = getStandFourMethod.invoke(recipe);
                if (one instanceof Ingredient i1
                        && two instanceof Ingredient i2
                        && three instanceof Ingredient i3
                        && four instanceof Ingredient i4) {
                    return List.of(i1, i2, i3, i4);
                }
                return null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static boolean recipeMatches(Object recipe, PlannedUnit base, List<PlannedUnit> modifiers) {
            ensureLookup();
            if (recipeMatchesMethod == null || !isEmpowererRecipe(recipe) || modifiers.size() != 4) {
                return false;
            }
            try {
                var value = recipeMatchesMethod.invoke(recipe,
                        base.stack(),
                        modifiers.get(0).stack(),
                        modifiers.get(1).stack(),
                        modifiers.get(2).stack(),
                        modifiers.get(3).stack());
                return value instanceof Boolean b && b;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        static int getTime(Object recipe) {
            ensureLookup();
            if (getTimeMethod == null || !isEmpowererRecipe(recipe)) {
                return 0;
            }
            try {
                return (Integer) getTimeMethod.invoke(recipe);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        static int getEnergyPerStand(Object recipe) {
            ensureLookup();
            if (getEnergyPerStandMethod == null || !isEmpowererRecipe(recipe)) {
                return 0;
            }
            try {
                return (Integer) getEnergyPerStandMethod.invoke(recipe);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        private static boolean isEmpowererRecipe(Object recipe) {
            return empowererRecipeClass != null && empowererRecipeClass.isInstance(recipe);
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (AaReflection.class) {
                if (lookupDone) {
                    return;
                }
                try {
                    doLookup();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                } finally {
                    lookupDone = true;
                }
            }
        }

        private static void doLookup() throws ReflectiveOperationException {
            empowererClass = Class.forName(EMPOWERER_CLASS);
            displayStandClass = Class.forName(DISPLAY_STAND_CLASS);
            empowererRecipeClass = Class.forName(EMPOWERER_RECIPE_CLASS);
            var inventoryBaseClass = Class.forName(INVENTORY_BASE_CLASS);

            invField = inventoryBaseClass.getField("inv");
            processTimeField = empowererClass.getField("processTime");
            storageField = displayStandClass.getField("storage");

            isPossibleInputMethod = empowererClass.getMethod("isPossibleInput", ItemStack.class);
            recipeMatchesMethod = empowererRecipeClass.getMethod("matches",
                    ItemStack.class, ItemStack.class, ItemStack.class, ItemStack.class, ItemStack.class);
            getInputMethod = empowererRecipeClass.getMethod("getInput");
            getStandOneMethod = empowererRecipeClass.getMethod("getStandOne");
            getStandTwoMethod = empowererRecipeClass.getMethod("getStandTwo");
            getStandThreeMethod = empowererRecipeClass.getMethod("getStandThree");
            getStandFourMethod = empowererRecipeClass.getMethod("getStandFour");
            getTimeMethod = empowererRecipeClass.getMethod("getTime");
            getEnergyPerStandMethod = empowererRecipeClass.getMethod("getEnergyPerStand");
        }
    }
}

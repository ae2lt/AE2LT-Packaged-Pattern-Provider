package com.moakiee.ae2lt.packaged.logic.multiblock.ma;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.logic.multiblock.AcceptedInsertion;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterRecipeTypes;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchCommitException;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterBlocks;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;
import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;

/**
 * Runtime adapter for Mystical Agriculture's Infusion Altar (seed crafting).
 * Pure reflection: MA is not a compile dependency.
 *
 * Layout: 1 altar + 8 pedestals at fixed offsets
 *   (+/-3,0,0), (0,0,+/-3), (+/-2,0,+/-2).
 * Two sets need at least 7 blocks apart to both assemble, so the hardcoded
 * offsets cannot pull from a neighboring set.
 */
public final class InfusionAltarAdapter implements MultiblockAdapter {

    private static final String MOD_ID = "mysticalagriculture";
    private static final ResourceLocation ALTAR_BLOCK = maId("infusion_altar");
    private static final ResourceLocation PEDESTAL_BLOCK = maId("infusion_pedestal");
    private static final ResourceLocation RECIPE_TYPE_ID = maId("infusion");
    private static final int MAX_INPUT_UNITS = 128;

    private static final BlockPos[] PEDESTAL_OFFSETS = {
            new BlockPos(3, 0, 0),
            new BlockPos(0, 0, 3),
            new BlockPos(-3, 0, 0),
            new BlockPos(0, 0, -3),
            new BlockPos(2, 0, 2),
            new BlockPos(2, 0, -2),
            new BlockPos(-2, 0, 2),
            new BlockPos(-2, 0, -2),
    };

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isMaLoaded()
                && MaReflection.isReady()
                && blockId(be.getBlockState()).equals(ALTAR_BLOCK)
                && MaReflection.isInfusionAltar(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.MA_INFUSION;
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
        var recipes = findCandidateRecipes(level, pattern);
        if (recipes.isEmpty()) {
            return null;
        }
        return new BindingResult(new InfusionBindHandle(recipes), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof InfusionBindHandle) || !MaReflection.isReady()) {
            return false;
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return false;
        }
        if (MaReflection.getProgress(be) > 0) {
            return false;
        }
        var altarInv = MaReflection.getHandler(be);
        if (altarInv == null || altarInv.getSlots() < 2) {
            return false;
        }
        if (!altarInv.getStackInSlot(0).isEmpty() || !altarInv.getStackInSlot(1).isEmpty()) {
            return false;
        }
        return findEmptyPedestals(level, mainPos) != null;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof InfusionBindHandle bind) || !MaReflection.isReady()) {
            return null;
        }
        var pedestals = findEmptyPedestals(level, mainPos);
        if (pedestals == null) {
            return null;
        }

        var units = expandInputUnits(inputs);
        if (units == null || units.isEmpty()) {
            return null;
        }

        var match = CandidateRecipeSelector.firstMatch(
                bind.recipes(), recipe -> assignInputsToRecipe(recipe, units, level));
        if (match == null) {
            return null;
        }

        var targets = new ArrayList<TargetSlot>(1 + match.pedestalAssignments().size());
        targets.add(new TargetSlot(
                level, mainPos, null,
                List.of(match.altar().toGenericStack()),
                InsertionStrategy.CUSTOM,
                altarInserter(level, mainPos, match.altar())));

        for (var assignment : match.pedestalAssignments()) {
            var pedestalPos = pedestals.get(assignment.slot());
            var unit = assignment.unit();
            targets.add(new TargetSlot(
                    level, pedestalPos, null,
                    List.of(unit.toGenericStack()),
                    InsertionStrategy.CUSTOM,
                    pedestalInserter(level, pedestalPos, unit)));
        }

        return new DispatchPlan(
                List.copyOf(targets),
                () -> MaReflection.activateOrThrow(level, mainPos),
                (accepted, recovered) -> recoverUnstartedDispatch(
                        level, mainPos, targets, accepted, recovered));
    }

    private static void recoverUnstartedDispatch(
            ServerLevel level,
            BlockPos mainPos,
            List<TargetSlot> targets,
            List<AcceptedInsertion> acceptedInsertions,
            Consumer<GenericStack> recovered) {
        var altar = level.getBlockEntity(mainPos);
        if (altar == null || !MaReflection.isDefinitelyInactive(altar)) {
            return;
        }
        for (var accepted : acceptedInsertions) {
            int targetIndex = targets.indexOf(accepted.target());
            if (targetIndex < 0) {
                continue;
            }
            var be = level.getBlockEntity(accepted.target().pos());
            boolean expectedTarget = be != null && (targetIndex == 0
                    ? blockId(be.getBlockState()).equals(ALTAR_BLOCK)
                            && MaReflection.isInfusionAltar(be)
                    : blockId(be.getBlockState()).equals(PEDESTAL_BLOCK)
                            && MaReflection.isInfusionPedestal(be));
            if (!expectedTarget) {
                continue;
            }
            var stack = recoverExactInsertion(
                    MaReflection.getHandler(be), 0, accepted.stack());
            if (stack != null) {
                recovered.accept(stack);
            }
        }
    }

    @Nullable
    private static GenericStack recoverExactInsertion(
            @Nullable IItemHandler inventory, int slot, GenericStack accepted) {
        if (inventory == null
                || slot < 0
                || slot >= inventory.getSlots()
                || !(accepted.what() instanceof AEItemKey expectedKey)
                || accepted.amount() <= 0
                || accepted.amount() > Integer.MAX_VALUE) {
            return null;
        }
        var current = inventory.getStackInSlot(slot);
        var expected = expectedKey.toStack((int) accepted.amount());
        if (current.isEmpty()
                || current.getCount() != expected.getCount()
                || !ItemStack.isSameItemSameTags(current, expected)) {
            return null;
        }
        var simulated = inventory.extractItem(slot, current.getCount(), true);
        if (simulated.isEmpty()
                || simulated.getCount() != current.getCount()
                || !ItemStack.isSameItemSameTags(simulated, current)) {
            return null;
        }
        var extracted = inventory.extractItem(slot, current.getCount(), false);
        if (extracted.isEmpty() || !ItemStack.isSameItemSameTags(extracted, current)) {
            return null;
        }
        return new GenericStack(AEItemKey.of(extracted), extracted.getCount());
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return List.of();
        }
        if (MaReflection.getProgress(be) > 0) {
            return List.of();
        }

        var handler = MaReflection.getHandler(be);
        if (handler == null || handler.getSlots() < 2) {
            return List.of();
        }

        var stack = handler.getStackInSlot(1);
        if (stack.isEmpty()) {
            return List.of();
        }

        var key = AEItemKey.of(stack);
        if (!allowsAutoReturn(level, mainPos, filter, key)) {
            return List.of();
        }

        var extracted = handler.extractItem(1, stack.getCount(), false);
        if (extracted.isEmpty()) {
            return List.of();
        }
        return List.of(new GenericStack(AEItemKey.of(extracted), extracted.getCount()));
    }

    /** Bind-time recipe search retains every structurally valid output match. */
    private static List<Object> findCandidateRecipes(ServerLevel level, IPatternDetails pattern) {
        var candidates = new ArrayList<Object>();
        for (var recipe : recipes(level)) {
            var result = resultItem(recipe, level);
            if (result == null || result.isEmpty() || !outputMatches(pattern, result)) {
                continue;
            }
            candidates.add(recipe);
        }
        candidates.sort(java.util.Comparator.comparing(InfusionAltarAdapter::recipeId));
        return List.copyOf(candidates);
    }

    /** Per-push input assignment chooses the compatible retained recipe. */
    @Nullable
    private static RecipeMatch assignInputsToRecipe(Object recipe, List<PlannedUnit> units,
                                                    ServerLevel level) {
        var ingredients = MaReflection.getIngredients(recipe);
        if (ingredients == null || ingredients.size() != 9 || ingredients.get(0).isEmpty()) {
            return null;
        }

        var constraints = new ArrayList<java.util.function.Predicate<PlannedUnit>>(9);
        for (var ingredient : ingredients) {
            constraints.add(ingredient.isEmpty() ? null : unit -> ingredient.test(unit.stack()));
        }

        var layout = InfusionInputMatcher.match(units, constraints);
        if (layout == null || layout.get(0) == null) {
            return null;
        }

        var craftingInput = buildCraftingContainer(layout);
        if (!recipeMatches(recipe, craftingInput, level)) {
            return null;
        }

        var pedestalAssignments = new ArrayList<PedestalAssignment>();
        for (int slot = 1; slot < layout.size(); slot++) {
            var unit = layout.get(slot);
            if (unit != null) {
                pedestalAssignments.add(new PedestalAssignment(slot - 1, unit));
            }
        }
        return new RecipeMatch(layout.get(0), List.copyOf(pedestalAssignments));
    }

    /** Never opened; only satisfies {@link TransientCraftingContainer}'s constructor. */
    private static final AbstractContainerMenu DUMMY_MENU = new AbstractContainerMenu(null, -1) {
        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return false;
        }
    };

    private static CraftingContainer buildCraftingContainer(List<@Nullable PlannedUnit> layout) {
        var stacks = NonNullList.withSize(9, ItemStack.EMPTY);
        for (int slot = 0; slot < layout.size() && slot < stacks.size(); slot++) {
            var unit = layout.get(slot);
            if (unit != null) {
                stacks.set(slot, unit.stack());
            }
        }
        return transientCraftingContainer(3, 3, stacks);
    }

    /**
     * Builds a detached crafting grid to match recipes against.
     *
     * <p>1.21 offers {@code CraftingContainer.of(w, h, stacks)}; on 1.20.1 the
     * equivalent is a {@link TransientCraftingContainer} bound to a dummy menu,
     * which is what vanilla itself uses for off-screen recipe lookups.
     */
    private static CraftingContainer transientCraftingContainer(
            int width, int height, NonNullList<ItemStack> stacks) {
        var container = new TransientCraftingContainer(DUMMY_MENU, width, height);
        for (int slot = 0; slot < stacks.size() && slot < container.getContainerSize(); slot++) {
            container.setItem(slot, stacks.get(slot));
        }
        return container;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean recipeMatches(Object recipe, CraftingContainer input, ServerLevel level) {
        try {
            return recipe instanceof Recipe<?> r && ((Recipe) r).matches(input, level);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
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

    private static String recipeId(Object recipe) {
        if (recipe instanceof Recipe<?> r) {
            try {
                return r.getId().toString();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        return recipe.getClass().getName();
    }

    @Nullable
    private static List<BlockPos> findEmptyPedestals(ServerLevel level, BlockPos mainPos) {
        var positions = new ArrayList<BlockPos>(PEDESTAL_OFFSETS.length);
        for (var offset : PEDESTAL_OFFSETS) {
            var pos = mainPos.offset(offset.getX(), offset.getY(), offset.getZ());
            if (!level.isLoaded(pos)) {
                return null;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(PEDESTAL_BLOCK)
                    || !MaReflection.isInfusionPedestal(be)) {
                return null;
            }
            var handler = MaReflection.getHandler(be);
            if (handler == null || handler.getSlots() < 1) {
                return null;
            }
            if (!handler.getStackInSlot(0).isEmpty()) {
                return null;
            }
            positions.add(pos);
        }
        return positions;
    }

    private static BiFunction<GenericStack, Actionable, Long> altarInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(ALTAR_BLOCK)
                    || !MaReflection.isInfusionAltar(be)) {
                return 0L;
            }
            var handler = MaReflection.getHandler(be);
            if (!(handler instanceof IItemHandlerModifiable modifiable)
                    || modifiable.getSlots() < 2
                    || !modifiable.getStackInSlot(0).isEmpty()
                    || !modifiable.getStackInSlot(1).isEmpty()) {
                return 0L;
            }
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(0, unit.stack());
            }
            return 1L;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> pedestalInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(PEDESTAL_BLOCK)
                    || !MaReflection.isInfusionPedestal(be)) {
                return 0L;
            }
            var handler = MaReflection.getHandler(be);
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
        units.sort(java.util.Comparator.comparing(unit -> keyOrder(unit.key())));
        return units;
    }

    private static String keyOrder(AEItemKey key) {
        return key.getId() + "\u0000" + key;
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.length == 1 && outputs[0].what() instanceof AEItemKey;
    }

    private static boolean outputMatches(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey) || expected.amount() != result.getCount()) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    private static boolean matchesPlannedStack(GenericStack stack, PlannedUnit unit) {
        return stack.amount() == 1 && unit.key().equals(stack.what());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Recipe<?>> recipes(ServerLevel level) {
        var type = AdapterRecipeTypes.find(RECIPE_TYPE_ID);
        if (type == null) {
            return List.of();
        }
        return (List<Recipe<?>>) (List<?>) level.getRecipeManager()
                .getAllRecipesFor((RecipeType) type);
    }

    private static boolean isMaLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) { return AdapterBlocks.idOf(state); }

    private static ResourceLocation maId(String path) {
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

    private record PedestalAssignment(int slot, PlannedUnit unit) {
    }

    private record RecipeMatch(PlannedUnit altar, List<PedestalAssignment> pedestalAssignments) {
    }

    /** Opaque binding handle returned from {@link #bind}. */
    private record InfusionBindHandle(List<Object> recipes) {
    }

    static final class MaReflection {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger("ae2ltpp/ma-infusion-reflection");

        private static final String ALTAR_CLASS =
                "com.blakebr0.mysticalagriculture.tileentity.InfusionAltarTileEntity";
        private static final String PEDESTAL_CLASS =
                "com.blakebr0.mysticalagriculture.tileentity.InfusionPedestalTileEntity";
        private static final String INFUSION_RECIPE_API_CLASS =
                "com.blakebr0.mysticalagriculture.api.crafting.IInfusionRecipe";
        private static final String ACTIVATABLE_CLASS =
                "com.blakebr0.mysticalagriculture.util.IActivatable";
        private static final String BASE_INVENTORY_CLASS =
                "com.blakebr0.cucumber.tileentity.BaseInventoryTileEntity";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Class<?> altarClass;
        private static volatile @Nullable Class<?> pedestalClass;
        private static volatile @Nullable Class<?> infusionRecipeApiClass;
        private static volatile @Nullable Class<?> activatableClass;
        private static volatile @Nullable Method getInventoryMethod;
        private static volatile @Nullable Method activateMethod;
        private static volatile @Nullable Method isActiveMethod;
        private static volatile @Nullable Field progressField;

        static boolean isReady() {
            ensureLookup();
            return altarClass != null
                    && pedestalClass != null
                    && infusionRecipeApiClass != null
                    && activatableClass != null
                    && getInventoryMethod != null
                    && activateMethod != null
                    && isActiveMethod != null
                    && progressField != null;
        }

        static boolean isInfusionAltar(Object o) {
            ensureLookup();
            return isReady() && altarClass.isInstance(o);
        }

        static boolean isInfusionPedestal(Object o) {
            ensureLookup();
            return isReady() && pedestalClass.isInstance(o);
        }

        @Nullable
        static IItemHandler getHandler(BlockEntity be) {
            ensureLookup();
            if (getInventoryMethod == null) {
                return null;
            }
            try {
                var value = getInventoryMethod.invoke(be);
                return value instanceof IItemHandler h ? h : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static int getProgress(BlockEntity be) {
            ensureLookup();
            if (progressField == null) {
                return Integer.MAX_VALUE;
            }
            try {
                return progressField.getInt(be);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return Integer.MAX_VALUE;
            }
        }

        static void activateOrThrow(ServerLevel level, BlockPos pos) {
            ensureLookup();
            var be = level.getBlockEntity(pos);
            if (activateMethod == null
                    || isActiveMethod == null
                    || activatableClass == null
                    || be == null
                    || !activatableClass.isInstance(be)) {
                throw new DispatchCommitException("MA infusion activation API is unavailable");
            }
            try {
                activateMethod.invoke(be);
                if (!Boolean.TRUE.equals(isActiveMethod.invoke(be))) {
                    throw new DispatchCommitException("MA infusion altar remained inactive");
                }
            } catch (DispatchCommitException e) {
                throw e;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                throw new DispatchCommitException("MA infusion activation failed: " + e);
            }
        }

        static boolean isDefinitelyInactive(BlockEntity be) {
            ensureLookup();
            if (isActiveMethod == null
                    || activatableClass == null
                    || !activatableClass.isInstance(be)) {
                return false;
            }
            try {
                return Boolean.FALSE.equals(isActiveMethod.invoke(be));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        @Nullable
        static List<net.minecraft.world.item.crafting.Ingredient> getIngredients(Object recipe) {
            ensureLookup();
            if (!isReady() || !infusionRecipeApiClass.isInstance(recipe)
                    || !(recipe instanceof net.minecraft.world.item.crafting.Recipe<?> vanillaRecipe)) {
                return null;
            }
            try {
                var ingredients = vanillaRecipe.getIngredients();
                return List.copyOf(ingredients);
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (MaReflection.class) {
                if (lookupDone) {
                    return;
                }
                doLookup();
                lookupDone = true;
            }
        }

        private static void doLookup() {
            altarClass = tryClass(ALTAR_CLASS);
            pedestalClass = tryClass(PEDESTAL_CLASS);
            activatableClass = tryClass(ACTIVATABLE_CLASS);
            infusionRecipeApiClass = tryClass(INFUSION_RECIPE_API_CLASS);

            var baseInventoryClass = tryClass(BASE_INVENTORY_CLASS);
            if (baseInventoryClass != null) {
                getInventoryMethod = tryMethod(baseInventoryClass, "getInventory");
            }
            if (activatableClass != null) {
                activateMethod = tryMethod(activatableClass, "activate");
                isActiveMethod = tryMethod(activatableClass, "isActive");
            }
            // Recipe#getIngredients() is invoked through the vanilla interface;
            // no nonexistent MA altar-ingredient reflection probe is needed.
            if (altarClass != null) {
                progressField = tryField(altarClass, "progress");
            }

            LOG.info("MA infusion reflection ready: ready={} altar={} pedestal={} activatable={} infusionRecipe={} inventory={} activate={} isActive={} ingredients=vanilla progress={}",
                    altarClass != null && pedestalClass != null
                            && activatableClass != null && infusionRecipeApiClass != null
                            && getInventoryMethod != null && activateMethod != null
                            && isActiveMethod != null && progressField != null,
                    altarClass != null,
                    pedestalClass != null,
                    activatableClass != null,
                    infusionRecipeApiClass != null,
                    getInventoryMethod != null,
                    activateMethod != null,
                    isActiveMethod != null,
                    progressField != null);
        }

        @Nullable
        private static Class<?> tryClass(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
                LOG.warn("MA infusion class lookup failed: {} ({})", name, e.toString());
                return null;
            }
        }

        @Nullable
        private static Method tryMethod(Class<?> declaring, String name, Class<?>... params) {
            try {
                return declaring.getMethod(name, params);
            } catch (NoSuchMethodException | RuntimeException | LinkageError e) {
                LOG.warn("MA infusion method lookup failed: {}#{} ({})",
                        declaring.getName(), name, e.toString());
                return null;
            }
        }

        @Nullable
        private static Field tryField(Class<?> declaring, String name) {
            try {
                var field = declaring.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException | RuntimeException | LinkageError e) {
                LOG.warn("MA infusion field lookup failed: {}#{} ({})",
                        declaring.getName(), name, e.toString());
                return null;
            }
        }
    }
}

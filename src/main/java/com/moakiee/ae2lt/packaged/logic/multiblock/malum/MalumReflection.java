package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;

final class MalumReflection {

    private static final Logger LOG = LoggerFactory.getLogger("ae2ltpp/malum-reflection");
    private static final String[] ALTAR_CLASSES = {
            "com.sammy.malum.common.block.curiosities.sorcery.spirit_altar.SpiritAltarBlockEntity",
            "com.sammy.malum.common.block.curiosities.spirit_altar.SpiritAltarBlockEntity"
    };
    private static final String[] ALTAR_HELPER_CLASSES = {
            "com.sammy.malum.common.block.curiosities.sorcery.spirit_altar.AltarCraftingHelper",
            "com.sammy.malum.common.block.curiosities.spirit_altar.AltarCraftingHelper"
    };
    private static final String[] CRUCIBLE_CLASSES = {
            "com.sammy.malum.common.block.curiosities.artifice.spirit_crucible.SpiritCrucibleCoreBlockEntity",
            "com.sammy.malum.common.block.curiosities.spirit_crucible.SpiritCrucibleCoreBlockEntity"
    };
    private static final String ACCESS_POINT_CLASS =
            "com.sammy.malum.common.block.storage.IMalumSpecialItemAccessPoint";
    private static final String INFUSION_RECIPE_CLASS =
            "com.sammy.malum.common.recipe.SpiritInfusionRecipe";
    private static final String FOCUSING_RECIPE_CLASS =
            "com.sammy.malum.common.recipe.SpiritFocusingRecipe";
    private static final String SPIRIT_INGREDIENT_CLASS =
            // 1.21 renamed this to SpiritIngredient; 1.20.1 ships SpiritWithCount.
            "com.sammy.malum.core.systems.recipe.SpiritWithCount";

    private static volatile boolean lookupDone;
    private static volatile @Nullable Class<?> altarClass;
    private static volatile @Nullable Class<?> crucibleClass;
    private static volatile @Nullable Class<?> accessPointClass;
    private static volatile @Nullable Class<?> infusionRecipeClass;
    private static volatile @Nullable Class<?> focusingRecipeClass;
    private static volatile @Nullable Class<?> spiritIngredientClass;

    private static volatile @Nullable Field altarInventoryField;
    private static volatile @Nullable Field altarSpiritInventoryField;
    private static volatile @Nullable Field altarExtrasInventoryField;
    private static volatile @Nullable Field altarRecipeField;
    private static volatile @Nullable Field altarCraftingField;
    private static volatile @Nullable Field altarProgressField;
    private static volatile @Nullable Field crucibleInventoryField;
    private static volatile @Nullable Field crucibleSpiritInventoryField;
    private static volatile @Nullable Field crucibleRecipeField;
    private static volatile @Nullable Field crucibleCraftingField;
    private static volatile @Nullable Field crucibleProgressField;
    private static volatile @Nullable Field infusionInputField;
    private static volatile @Nullable Field infusionSpiritsField;
    private static volatile @Nullable Field infusionExtrasField;
    private static volatile @Nullable Field infusionOutputField;
    private static volatile @Nullable Field infusionUseNbtFromInputField;
    private static volatile @Nullable Field focusingInputField;
    private static volatile @Nullable Field focusingSpiritsField;
    private static volatile @Nullable Field focusingOutputField;

    private static volatile @Nullable Method altarRecalculateMethod;
    private static volatile @Nullable Method altarCraftingMethod;
    private static volatile @Nullable Method crucibleUpdateMethod;
    private static volatile @Nullable Method crucibleCraftingMethod;
    private static volatile @Nullable Method capturePedestalsMethod;
    private static volatile @Nullable Method getSuppliedInventoryMethod;
    private static volatile @Nullable Method getAccessPointBlockPosMethod;
    private static volatile @Nullable Method infusionGetOutputMethod;
    private static volatile @Nullable Method focusingGetInputMethod;
    private static volatile @Nullable Method focusingGetSpiritsMethod;
    private static volatile @Nullable Method focusingCreateOutputMethod;
    private static volatile @Nullable Method spiritAsItemStackMethod;

    private MalumReflection() {
    }

    static boolean isSpiritAltar(Object o) {
        ensureLookup();
        return altarClass != null && altarClass.isInstance(o);
    }

    static boolean isSpiritCrucible(Object o) {
        ensureLookup();
        return crucibleClass != null && crucibleClass.isInstance(o);
    }

    static boolean isAltarIdle(BlockEntity be) {
        ensureLookup();
        if (altarRecipeField == null || !isAltarInactive(be)) {
            return false;
        }
        return fieldIsNull(altarRecipeField, be);
    }

    static boolean isCrucibleIdle(BlockEntity be) {
        ensureLookup();
        if (crucibleRecipeField == null
                || crucibleProgressField == null
                || !isSpiritCrucible(be)) {
            return false;
        }
        var progress = fieldValue(crucibleProgressField, be);
        if (!(progress instanceof Number number) || number.doubleValue() > 0) {
            return false;
        }
        if (!optionalFalse(crucibleCraftingField, crucibleCraftingMethod, be)) {
            return false;
        }
        return fieldIsNull(crucibleRecipeField, be);
    }

    static boolean isAltarInactive(BlockEntity be) {
        ensureLookup();
        if (altarCraftingField == null
                && altarCraftingMethod == null
                || altarProgressField == null
                || !isSpiritAltar(be)) {
            return false;
        }
        var crafting = booleanState(altarCraftingField, altarCraftingMethod, be);
        var progress = fieldValue(altarProgressField, be);
        return crafting != null
                && !crafting
                && progress instanceof Number number
                && number.doubleValue() <= 0;
    }

    static boolean isCrucibleInactive(BlockEntity be) {
        ensureLookup();
        if (crucibleProgressField == null || !isSpiritCrucible(be)) {
            return false;
        }
        var progress = fieldValue(crucibleProgressField, be);
        return progress instanceof Number number
                && number.doubleValue() <= 0
                && optionalFalse(crucibleCraftingField, crucibleCraftingMethod, be);
    }

    private static boolean optionalFalse(@Nullable Field field, @Nullable Method method, Object target) {
        if (field == null && method == null) {
            return true;
        }
        var value = booleanState(field, method, target);
        return value != null && !value;
    }

    @Nullable
    private static Boolean booleanState(@Nullable Field field, @Nullable Method method, Object target) {
        var value = fieldValue(field, target);
        if (value instanceof Boolean state) {
            return state;
        }
        if (method != null) {
            var result = ReflectionSupport.invoke(method, target);
            if (result.isPresent() && result.get() instanceof Boolean state) {
                return state;
            }
        }
        return null;
    }

    @Nullable
    static IItemHandlerModifiable altarInventory(BlockEntity be) {
        return itemHandler(altarInventoryField, be);
    }

    @Nullable
    static IItemHandlerModifiable altarSpiritInventory(BlockEntity be) {
        return itemHandler(altarSpiritInventoryField, be);
    }

    @Nullable
    static IItemHandlerModifiable altarExtrasInventory(BlockEntity be) {
        return itemHandler(altarExtrasInventoryField, be);
    }

    @Nullable
    static IItemHandlerModifiable crucibleInventory(BlockEntity be) {
        return itemHandler(crucibleInventoryField, be);
    }

    @Nullable
    static IItemHandlerModifiable crucibleSpiritInventory(BlockEntity be) {
        return itemHandler(crucibleSpiritInventoryField, be);
    }

    static boolean recalculateAltar(ServerLevel level, BlockPos pos,
                                    @Nullable ResourceLocation expectedRecipeId) {
        var be = level.getBlockEntity(pos);
        if (be == null || !isSpiritAltar(be) || altarRecalculateMethod == null) {
            return false;
        }
        // Cache refresh is a mutation-adjacent operation: a throwing call leaves
        // inventory state uncertain and must reach the dispatch boundary.
        refreshInventoryCaches(altarInventory(be));
        refreshInventoryCaches(altarSpiritInventory(be));
        refreshInventoryCaches(altarExtrasInventory(be));
        for (var pedestal : capturePedestals(level, pos)) {
            refreshInventoryCaches(pedestal.inventory());
        }
        try {
            LOG.debug("Spirit Altar recalculate before: dimension={} pos={} state={}",
                    level.dimension().location(), pos, altarStateSummary(be, capturePedestals(level, pos)));
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Malum Spirit Altar state inspection failed before recalculate: dimension={} pos={} error={}",
                    level.dimension().location(), pos, e.toString());
            return false;
        }
        // Activation callers translate false to a commit failure or retry later.
        try {
            ReflectionSupport.invokeMutation(altarRecalculateMethod, be);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Malum Spirit Altar recalculateRecipes invocation failed: dimension={} pos={} be={} error={}",
                    level.dimension().location(), pos, be.getClass().getName(), e.toString());
            throw e;
        }
        try {
            var recipeId = altarRecipeId(be);
            boolean activated = !isAltarIdle(be)
                    && recipeId != null
                    && (expectedRecipeId == null || expectedRecipeId.equals(recipeId));
            LOG.debug("Spirit Altar recalculate after: dimension={} pos={} activated={} recipe={} expected={} state={}",
                    level.dimension().location(), pos, activated, recipeId, expectedRecipeId,
                    altarStateSummary(be, capturePedestals(level, pos)));
            if (!activated) {
                LOG.warn("Malum Spirit Altar recalculateRecipes did not establish expected recipe: dimension={} pos={} "
                                + "expected={} actual={} be={} recipeField={} craftingField={} progressField={}",
                        level.dimension().location(), pos, expectedRecipeId, recipeId, be.getClass().getName(),
                        fieldName(altarRecipeField), fieldName(altarCraftingField), fieldName(altarProgressField));
            }
            return activated;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Malum Spirit Altar state inspection failed after recalculate: dimension={} pos={} error={}",
                    level.dimension().location(), pos, e.toString());
            return false;
        }
    }

    static boolean updateCrucibleRecipe(ServerLevel level, BlockPos pos,
                                         @Nullable ResourceLocation expectedRecipeId) {
        var be = level.getBlockEntity(pos);
        if (be == null || !isSpiritCrucible(be) || crucibleUpdateMethod == null) {
            return false;
        }
        try {
            LOG.debug("Spirit Crucible init before: dimension={} pos={} state={}",
                    level.dimension().location(), pos, crucibleStateSummary(be));
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Malum Spirit Crucible state inspection failed before init: dimension={} pos={} error={}",
                    level.dimension().location(), pos, e.toString());
            return false;
        }
        try {
            ReflectionSupport.invokeMutation(crucibleUpdateMethod, be);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Malum Spirit Crucible init invocation failed: dimension={} pos={} error={}",
                    level.dimension().location(), pos, e.toString());
            throw e;
        }
        try {
            var recipeId = crucibleRecipeId(be);
            boolean matches = recipeId != null
                    && (expectedRecipeId == null || expectedRecipeId.equals(recipeId));
            LOG.debug("Spirit Crucible init after: dimension={} pos={} recipe={} expected={} accepted={} state={}",
                    level.dimension().location(), pos, recipeId, expectedRecipeId, matches,
                    crucibleStateSummary(be));
            if (!matches && expectedRecipeId != null) {
                LOG.warn("Malum Spirit Crucible init did not establish expected recipe: dimension={} pos={} "
                                + "expected={} actual={}",
                        level.dimension().location(), pos, expectedRecipeId, recipeId);
            }
            return matches;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Malum Spirit Crucible state inspection failed after init: dimension={} pos={} error={}",
                    level.dimension().location(), pos, e.toString());
            return false;
        }
    }

    @Nullable
    static ResourceLocation altarRecipeId(BlockEntity be) {
        ensureLookup();
        if (altarRecipeField == null || !isSpiritAltar(be)) {
            return null;
        }
        return recipeId(fieldValue(altarRecipeField, be));
    }

    @Nullable
    static ResourceLocation crucibleRecipeId(BlockEntity be) {
        ensureLookup();
        if (crucibleRecipeField == null || !isSpiritCrucible(be)) {
            return null;
        }
        return recipeId(fieldValue(crucibleRecipeField, be));
    }

    @Nullable
    static ResourceLocation recipeId(@Nullable Object recipe) {
        return recipe instanceof Recipe<?> vanillaRecipe ? vanillaRecipe.getId() : null;
    }

    private static String crucibleStateSummary(BlockEntity be) {
        return "catalyst=" + inventorySummary(crucibleInventory(be))
                + ", spirits=" + inventorySummary(crucibleSpiritInventory(be))
                + ", recipe=" + recipeId(fieldValue(crucibleRecipeField, be))
                + ", crafting=" + booleanState(crucibleCraftingField, crucibleCraftingMethod, be)
                + ", progress=" + fieldValue(crucibleProgressField, be);
    }

    private static String altarStateSummary(BlockEntity be, List<Pedestal> pedestals) {
        var main = altarInventory(be);
        var spirits = altarSpiritInventory(be);
        var extras = altarExtrasInventory(be);
        return "main=" + inventorySummary(main)
                + ", spirits=" + inventorySummary(spirits)
                + ", extras=" + inventorySummary(extras)
                + ", pedestals=" + pedestals.stream()
                .map(pedestal -> pedestal.pos() + ":" + inventorySummary(pedestal.inventory()))
                .toList()
                + ", recipe=" + recipeId(fieldValue(altarRecipeField, be))
                + ", crafting=" + booleanState(altarCraftingField, altarCraftingMethod, be)
                + ", progress=" + fieldValue(altarProgressField, be);
    }

    private static String inventorySummary(@Nullable IItemHandlerModifiable inventory) {
        if (inventory == null) {
            return "unresolved";
        }
        var values = new ArrayList<String>();
        for (int i = 0; i < inventory.getSlots(); i++) {
            var stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                values.add(i + "=" + stack.getItem() + "x" + stack.getCount());
            }
        }
        return "slots=" + inventory.getSlots() + values;
    }

    static boolean insertItem(ServerLevel level, IItemHandlerModifiable inventory, int fallbackSlot, ItemStack stack) {
        return insertItem(level, inventory, fallbackSlot, stack, Actionable.MODULATE);
    }

    /**
     * Inserts into the exact slot used by the adapter. A simulated insertion is
     * deliberately side-effect free; this matters for Lodestone's one-argument
     * API, which performs a real insertion and returns the accepted stack.
     */
    static boolean insertItem(ServerLevel level, IItemHandlerModifiable inventory, int fallbackSlot,
                              ItemStack stack, Actionable mode) {
        if (slotInvalid(inventory, fallbackSlot) || stack.isEmpty()) {
            return false;
        }
        if (!canPlaceInSlot(inventory, fallbackSlot, stack)) {
            return false;
        }
        if (mode == Actionable.SIMULATE) {
            return true;
        }

        // Recovery is keyed to fallbackSlot, so use a direct write whenever the
        // target handler exposes a modifiable slot. This avoids generic APIs
        // selecting another slot and avoids ambiguous return-value conventions.
        if (placeItemInSlot(inventory, fallbackSlot, stack)) {
            return true;
        }

        var insertMethod = ReflectionSupport.findMethodCached(
                inventory.getClass(), "insertItem", ServerLevel.class, ItemStack.class);
        if (insertMethod.isPresent()) {
            var submitted = stack.copy();
            var result = ReflectionSupport.invokeMutation(insertMethod.get(), inventory, level, submitted);
            return result.isPresent()
                    && insertionResultAccepted(result.get(), stack.getCount(), false, submitted);
        }

        insertMethod = ReflectionSupport.findMethodCached(inventory.getClass(), "insertItem", ItemStack.class);
        if (insertMethod.isPresent()) {
            var submitted = stack.copy();
            var result = ReflectionSupport.invokeMutation(insertMethod.get(), inventory, submitted);
            // Lodestone's one-argument method returns the accepted stack.
            return result.isPresent()
                    && insertionResultAccepted(result.get(), stack.getCount(), false, submitted);
        }

        insertMethod = ReflectionSupport.findMethodCached(
                inventory.getClass(), "insertItem", ItemStack.class, boolean.class);
        if (insertMethod.isPresent()) {
            var submitted = stack.copy();
            var result = ReflectionSupport.invokeMutation(insertMethod.get(), inventory, submitted, false);
            return result.isPresent()
                    && insertionResultAccepted(result.get(), stack.getCount(), true, submitted);
        }

        return false;
    }

    private static boolean canPlaceInSlot(IItemHandlerModifiable inventory, int slot, ItemStack stack) {
        if (slotInvalid(inventory, slot)
                || stack.getCount() > inventory.getSlotLimit(slot)
                || !inventory.isItemValid(slot, stack)) {
            return false;
        }
        var existing = inventory.getStackInSlot(slot);
        return existing.isEmpty()
                || ItemStack.isSameItemSameTags(existing, stack)
                && (long) existing.getCount() + stack.getCount() <= inventory.getSlotLimit(slot);
    }

    static boolean placeItemInSlot(IItemHandlerModifiable inventory, int slot, ItemStack stack) {
        if (slotInvalid(inventory, slot) || stack.isEmpty()) {
            return false;
        }
        if (!canPlaceInSlot(inventory, slot, stack)) {
            return false;
        }
        var existing = inventory.getStackInSlot(slot);
        var placed = existing.isEmpty() ? stack.copy() : existing.copy();
        if (!existing.isEmpty()) {
            placed.grow(stack.getCount());
        }
        // Once a setter is entered, even a throwing call may own the input.
        inventory.setStackInSlot(slot, placed);
        refreshInventoryCaches(inventory);
        return true;
    }

    private static boolean slotInvalid(IItemHandlerModifiable inventory, int slot) {
        return slot < 0 || slot >= inventory.getSlots();
    }

    private static boolean insertionResultAccepted(
            Object result,
            int requestedCount,
            boolean resultIsRemainder,
            ItemStack submitted) {
        long interactionAccepted = interactionAcceptedCount(result);
        if (interactionAccepted >= 0) {
            return interactionAccepted >= requestedCount;
        }
        if (result instanceof Boolean accepted) {
            return accepted;
        }
        if (result instanceof ItemStack stack) {
            if (resultIsRemainder) {
                return MalumInsertionSemantics.remainderResult(stack.isEmpty());
            }
            return MalumInsertionSemantics.acceptedStackResult(
                    stack.isEmpty(), stack.getCount(), requestedCount);
        }
        return false;
    }

    private static long interactionAcceptedCount(Object result) {
        var successful = invokeNoArg(result, "wasSuccessful");
        if (successful.isPresent() && successful.get() instanceof Boolean value && !value) {
            return 0L;
        }

        var original = invokeNoArg(result, "original");
        if (original.isPresent() && original.get() instanceof ItemStack stack) {
            return stack.getCount();
        }
        return -1L;
    }

    private static Optional<Object> invokeNoArg(Object target, String methodName) {
        try {
            var method = ReflectionSupport.findMethodCached(target.getClass(), methodName).orElse(null);
            if (method == null) {
                return Optional.empty();
            }
            return Optional.ofNullable(method.invoke(target));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    static void refreshInventoryCaches(@Nullable IItemHandlerModifiable inventory) {
        if (inventory == null) {
            return;
        }
        for (var methodName : List.of("updateCaches", "updateInventoryCaches")) {
            var method = methodInHierarchy(inventory.getClass(), methodName);
            if (method.isEmpty()) {
                continue;
            }
            ReflectionSupport.invokeMutation(method.get(), inventory);
            return;
        }
    }

    static List<Pedestal> capturePedestals(ServerLevel level, BlockPos altarPos) {
        ensureLookup();
        if (capturePedestalsMethod == null
                || getSuppliedInventoryMethod == null
                || getAccessPointBlockPosMethod == null
                || accessPointClass == null) {
            return List.of();
        }

        try {
            var value = capturePedestalsMethod.invoke(null, level, altarPos);
            if (!(value instanceof List<?> list)) {
                return List.of();
            }

            var pedestals = new ArrayList<Pedestal>(list.size());
            for (var accessPoint : list) {
                if (!accessPointClass.isInstance(accessPoint)) {
                    continue;
                }
                var pos = getAccessPointBlockPosMethod.invoke(accessPoint);
                var inventory = getSuppliedInventoryMethod.invoke(accessPoint);
                if (pos instanceof BlockPos blockPos
                        && inventory instanceof IItemHandlerModifiable itemHandler) {
                    pedestals.add(new Pedestal(blockPos, itemHandler));
                }
            }
            return List.copyOf(pedestals);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    @Nullable
    static SizedIngredientView infusionInput(Object recipe) {
        ensureLookup();
        if (!isInfusionRecipe(recipe)) {
            return null;
        }
        return sizedIngredient(fieldValue(infusionInputField, recipe));
    }

    @Nullable
    static List<ItemStack> infusionSpirits(Object recipe) {
        ensureLookup();
        if (!isInfusionRecipe(recipe)) {
            return null;
        }
        return spiritStacks(fieldValue(infusionSpiritsField, recipe));
    }

    @Nullable
    static List<SizedIngredientView> infusionExtras(Object recipe) {
        ensureLookup();
        if (!isInfusionRecipe(recipe)) {
            return null;
        }
        return sizedIngredients(fieldValue(infusionExtrasField, recipe));
    }

    @Nullable
    static ItemStack infusionOutput(Object recipe, ServerLevel level, ItemStack input) {
        ensureLookup();
        if (!isInfusionRecipe(recipe) || infusionGetOutputMethod == null) {
            return null;
        }
        try {
            var value = infusionGetOutputMethod.invoke(recipe, level, input);
            return value instanceof ItemStack stack ? stack.copy() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // fall through to the output field (1.20.1 has no getOutput method)
        }
        var value = fieldValue(infusionOutputField, recipe);
        if (!(value instanceof ItemStack stack)) {
            return null;
        }
        var output = stack.copy();
        if (infusionUseNbtFromInputField != null) {
            var useInputNbt = fieldValue(infusionUseNbtFromInputField, recipe);
            if (!(useInputNbt instanceof Boolean copyInputNbt)) {
                return null;
            }
            if (copyInputNbt && input.hasTag()) {
                output.setTag(input.getTag().copy());
            }
        }
        return output;
    }

    @Nullable
    static Ingredient focusingInput(Object recipe) {
        ensureLookup();
        if (!isFocusingRecipe(recipe)) {
            return null;
        }
        if (focusingGetInputMethod != null) {
            try {
                var value = focusingGetInputMethod.invoke(recipe);
                return value instanceof Ingredient ingredient ? ingredient : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        var value = fieldValue(focusingInputField, recipe);
        return value instanceof Ingredient ingredient ? ingredient : null;
    }

    @Nullable
    static List<ItemStack> focusingSpirits(Object recipe) {
        ensureLookup();
        if (!isFocusingRecipe(recipe)) {
            return null;
        }
        if (focusingGetSpiritsMethod != null) {
            try {
                return spiritStacks(focusingGetSpiritsMethod.invoke(recipe));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        return spiritStacks(fieldValue(focusingSpiritsField, recipe));
    }

    @Nullable
    static ItemStack focusingOutput(Object recipe) {
        ensureLookup();
        if (!isFocusingRecipe(recipe)) {
            return null;
        }
        if (focusingCreateOutputMethod != null) {
            try {
                var value = focusingCreateOutputMethod.invoke(recipe);
                return value instanceof ItemStack stack ? stack.copy() : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        var value = fieldValue(focusingOutputField, recipe);
        return value instanceof ItemStack stack ? stack.copy() : null;
    }

    private static boolean isInfusionRecipe(Object recipe) {
        return infusionRecipeClass != null && infusionRecipeClass.isInstance(recipe);
    }

    private static boolean isFocusingRecipe(Object recipe) {
        return focusingRecipeClass != null && focusingRecipeClass.isInstance(recipe);
    }

    @Nullable
    private static IItemHandlerModifiable itemHandler(@Nullable Field field, Object target) {
        var value = fieldValue(field, target);
        return value instanceof IItemHandlerModifiable itemHandler ? itemHandler : null;
    }

    @Nullable
    private static Object fieldValue(@Nullable Field field, Object target) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean fieldIsNull(Field field, Object target) {
        try {
            return field.get(target) == null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Reads an ingredient-with-count pair off a Malum recipe field.
     *
     * <p>1.20.1 Malum uses Lodestone's own class here rather than a loader-level
     * sized-ingredient type, so both members are resolved by name and the whole
     * lookup degrades to {@code null} when the shape does not match.
     */
    @Nullable
    private static SizedIngredientView sizedIngredient(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Ingredient ingredient) {
            return new SizedIngredientView(ingredient, 1);
        }
        var ingredient = firstMemberOfType(value, Ingredient.class, "ingredient", "getIngredient");
        if (ingredient == null) {
            return null;
        }
        var count = firstMemberOfType(value, Integer.class, "count", "getCount", "amount", "getAmount");
        return count instanceof Integer i && i > 0
                ? new SizedIngredientView((Ingredient) ingredient, i)
                : null;
    }

    @Nullable
    private static Object firstMemberOfType(Object target, Class<?> expected, String... names) {
        for (var name : names) {
            var field = ReflectionSupport.findFieldCached(target.getClass(), name).orElse(null);
            if (field != null) {
                var value = fieldValue(field, target);
                if (expected.isInstance(value)) {
                    return value;
                }
            }
            var method = ReflectionSupport.findMethodCached(target.getClass(), name).orElse(null);
            if (method != null) {
                var value = ReflectionSupport.invoke(method, target).orElse(null);
                if (expected.isInstance(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private static boolean booleanField(@Nullable Field field, Object target) {
        var value = fieldValue(field, target);
        return value instanceof Boolean b && b;
    }

    private static double numberField(@Nullable Field field, Object target) {
        var value = fieldValue(field, target);
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    @Nullable
    private static List<ItemStack> spiritStacks(@Nullable Object value) {
        if (!(value instanceof List<?> list) || spiritAsItemStackMethod == null) {
            return null;
        }
        var stacks = new ArrayList<ItemStack>(list.size());
        for (var spiritIngredient : list) {
            if (spiritIngredientClass == null || !spiritIngredientClass.isInstance(spiritIngredient)) {
                return null;
            }
            try {
                var stack = spiritAsItemStackMethod.invoke(spiritIngredient);
                if (!(stack instanceof ItemStack itemStack) || itemStack.isEmpty()) {
                    return null;
                }
                stacks.add(itemStack.copy());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        return List.copyOf(stacks);
    }

    @Nullable
    private static List<SizedIngredientView> sizedIngredients(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        var ingredients = new ArrayList<SizedIngredientView>(list.size());
        for (var ingredient : list) {
            var view = sizedIngredient(ingredient);
            if (view == null) {
                return null;
            }
            ingredients.add(view);
        }
        return List.copyOf(ingredients);
    }

    private static void ensureLookup() {
        if (lookupDone) {
            return;
        }
        synchronized (MalumReflection.class) {
            if (lookupDone) {
                return;
            }
            try {
                doLookup();
                LOG.info("Malum reflection ready: altar={} crucible={} accessPoint={} infusionRecipe={} focusingRecipe={} spiritIngredient={} "
                                + "altarInventory={} altarSpiritInventory={} altarExtrasInventory={} altarRecipe={} altarCrafting={} altarProgress={} "
                                + "crucibleInventory={} crucibleSpiritInventory={} crucibleRecipe={} crucibleCrafting={} crucibleProgress={} "
                                + "altarRecalculate={} crucibleUpdate={} capturePedestals={} suppliedInventory={} accessPointPos={}",
                        className(altarClass), className(crucibleClass), className(accessPointClass),
                        className(infusionRecipeClass), className(focusingRecipeClass), className(spiritIngredientClass),
                        fieldName(altarInventoryField), fieldName(altarSpiritInventoryField), fieldName(altarExtrasInventoryField),
                        fieldName(altarRecipeField), fieldName(altarCraftingField), fieldName(altarProgressField),
                        fieldName(crucibleInventoryField), fieldName(crucibleSpiritInventoryField), fieldName(crucibleRecipeField),
                        fieldName(crucibleCraftingField), fieldName(crucibleProgressField), methodName(altarRecalculateMethod),
                        methodName(crucibleUpdateMethod), methodName(capturePedestalsMethod), methodName(getSuppliedInventoryMethod),
                        methodName(getAccessPointBlockPosMethod));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("Malum reflection lookup failed: {}", e.toString());
            } finally {
                lookupDone = true;
            }
        }
    }

    private static String className(@Nullable Class<?> type) {
        return type == null ? "unresolved" : type.getName();
    }

    private static String fieldName(@Nullable Field field) {
        return field == null ? "unresolved" : field.getDeclaringClass().getName() + "#" + field.getName();
    }

    private static String methodName(@Nullable Method method) {
        return method == null ? "unresolved" : method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static void doLookup() throws ReflectiveOperationException {
        // Critical class handles: without these the adapters cannot exist at
        // all, so failures keep aborting the rest of the lookup.
        altarClass = requiredClass(ALTAR_CLASSES);
        crucibleClass = requiredClass(CRUCIBLE_CLASSES);
        accessPointClass = requiredClass(ACCESS_POINT_CLASS);
        infusionRecipeClass = requiredClass(INFUSION_RECIPE_CLASS);
        focusingRecipeClass = requiredClass(FOCUSING_RECIPE_CLASS);
        spiritIngredientClass = requiredClass(SPIRIT_INGREDIENT_CLASS);
        var altarHelperClass = requiredClass(ALTAR_HELPER_CLASSES);

        // Everything below is optional per mod version (1.20.1 lacks several
        // 1.21 members); each resolution must not be able to disable the rest.
        altarInventoryField = fieldInHierarchy(altarClass, "inventory");
        altarSpiritInventoryField = fieldInHierarchy(altarClass, "spiritInventory");
        altarExtrasInventoryField = fieldInHierarchy(altarClass, "extrasInventory");
        altarRecipeField = fieldInHierarchy(altarClass, "recipe");
        altarCraftingField = fieldInHierarchy(altarClass, "isCrafting");
        altarProgressField = fieldInHierarchy(altarClass, "progress");
        crucibleInventoryField = fieldInHierarchy(crucibleClass, "inventory");
        crucibleSpiritInventoryField = fieldInHierarchy(crucibleClass, "spiritInventory");
        crucibleRecipeField = fieldInHierarchy(crucibleClass, "recipe");
        crucibleCraftingField = fieldInHierarchy(crucibleClass, "isCrafting");
        crucibleProgressField = fieldInHierarchy(crucibleClass, "progress");
        infusionInputField = fieldQuietly(infusionRecipeClass, "input");
        infusionSpiritsField = fieldInHierarchy(infusionRecipeClass, "spirits");
        infusionExtrasField = fieldQuietly(infusionRecipeClass, "extraItems");
        if (infusionExtrasField == null) {
            infusionExtrasField = fieldInHierarchy(infusionRecipeClass, "extraInputs");
        }
        focusingInputField = fieldQuietly(focusingRecipeClass, "input");
        focusingSpiritsField = fieldInHierarchy(focusingRecipeClass, "spirits");
        focusingOutputField = fieldQuietly(focusingRecipeClass, "output");
        infusionOutputField = fieldQuietly(infusionRecipeClass, "output");
        infusionUseNbtFromInputField = fieldQuietly(infusionRecipeClass, "useNbtFromInput");

        altarRecalculateMethod = methodInHierarchy(altarClass, "recalculateRecipes").orElse(null);
        altarCraftingMethod = methodInHierarchy(altarClass, "isCrafting").orElse(null);
        crucibleUpdateMethod = methodInHierarchy(crucibleClass, "updateRecipe").orElse(null);
        if (crucibleUpdateMethod == null) {
            crucibleUpdateMethod = methodInHierarchy(crucibleClass, "init").orElse(null);
        }
        crucibleCraftingMethod = methodInHierarchy(crucibleClass, "isCrafting").orElse(null);
        capturePedestalsMethod = methodInHierarchy(altarHelperClass, "capturePedestals",
                Level.class, BlockPos.class).orElse(null);
        getSuppliedInventoryMethod = methodInHierarchy(accessPointClass, "getSuppliedInventory").orElse(null);
        getAccessPointBlockPosMethod = methodInHierarchy(accessPointClass, "getAccessPointBlockPos").orElse(null);
        infusionGetOutputMethod = methodInHierarchy(infusionRecipeClass, "getOutput",
                ServerLevel.class, ItemStack.class).orElse(null);
        spiritAsItemStackMethod = methodQuietly(spiritIngredientClass, "getStack");
        focusingGetInputMethod = ReflectionSupport.findMethodCached(focusingRecipeClass, "getInput").orElse(null);
        focusingGetSpiritsMethod = ReflectionSupport.findMethodCached(focusingRecipeClass, "getSpirits").orElse(null);
        focusingCreateOutputMethod = ReflectionSupport.findMethodCached(focusingRecipeClass, "createOutput").orElse(null);
    }

    @Nullable
    private static Field fieldQuietly(@Nullable Class<?> type, String name) {
        if (type == null) {
            return null;
        }
        try {
            return field(type, name);
        } catch (NoSuchFieldException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** getDeclaredField fallback that walks superclasses (1.20.1 declares
     *  {@code spirits} on the abstract base recipe). */
    @Nullable
    private static Field fieldInHierarchy(@Nullable Class<?> type, String name) {
        var current = type;
        while (current != null) {
            var f = fieldQuietly(current, name);
            if (f != null) {
                return f;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    @Nullable
    private static Method methodQuietly(@Nullable Class<?> type, String name, Class<?>... parameterTypes) {
        if (type == null) {
            return null;
        }
        try {
            return method(type, name, parameterTypes);
        } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        var method = type.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static Optional<Method> methodInHierarchy(Class<?> type, String name, Class<?>... parameterTypes) {
        var current = type;
        while (current != null) {
            var method = ReflectionSupport.findDeclaredMethodCached(current, name, parameterTypes);
            if (method.isPresent()) {
                return method;
            }
            current = current.getSuperclass();
        }
        return Optional.empty();
    }

    private static Class<?> requiredClass(String... classNames) throws ClassNotFoundException {
        for (var className : classNames) {
            var found = ReflectionSupport.findClassCached(className);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new ClassNotFoundException(String.join(", ", classNames));
    }

    private static Method requiredMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return ReflectionSupport.findMethodCached(type, name, parameterTypes)
                .orElseThrow(() -> new NoSuchMethodException(type.getName() + "#" + name));
    }

    record Pedestal(BlockPos pos, IItemHandlerModifiable inventory) {
    }
}

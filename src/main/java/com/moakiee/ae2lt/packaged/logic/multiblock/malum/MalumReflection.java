package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;

final class MalumReflection {

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
            "com.sammy.malum.core.systems.recipe.SpiritIngredient";

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
    private static volatile @Nullable Field focusingInputField;
    private static volatile @Nullable Field focusingSpiritsField;
    private static volatile @Nullable Field focusingOutputField;

    private static volatile @Nullable Method altarRecalculateMethod;
    private static volatile @Nullable Method crucibleUpdateMethod;
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
        return isAltarInactive(be)
                && fieldValue(altarRecipeField, be) == null;
    }

    static boolean isCrucibleIdle(BlockEntity be) {
        return isCrucibleInactive(be)
                && fieldValue(crucibleRecipeField, be) == null;
    }

    static boolean isAltarInactive(BlockEntity be) {
        return isSpiritAltar(be)
                && !booleanField(altarCraftingField, be)
                && numberField(altarProgressField, be) <= 0;
    }

    static boolean isCrucibleInactive(BlockEntity be) {
        return isSpiritCrucible(be)
                && !booleanField(crucibleCraftingField, be)
                && numberField(crucibleProgressField, be) <= 0;
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

    static void recalculateAltar(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (be == null || !isSpiritAltar(be) || altarRecalculateMethod == null) {
            return;
        }
        refreshInventoryCaches(altarInventory(be));
        refreshInventoryCaches(altarSpiritInventory(be));
        refreshInventoryCaches(altarExtrasInventory(be));
        for (var pedestal : capturePedestals(level, pos)) {
            refreshInventoryCaches(pedestal.inventory());
        }
        try {
            ReflectionSupport.invoke(altarRecalculateMethod, be);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    static void updateCrucibleRecipe(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (be == null || !isSpiritCrucible(be) || crucibleUpdateMethod == null) {
            return;
        }
        try {
            ReflectionSupport.invoke(crucibleUpdateMethod, be);
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    static boolean insertItem(ServerLevel level, IItemHandlerModifiable inventory, int fallbackSlot, ItemStack stack) {
        var insertMethod = ReflectionSupport.findMethodCached(inventory.getClass(), "insertItem", ServerLevel.class, ItemStack.class);
        if (insertMethod.isPresent()) {
            try {
                var method = insertMethod.get();
                var submitted = stack.copy();
                var result = ReflectionSupport.invoke(method, inventory, level, submitted);
                return result.isPresent()
                        && insertionResultAccepted(result.get(), stack.getCount(), false, submitted);
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        insertMethod = ReflectionSupport.findMethodCached(inventory.getClass(), "insertItem", ItemStack.class);
        if (insertMethod.isPresent()) {
            try {
                var method = insertMethod.get();
                var submitted = stack.copy();
                var result = ReflectionSupport.invoke(method, inventory, submitted);
                return result.isPresent()
                        && insertionResultAccepted(result.get(), stack.getCount(), false, submitted);
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        insertMethod = ReflectionSupport.findMethodCached(inventory.getClass(), "insertItem", ItemStack.class, boolean.class);
        if (insertMethod.isPresent()) {
            try {
                var method = insertMethod.get();
                var submitted = stack.copy();
                var result = ReflectionSupport.invoke(method, inventory, submitted, false);
                return result.isPresent()
                        && insertionResultAccepted(result.get(), stack.getCount(), true, submitted);
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        return placeItemInSlot(inventory, fallbackSlot, stack);
    }

    static boolean placeItemInSlot(IItemHandlerModifiable inventory, int slot, ItemStack stack) {
        if (slotInvalid(inventory, slot) || stack.isEmpty()) {
            return false;
        }
        try {
            if (!inventory.isItemValid(slot, stack)) {
                return false;
            }
            var existing = inventory.getStackInSlot(slot);
            if (!existing.isEmpty()) {
                if (!ItemStack.isSameItemSameTags(existing, stack)) {
                    return false;
                }
                long mergedCount = (long) existing.getCount() + stack.getCount();
                if (mergedCount > inventory.getSlotLimit(slot)) {
                    return false;
                }
                var merged = existing.copy();
                merged.grow(stack.getCount());
                inventory.setStackInSlot(slot, merged);
                refreshInventoryCaches(inventory);
                return true;
            }
            if (stack.getCount() > inventory.getSlotLimit(slot)) {
                return false;
            }
            inventory.setStackInSlot(slot, stack.copy());
            refreshInventoryCaches(inventory);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
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
                return stack.isEmpty();
            }
            return !stack.isEmpty() && stack.getCount() >= requestedCount;
        }
        if (submitted.isEmpty()) {
            return true;
        }
        if (submitted.getCount() < requestedCount) {
            return false;
        }
        return true;
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
            try {
                var refresh = method.get();
                ReflectionSupport.invoke(refresh, inventory);
                return;
            } catch (RuntimeException | LinkageError ignored) {
            }
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
            return null;
        }
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
        return new SizedIngredientView((Ingredient) ingredient, count instanceof Integer i ? i : 1);
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
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            } finally {
                lookupDone = true;
            }
        }
    }

    private static void doLookup() throws ReflectiveOperationException {
        altarClass = requiredClass(ALTAR_CLASSES);
        crucibleClass = requiredClass(CRUCIBLE_CLASSES);
        accessPointClass = requiredClass(ACCESS_POINT_CLASS);
        infusionRecipeClass = requiredClass(INFUSION_RECIPE_CLASS);
        focusingRecipeClass = requiredClass(FOCUSING_RECIPE_CLASS);
        spiritIngredientClass = requiredClass(SPIRIT_INGREDIENT_CLASS);
        var altarHelperClass = requiredClass(ALTAR_HELPER_CLASSES);

        altarInventoryField = field(altarClass, "inventory");
        altarSpiritInventoryField = field(altarClass, "spiritInventory");
        altarExtrasInventoryField = field(altarClass, "extrasInventory");
        altarRecipeField = field(altarClass, "recipe");
        altarCraftingField = field(altarClass, "isCrafting");
        altarProgressField = field(altarClass, "progress");
        crucibleInventoryField = field(crucibleClass, "inventory");
        crucibleSpiritInventoryField = field(crucibleClass, "spiritInventory");
        crucibleRecipeField = field(crucibleClass, "recipe");
        crucibleCraftingField = field(crucibleClass, "isCrafting");
        crucibleProgressField = field(crucibleClass, "progress");
        infusionInputField = field(infusionRecipeClass, "input");
        infusionSpiritsField = field(infusionRecipeClass, "spirits");
        infusionExtrasField = field(infusionRecipeClass, "extraInputs");
        focusingInputField = field(focusingRecipeClass, "input");
        focusingSpiritsField = field(focusingRecipeClass, "spirits");
        focusingOutputField = field(focusingRecipeClass, "output");

        altarRecalculateMethod = method(altarClass, "recalculateRecipes");
        crucibleUpdateMethod = method(crucibleClass, "updateRecipe");
        capturePedestalsMethod = requiredMethod(altarHelperClass, "capturePedestals", Level.class, BlockPos.class);
        getSuppliedInventoryMethod = requiredMethod(accessPointClass, "getSuppliedInventory");
        getAccessPointBlockPosMethod = requiredMethod(accessPointClass, "getAccessPointBlockPos");
        infusionGetOutputMethod = requiredMethod(infusionRecipeClass, "getOutput", ServerLevel.class, ItemStack.class);
        spiritAsItemStackMethod = requiredMethod(spiritIngredientClass, "asItemStack");
        focusingGetInputMethod = ReflectionSupport.findMethodCached(focusingRecipeClass, "getInput").orElse(null);
        focusingGetSpiritsMethod = ReflectionSupport.findMethodCached(focusingRecipeClass, "getSpirits").orElse(null);
        focusingCreateOutputMethod = ReflectionSupport.findMethodCached(focusingRecipeClass, "createOutput").orElse(null);
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

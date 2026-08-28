package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.IntFunction;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;

/**
 * Lazy-resolved reflection handles into Botania (1.21.1 SNAPSHOT).
 *
 * <p>Botania is not a compile dependency for this project &mdash; the
 * SNAPSHOT JAR is loaded at runtime via {@code runtimeOnly files(...)},
 * and the maintainer explicitly states 1.21.1 is unsupported for bug
 * reports. Every access here is therefore null-safe and reflective, so
 * a missing class / renamed field merely degrades the adapter to "mod
 * not present" rather than crashing the host.
 */
public final class BotaniaReflection {

    public static final String MOD_ID = "botania";
    private static final Logger LOG = LogUtils.getLogger();

    // ===== Class names (kept here for one-place updates if Botania renames) =====

    private static final String CLS_MANA_POOL_BE =
            "vazkii.botania.common.block.block_entity.mana.ManaPoolBlockEntity";
    private static final String CLS_PETAL_APOTHECARY_BE =
            "vazkii.botania.common.block.block_entity.PetalApothecaryBlockEntity";
    private static final String CLS_ALFHEIM_PORTAL_BE =
            "vazkii.botania.common.block.block_entity.AlfheimPortalBlockEntity";
    private static final String CLS_RUNIC_ALTAR_BE =
            "vazkii.botania.common.block.block_entity.RunicAltarBlockEntity";
    private static final String CLS_TERRA_PLATE_BE =
            "vazkii.botania.common.block.block_entity.TerrestrialAgglomerationPlateBlockEntity";

    private static final String CLS_SIMPLE_INVENTORY_BE =
            "vazkii.botania.common.block.block_entity.SimpleInventoryBlockEntity";

    private static final String CLS_MANA_RECEIVER = "vazkii.botania.api.mana.ManaReceiver";

    private static final String CLS_PETAL_APOTHECARY_IFACE = "vazkii.botania.api.block.PetalApothecary";
    private static final String CLS_PETAL_APOTHECARY_STATE = "vazkii.botania.api.block.PetalApothecary$State";
    private static final String CLS_ALFHEIM_PORTAL_STATE = "vazkii.botania.api.state.enums.AlfheimPortalState";
    private static final String CLS_TERRA_PLATE_STATE = "vazkii.botania.api.state.enums.TerraPlateState";

    private static final String CLS_MANA_INFUSION_RECIPE = "vazkii.botania.api.recipe.ManaInfusionRecipe";
    private static final String CLS_ELVEN_TRADE_RECIPE = "vazkii.botania.api.recipe.ElvenTradeRecipe";
    private static final String CLS_PETAL_APOTHECARY_RECIPE = "vazkii.botania.api.recipe.PetalApothecaryRecipe";
    private static final String CLS_RUNIC_ALTAR_RECIPE = "vazkii.botania.api.recipe.RunicAltarRecipe";
    private static final String CLS_TERRA_PLATE_RECIPE = "vazkii.botania.api.recipe.TerrestrialAgglomerationRecipe";

    private static final String CLS_PROCESSING_RECIPE_INPUT = "vazkii.botania.api.recipe.ProcessingRecipeInput";
    private static final String CLS_STATE_INGREDIENT = "vazkii.botania.api.recipe.StateIngredient";
    private static final String CLS_RECIPE_WITH_REAGENT = "vazkii.botania.api.recipe.RecipeWithReagent";
    private static final String CLS_RECIPE_WITH_CATALYSTS = "vazkii.botania.api.recipe.RecipeWithCatalysts";

    private static final String CLS_BOTANIA_RECIPE_TYPES = "vazkii.botania.common.crafting.BotaniaRecipeTypes";

    private static final String CLS_STACKS_PROCESSING_INPUT =
            "vazkii.botania.common.crafting.recipe.StacksProcessingRecipeInput";

    // ===== Lazy holders =====

    private static volatile boolean attempted;
    private static volatile boolean available;

    // Class handles
    @Nullable private static volatile Class<?> manaPoolBeClass;
    @Nullable private static volatile Class<?> petalApothecaryBeClass;
    @Nullable private static volatile Class<?> alfheimPortalBeClass;
    @Nullable private static volatile Class<?> runicAltarBeClass;
    @Nullable private static volatile Class<?> terraPlateBeClass;
    @Nullable private static volatile Class<?> simpleInventoryBeClass;
    @Nullable private static volatile Class<?> manaReceiverClass;
    @Nullable private static volatile Class<?> petalApothecaryIfaceClass;
    @Nullable private static volatile Class<?> petalApothecaryStateClass;
    @Nullable private static volatile Class<?> alfheimPortalStateClass;
    @Nullable private static volatile Class<?> terraPlateStateClass;

    @Nullable private static volatile Class<?> manaInfusionRecipeClass;
    @Nullable private static volatile Class<?> elvenTradeRecipeClass;
    @Nullable private static volatile Class<?> petalApothecaryRecipeClass;
    @Nullable private static volatile Class<?> runicAltarRecipeClass;
    @Nullable private static volatile Class<?> terraPlateRecipeClass;

    @Nullable private static volatile Class<?> processingContainerClass;
    @Nullable private static volatile Class<?> stateIngredientClass;
    @Nullable private static volatile Class<?> recipeWithReagentClass;
    @Nullable private static volatile Class<?> recipeWithCatalystsClass;

    // Method handles
    @Nullable private static volatile Method getCurrentMana;          // ManaReceiver
    @Nullable private static volatile Method receiveMana;             // ManaReceiver
    @Nullable private static volatile Method getMaxMana;              // ManaPool only
    @Nullable private static volatile Method poolGetMatchingRecipe;   // ManaPoolBlockEntity
    @Nullable private static volatile Method poolCraftingEffect;      // ManaPoolBlockEntity
    @Nullable private static volatile Method apothecaryGetFluid;
    @Nullable private static volatile Method apothecarySetFluid;
    @Nullable private static volatile Method simpleBeGetItemHandler;  // SimpleInventoryBlockEntity
    @Nullable private static volatile Method simpleBeGetContainer;  // SimpleInventoryBlockEntity
    @Nullable private static volatile Method stateIngredientTest;     // StateIngredient.test(BlockState)
    @Nullable private static volatile Method reagentGetReagent;       // RecipeWithReagent.getReagent()
    @Nullable private static volatile Method catalystsGetCatalysts;   // RecipeWithCatalysts.getCatalysts()

    // Field handles
    @Nullable private static volatile Field portalTicksOpen;          // AlfheimPortalBlockEntity.ticksOpen
    @Nullable private static volatile Field altarCurrentRecipe;       // RunicAltarBlockEntity.currentRecipe
    @Nullable private static volatile Field altarMana;                // RunicAltarBlockEntity.mana
    @Nullable private static volatile Field altarManaToGet;           // RunicAltarBlockEntity.manaToGet
    @Nullable private static volatile Field terraMana;                // TerrestrialAgglomerationPlateBlockEntity.mana
    @Nullable private static volatile Field terraManaToGet;           // TerrestrialAgglomerationPlateBlockEntity.manaToGet
    @Nullable private static volatile Field terraCurrentProgress;     // TerrestrialAgglomerationPlateBlockEntity.currentProgress

    @Nullable private static Constructor<?> stacksProcessingInputCtor;

    // RecipeType statics from BotaniaRecipeTypes
    @Nullable private static RecipeType<?> manaInfusionType;
    @Nullable private static RecipeType<?> elvenTradeType;
    @Nullable private static RecipeType<?> petalType;
    @Nullable private static RecipeType<?> runeType;
    @Nullable private static RecipeType<?> terraPlateType;

    // State enum values (cached for fast equals)
    @Nullable private static volatile Object stateEmpty;
    @Nullable private static volatile Object stateWater;
    @Nullable private static volatile Object stateLava;
    @Nullable private static volatile Object portalStateOff;
    @Nullable private static volatile Object portalStateOnX;
    @Nullable private static volatile Object portalStateOnZ;

    private BotaniaReflection() {
    }

    /**
     * Returns true when Botania is loaded AND all hot-path reflection
     * handles resolved successfully on the first attempt. Adapters should
     * short-circuit when this is false instead of throwing.
     */
    public static boolean isAvailable() {
        if (!attempted) {
            attempt();
        }
        return available;
    }

    public static boolean isModLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static synchronized void attempt() {
        if (attempted) {
            return;
        }
        attempted = true;
        if (!isModLoaded()) {
            return;
        }

        // Each member resolves independently: 1.20.x lacks several 1.21
        // hook classes (TerraPlateState / ProcessingRecipeInput /
        // RecipeWithCatalysts), and one absent name must not disable the
        // whole family of adapters.
        manaPoolBeClass = tryClass(CLS_MANA_POOL_BE);
        petalApothecaryBeClass = tryClass(CLS_PETAL_APOTHECARY_BE);
        alfheimPortalBeClass = tryClass(CLS_ALFHEIM_PORTAL_BE);
        runicAltarBeClass = tryClass(CLS_RUNIC_ALTAR_BE);
        terraPlateBeClass = tryClass(CLS_TERRA_PLATE_BE);
        simpleInventoryBeClass = tryClass(CLS_SIMPLE_INVENTORY_BE);
        manaReceiverClass = tryClass(CLS_MANA_RECEIVER);
        petalApothecaryIfaceClass = tryClass(CLS_PETAL_APOTHECARY_IFACE);
        petalApothecaryStateClass = tryClass(CLS_PETAL_APOTHECARY_STATE);
        alfheimPortalStateClass = tryClass(CLS_ALFHEIM_PORTAL_STATE);
        terraPlateStateClass = tryClass(CLS_TERRA_PLATE_STATE);

        boolean available_ = !isMissing(
                CLS_MANA_POOL_BE, manaPoolBeClass,
                CLS_PETAL_APOTHECARY_BE, petalApothecaryBeClass,
                CLS_ALFHEIM_PORTAL_BE, alfheimPortalBeClass,
                CLS_RUNIC_ALTAR_BE, runicAltarBeClass,
                CLS_TERRA_PLATE_BE, terraPlateBeClass,
                CLS_SIMPLE_INVENTORY_BE, simpleInventoryBeClass,
                CLS_MANA_RECEIVER, manaReceiverClass,
                CLS_PETAL_APOTHECARY_IFACE, petalApothecaryIfaceClass,
                CLS_PETAL_APOTHECARY_STATE, petalApothecaryStateClass);

        // Recipe interfaces: used as type filters, degrades to "recipe
        // type unknown" when absent.
        manaInfusionRecipeClass = tryClass(CLS_MANA_INFUSION_RECIPE);
        elvenTradeRecipeClass = tryClass(CLS_ELVEN_TRADE_RECIPE);
        petalApothecaryRecipeClass = tryClass(CLS_PETAL_APOTHECARY_RECIPE);
        runicAltarRecipeClass = tryClass(CLS_RUNIC_ALTAR_RECIPE);
        terraPlateRecipeClass = tryClass(CLS_TERRA_PLATE_RECIPE);
        stateIngredientClass = tryClass(CLS_STATE_INGREDIENT);
        recipeWithReagentClass = tryClass(CLS_RECIPE_WITH_REAGENT);
        recipeWithCatalystsClass = tryClass(CLS_RECIPE_WITH_CATALYSTS);
        // Not present on 1.20.x; handle stays null and createProcessingInput
        // reports unresolved.
        processingContainerClass = tryClass(CLS_PROCESSING_RECIPE_INPUT);

        getCurrentMana = tryMethod(manaReceiverClass, "getCurrentMana");
        receiveMana = tryMethod(manaReceiverClass, "receiveMana", int.class);
        getMaxMana = tryMethod(manaPoolBeClass, "getMaxMana");
        poolGetMatchingRecipe = tryMethod(manaPoolBeClass, "getMatchingRecipe",
                ItemStack.class, BlockState.class);
        poolCraftingEffect = tryMethod(manaPoolBeClass, "craftingEffect", boolean.class);

        apothecaryGetFluid = tryMethod(petalApothecaryIfaceClass, "getFluid");
        apothecarySetFluid = tryMethod(petalApothecaryIfaceClass, "setFluid", petalApothecaryStateClass);

        simpleBeGetItemHandler = tryMethod(simpleInventoryBeClass, "getItemHandler");
        simpleBeGetContainer = tryMethod(simpleInventoryBeClass, "getRecipeInput");

        stateIngredientTest = tryMethod(stateIngredientClass, "test", BlockState.class);
        reagentGetReagent = tryMethod(recipeWithReagentClass, "getReagent");
        catalystsGetCatalysts = tryMethod(recipeWithCatalystsClass, "getCatalysts");

        portalTicksOpen = tryField(alfheimPortalBeClass, "ticksOpen");
        altarCurrentRecipe = tryField(runicAltarBeClass, "currentRecipe");
        altarMana = tryField(runicAltarBeClass, "mana");
        altarManaToGet = tryField(runicAltarBeClass, "manaToGet");
        terraMana = tryField(terraPlateBeClass, "mana");
        terraManaToGet = tryField(terraPlateBeClass, "manaToGet");
        terraCurrentProgress = tryField(terraPlateBeClass, "currentProgress");

        stateEmpty = enumValue(petalApothecaryStateClass, "EMPTY");
        stateWater = enumValue(petalApothecaryStateClass, "WATER");
        stateLava = enumValue(petalApothecaryStateClass, "LAVA");
        portalStateOff = enumValue(alfheimPortalStateClass, "OFF");
        portalStateOnX = enumValue(alfheimPortalStateClass, "ON_X");
        portalStateOnZ = enumValue(alfheimPortalStateClass, "ON_Z");

        stacksProcessingInputCtor = tryConstructor(CLS_STACKS_PROCESSING_INPUT);

        var typeHolder = tryClass(CLS_BOTANIA_RECIPE_TYPES);
        manaInfusionType = recipeType(typeHolder, "MANA_INFUSION_TYPE");
        elvenTradeType = recipeType(typeHolder, "ELVEN_TRADE_TYPE");
        petalType = recipeType(typeHolder, "PETAL_APOTHECARY_TYPE", "PETAL_TYPE");
        runeType = recipeType(typeHolder, "RUNIC_ALTAR_TYPE", "RUNE_TYPE");
        terraPlateType = recipeType(typeHolder, "TERRA_PLATE_TYPE");

        available = available_;
        if (!available) {
            LOG.warn("Botania reflection missed critical handles; all Botania adapters are disabled");
        }
    }

    @Nullable
    private static Class<?> tryClass(String name) {
        try {
            return Class.forName(name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Method tryMethod(@Nullable Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, params);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Field tryField(@Nullable Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            var f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static Constructor<?> tryConstructor(String className) {
        var owner = tryClass(className);
        if (owner == null) {
            return null;
        }
        try {
            return owner.getConstructor(ItemStack[].class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Validates that every {@code (name, class)} pair in this argument list
     * resolved.
     */
    private static boolean isMissing(Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] == null) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static RecipeType<?> recipeType(@Nullable Class<?> owner, String... fieldNames) {
        if (owner == null) {
            return null;
        }
        for (var fieldName : fieldNames) {
            var f = tryField(owner, fieldName);
            if (f != null) {
                try {
                    f.setAccessible(true);
                    return (RecipeType<?>) f.get(null);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // try the next alias
                }
            }
        }
        return null;
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(@Nullable Class<?> enumClass, String name) {
        if (enumClass == null || !enumClass.isEnum()) {
            return null;
        }
        try {
            return Enum.valueOf((Class) enumClass, name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ===== Class predicates =====

    public static boolean isManaPool(@Nullable BlockEntity be) {
        return isAvailable() && manaPoolBeClass != null && manaPoolBeClass.isInstance(be);
    }

    public static boolean isPetalApothecary(@Nullable BlockEntity be) {
        return isAvailable() && petalApothecaryBeClass != null && petalApothecaryBeClass.isInstance(be);
    }

    public static boolean isAlfheimPortal(@Nullable BlockEntity be) {
        return isAvailable() && alfheimPortalBeClass != null && alfheimPortalBeClass.isInstance(be);
    }

    public static boolean isRunicAltar(@Nullable BlockEntity be) {
        return isAvailable() && runicAltarBeClass != null && runicAltarBeClass.isInstance(be);
    }

    public static boolean isTerraPlate(@Nullable BlockEntity be) {
        return isAvailable() && terraPlateBeClass != null && terraPlateBeClass.isInstance(be);
    }

    // ===== Mana Pool =====

    public static int manaPoolCurrentMana(BlockEntity be) {
        try {
            return (int) getCurrentMana.invoke(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    public static int manaPoolMaxMana(BlockEntity be) {
        try {
            return (int) getMaxMana.invoke(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    public static void manaPoolReceiveMana(BlockEntity be, int delta) {
        try {
            receiveMana.invoke(be, delta);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    public static void manaPoolCraftingEffect(BlockEntity be) {
        try {
            poolCraftingEffect.invoke(be, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    /**
     * Mirror of {@code ManaPoolBlockEntity#getMatchingRecipe(ItemStack, BlockState)}.
     * Returns the {@code ManaInfusionRecipe} whose ingredient
     * accepts the given item and whose catalyst matches the block under the
     * pool, or {@code null} when no recipe matches.
     */
    @Nullable
    public static Recipe<?> manaPoolMatchingRecipe(BlockEntity be, ItemStack stack, BlockState below) {
        try {
            var result = poolGetMatchingRecipe.invoke(be, stack, below);
            return result instanceof Recipe<?> holder ? holder : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    // ===== Petal Apothecary =====

    @Nullable
    public static Object apothecaryFluid(BlockEntity be) {
        try {
            return apothecaryGetFluid.invoke(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static boolean apothecaryHasWater(BlockEntity be) {
        var fluid = apothecaryFluid(be);
        return fluid != null && fluid == stateWater;
    }

    public static void apothecarySetEmpty(BlockEntity be) {
        try {
            apothecarySetFluid.invoke(be, stateEmpty);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    // ===== Alfheim Portal =====

    public static int portalTicksOpen(BlockEntity be) {
        try {
            return portalTicksOpen.getInt(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    public static boolean portalIsOpen(BlockEntity be, BlockState state) {
        // Either the BE ticks-open counter says "open" or the controller's
        // block state is in one of the ON_X/ON_Z values.
        if (portalTicksOpen(be) > 0) {
            return true;
        }
        for (var prop : state.getProperties()) {
            if (alfheimPortalStateClass != null && alfheimPortalStateClass.isInstance(state.getValue(prop))) {
                var value = state.getValue(prop);
                return value == portalStateOnX || value == portalStateOnZ;
            }
        }
        return false;
    }

    // ===== Runic Altar =====

    @Nullable
    public static Recipe<?> altarCurrentRecipe(BlockEntity be) {
        try {
            var v = altarCurrentRecipe.get(be);
            return v instanceof Recipe<?> holder ? holder : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static int altarMana(BlockEntity be) {
        try {
            return altarMana.getInt(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    public static int altarManaToGet(BlockEntity be) {
        try {
            return altarManaToGet.getInt(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    public static void altarResetState(BlockEntity be) {
        // Independent writes: 1.20.x lacks manaToGet/currentProgress, and an
        // exception here must never leave currentRecipe latched (that would
        // block every future dispatch).
        setFieldQuietly(altarMana, be, 0);
        setFieldQuietly(altarManaToGet, be, 0);
        if (altarCurrentRecipe != null) {
            try {
                altarCurrentRecipe.set(be, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
    }

    private static void setFieldQuietly(@Nullable Field field, BlockEntity be, @Nullable Object value) {
        if (field == null) {
            return;
        }
        try {
            field.setAccessible(true);
            if (field.getType() == int.class) {
                field.setInt(be, value instanceof Number number ? number.intValue() : 0);
            } else {
                field.set(be, value);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }

    // ===== Terra Plate =====

    public static int terraMana(BlockEntity be) {
        try {
            return terraMana.getInt(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    public static int terraManaToGet(BlockEntity be) {
        try {
            return terraManaToGet.getInt(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    public static boolean terraIsIdle(BlockEntity be) {
        return terraMana(be) == 0 && terraManaToGet(be) == 0;
    }

    // ===== SimpleInventory / Recipe helpers =====

    @Nullable
    public static Container simpleInventoryContainer(BlockEntity be) {
        try {
            return (Container) simpleBeGetItemHandler.invoke(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Returns the live {@link Container} backing the BE's inventory.
     * Cast freely to {@link Recipe#matches(Container, net.minecraft.world.level.Level) Recipe.matches}'s
     * concrete input type at the call site.
     */
    @Nullable
    public static Container simpleInventoryRecipeContainer(BlockEntity be) {
        try {
            var v = simpleBeGetContainer.invoke(be);
            return v instanceof Container input ? input : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Constructs a Botania {@code ProcessingContainer} backed by the
     * given stack array. Returned object passes
     * {@code Recipe.matches(ProcessingContainer, Level)} for all
     * Botania recipe types whose input type parameter is
     * {@code ProcessingContainer} (elven trade, runic altar, terra
     * plate, petal apothecary). Returns {@code null} when the reflection
     * handle wasn't resolved.
     */
    @Nullable
    public static Container createProcessingInput(ItemStack[] stacks) {
        if (stacksProcessingInputCtor == null) {
            return null;
        }
        try {
            return (Container) stacksProcessingInputCtor.newInstance((Object) stacks);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static boolean stateIngredientAccepts(Object stateIngredient, BlockState state) {
        try {
            return (boolean) stateIngredientTest.invoke(stateIngredient, state);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    // ===== Recipe types =====

    @Nullable public static RecipeType<?> manaInfusionType() { return manaInfusionType; }
    @Nullable public static RecipeType<?> elvenTradeType() { return elvenTradeType; }
    @Nullable public static RecipeType<?> petalType() { return petalType; }
    @Nullable public static RecipeType<?> runeType() { return runeType; }
    @Nullable public static RecipeType<?> terraPlateType() { return terraPlateType; }

    // ===== Recipe class predicates (used by lookup module to filter holders) =====

    public static boolean isManaInfusionRecipe(Object recipe) {
        return manaInfusionRecipeClass != null && manaInfusionRecipeClass.isInstance(recipe);
    }

    public static boolean isElvenTradeRecipe(Object recipe) {
        return elvenTradeRecipeClass != null && elvenTradeRecipeClass.isInstance(recipe);
    }

    public static boolean isPetalApothecaryRecipe(Object recipe) {
        return petalApothecaryRecipeClass != null && petalApothecaryRecipeClass.isInstance(recipe);
    }

    public static boolean isRunicAltarRecipe(Object recipe) {
        return runicAltarRecipeClass != null && runicAltarRecipeClass.isInstance(recipe);
    }

    public static boolean isTerraPlateRecipe(Object recipe) {
        return terraPlateRecipeClass != null && terraPlateRecipeClass.isInstance(recipe);
    }

    /**
     * Generic helper: invokes a zero-arg method on a recipe whose return
     * type the caller knows. Used by the lookup module to pull
     * {@code getManaToConsume() / getMana() / getOutputs() / ...} without
     * compiling against Botania.
     */
    public static <T> Optional<T> invokeRecipeMethod(Object recipe, String methodName, Class<T> resultType) {
        try {
            var m = ReflectionSupport.findMethodCached(recipe.getClass(), methodName).orElse(null);
            if (m == null) {
                return Optional.empty();
            }
            var v = m.invoke(recipe);
            if (v == null) {
                return Optional.empty();
            }
            if (!resultType.isInstance(v)) {
                return Optional.empty();
            }
            return Optional.of(resultType.cast(v));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    public static int invokeRecipeIntMethod(Object recipe, String methodName, int fallback) {
        try {
            var m = ReflectionSupport.findMethodCached(recipe.getClass(), methodName).orElse(null);
            if (m == null) {
                return fallback;
            }
            var v = m.invoke(recipe);
            return v instanceof Integer i ? i : fallback;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return fallback;
        }
    }

    /**
     * Helper that lets adapters reflectively use the recipe's
     * {@code getReagent()} when it's a {@link #recipeWithReagentClass}.
     */
    @Nullable
    public static net.minecraft.world.item.crafting.Ingredient recipeReagent(Object recipe) {
        try {
            if (recipeWithReagentClass == null || !recipeWithReagentClass.isInstance(recipe)) {
                return null;
            }
            var v = reagentGetReagent.invoke(recipe);
            return v instanceof net.minecraft.world.item.crafting.Ingredient ing ? ing : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Returns the recipe's catalyst ingredient list when it's a
     * {@link #recipeWithCatalystsClass}; empty list when the recipe
     * has no catalysts or doesn't implement the interface. Catalysts
     * sit in the same inventory as the regular ingredients and
     * {@code matches()} requires {@code input.size() == ingredients.size() +
     * catalysts.size()} &mdash; so adapters must dispatch them together
     * with the ingredients and reclaim them from
     * {@code recipe.getRemainingItems(input)} after the craft.
     */
    public static java.util.List<net.minecraft.world.item.crafting.Ingredient>
            recipeCatalysts(Object recipe) {
        try {
            if (recipeWithCatalystsClass == null || !recipeWithCatalystsClass.isInstance(recipe)) {
                return java.util.List.of();
            }
            var v = catalystsGetCatalysts.invoke(recipe);
            if (v instanceof java.util.List<?> list) {
                var out = new java.util.ArrayList<net.minecraft.world.item.crafting.Ingredient>(list.size());
                for (var entry : list) {
                    if (entry instanceof net.minecraft.world.item.crafting.Ingredient ing) {
                        out.add(ing);
                    }
                }
                return out;
            }
            return java.util.List.of();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return java.util.List.of();
        }
    }

    /**
     * Reflectively calls {@code recipe.getRemainingItems(input)},
     * returning the per-slot list of "items the recipe consumes but
     * leaves in the inventory" (Botania uses this for catalysts).
     * Returns an empty list on any failure so the adapter can fall
     * back to "no catalysts to reclaim" without crashing.
     */
    public static java.util.List<net.minecraft.world.item.ItemStack>
            recipeRemainingItems(Object recipe, net.minecraft.world.Container input) {
        try {
            var m = ReflectionSupport.findMethodCached(recipe.getClass(), "getRemainingItems", input.getClass())
                    .orElse(null);
            if (m == null) {
                throw new NoSuchMethodException();
            }
            var v = m.invoke(recipe, input);
            if (v instanceof java.util.List<?> list) {
                var out = new java.util.ArrayList<net.minecraft.world.item.ItemStack>(list.size());
                for (var entry : list) {
                    if (entry instanceof net.minecraft.world.item.ItemStack stack) {
                        out.add(stack);
                    }
                }
                return out;
            }
            return java.util.List.of();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Try the more generic Container overload (matches the bridge
            // method generated by the parameterised Recipe interface).
            try {
                var m = ReflectionSupport.findMethodCached(recipe.getClass(), "getRemainingItems",
                        net.minecraft.world.Container.class).orElse(null);
                if (m == null) {
                    return java.util.List.of();
                }
                var v = m.invoke(recipe, input);
                if (v instanceof java.util.List<?> list) {
                    var out = new java.util.ArrayList<net.minecraft.world.item.ItemStack>(list.size());
                    for (var entry : list) {
                        if (entry instanceof net.minecraft.world.item.ItemStack stack) {
                            out.add(stack);
                        }
                    }
                    return out;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored2) {
                // Fall through.
            }
            return java.util.List.of();
        }
    }

    @SuppressWarnings("unused")
    private interface IntSupplierFn extends IntFunction<Integer> {
    }
}

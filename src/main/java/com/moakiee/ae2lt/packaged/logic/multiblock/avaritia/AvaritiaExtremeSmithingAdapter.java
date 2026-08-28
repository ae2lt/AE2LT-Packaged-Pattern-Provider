package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterBlocks;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Virtual adapter for Re-Avaritia's Extreme Smithing Table.
 */
public final class AvaritiaExtremeSmithingAdapter implements VirtualCraftingAdapter {

    private static final String MOD_ID = "avaritia";
    private static final ResourceLocation EXTREME_SMITHING_BLOCK = avaritiaId("extreme_smithing_table");
    private static final ResourceLocation RECIPE_TYPE_ID = avaritiaId("extreme_smithing_recipe");

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, net.minecraft.core.BlockPos pos,
                                  @Nullable BlockEntity be) {
        return isAvaritiaLoaded()
                && AdapterBlocks.idOf(level.getBlockState(pos).getBlock())
                .equals(EXTREME_SMITHING_BLOCK);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, net.minecraft.core.BlockPos pos) {
        return AdapterIds.AVARITIA_EXTREME_SMITHING;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, net.minecraft.core.BlockPos mainPos, IPatternDetails pattern) {
        if (!recognizesMain(level, mainPos, level.getBlockEntity(mainPos)) || !hasSingleItemOutput(pattern)) {
            return null;
        }

        var handle = searchHandle(level, pattern);
        if (handle == null) {
            return null;
        }
        return new BindingResult(handle, BindingMode.VIRTUAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, net.minecraft.core.BlockPos mainPos, Object handle) {
        return true;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, net.minecraft.core.BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        return null;
    }

    @Override
    @Nullable
    public VirtualCraftingResult planVirtualWithBinding(ServerLevel level, net.minecraft.core.BlockPos mainPos,
                                                        IPatternDetails pattern, KeyCounter[] inputs,
                                                        Object handle, IActionSource source) {
        if (!(handle instanceof SmithingBindHandle bind)) {
            return null;
        }
        if (!recognizesMain(level, mainPos, level.getBlockEntity(mainPos))) {
            return null;
        }

        var available = new LinkedHashMap<AEItemKey, Long>();
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                available.merge(itemKey, amount, Long::sum);
            }
        }
        if (available.isEmpty()) {
            return null;
        }

        var match = assignStrict(bind.recipe(), available, bind.craftRatio());
        if (match == null) {
            return null;
        }

        var input = AvaritiaSmithingReflection.createInput(match);
        if (input == null || !recipeMatches(bind.recipe(), input, level)) {
            return null;
        }

        var result = assemble(bind.recipe(), input, level);
        if (result == null || result.isEmpty()) {
            return null;
        }

        long totalCount = (long) bind.craftRatio() * result.getCount();
        if (totalCount <= 0 || totalCount > Integer.MAX_VALUE) {
            return null;
        }
        if (!outputMatchesBatch(pattern, result, totalCount)) {
            return null;
        }

        return new VirtualCraftingResult(List.of(
                new GenericStack(AEItemKey.of(result), totalCount)));
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, net.minecraft.core.BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        return List.of();
    }

    @Nullable
    private static SmithingBindHandle searchHandle(ServerLevel level, IPatternDetails pattern) {
        var patternOut = pattern.getOutputs()[0];
        if (!(patternOut.what() instanceof AEItemKey patternOutKey) || patternOut.amount() <= 0) {
            return null;
        }

        var provided = new LinkedHashMap<AEItemKey, Long>();
        for (var input : pattern.getInputs()) {
            var possible = input.getPossibleInputs();
            if (possible.length == 0 || !(possible[0].what() instanceof AEItemKey itemKey)) {
                return null;
            }
            long stackAmount = Math.max(1L, possible[0].amount());
            long multiplier = Math.max(1L, input.getMultiplier());
            long amount;
            try {
                amount = Math.multiplyExact(stackAmount, multiplier);
            } catch (ArithmeticException ignored) {
                return null;
            }
            provided.merge(itemKey, amount, Long::sum);
        }
        if (provided.isEmpty()) {
            return null;
        }

        boolean overload = OverloadPatternSemantics.isIdOnlyOutput(pattern, 0);

        for (var recipe : recipes(level)) {
            var resultPreview = resultItem(recipe, level);
            if (resultPreview == null || resultPreview.isEmpty()) {
                continue;
            }
            if (resultPreview.getItem() != patternOutKey.getItem()) {
                continue;
            }

            int resultCount = resultPreview.getCount();
            if (resultCount <= 0 || patternOut.amount() % resultCount != 0) {
                continue;
            }
            long k = patternOut.amount() / resultCount;
            if (k <= 0 || k > Integer.MAX_VALUE) {
                continue;
            }

            var match = overload
                    ? assignByItemId(recipe, provided, k)
                    : assignStrict(recipe, provided, k);
            if (match == null) {
                continue;
            }

            var input = AvaritiaSmithingReflection.createInput(match);
            if (input == null || !recipeMatches(recipe, input, level)) {
                continue;
            }

            var result = assemble(recipe, input, level);
            if (result == null || result.isEmpty()) {
                continue;
            }

            long totalCount = k * result.getCount();
            if (totalCount <= 0 || totalCount > Integer.MAX_VALUE) {
                continue;
            }
            if (!outputMatchesBatch(pattern, result, totalCount)) {
                continue;
            }

            return new SmithingBindHandle(recipe, (int) k);
        }
        return null;
    }

    @Nullable
    private static SmithingMatch assignStrict(Object recipe,
                                              LinkedHashMap<AEItemKey, Long> available,
                                              long craftRatio) {
        return assign(recipe, available, craftRatio,
                key -> AvaritiaSmithingReflection.matchesTemplate(recipe, key.toStack(1)),
                key -> AvaritiaSmithingReflection.matchesBase(recipe, key.toStack(1)),
                key -> AvaritiaSmithingReflection.matchesAddition(recipe, key.toStack(1)));
    }

    @Nullable
    private static SmithingMatch assignByItemId(Object recipe,
                                                LinkedHashMap<AEItemKey, Long> available,
                                                long craftRatio) {
        return assign(recipe, available, craftRatio,
                key -> AvaritiaSmithingReflection.templateAcceptsItemId(recipe, key.getItem()),
                key -> AvaritiaSmithingReflection.baseAcceptsItemId(recipe, key.getItem()),
                key -> AvaritiaSmithingReflection.additionAcceptsItemId(recipe, key.getItem()));
    }

    @Nullable
    private static SmithingMatch assign(Object recipe,
                                        LinkedHashMap<AEItemKey, Long> available,
                                        long craftRatio,
                                        java.util.function.Predicate<AEItemKey> templateMatcher,
                                        java.util.function.Predicate<AEItemKey> baseMatcher,
                                        java.util.function.Predicate<AEItemKey> additionMatcher) {
        var units = expandUnits(available, craftRatio);
        if (units == null) {
            return null;
        }

        var match = AvaritiaExtremeSmithingInputMatcher.match(
                units, templateMatcher, baseMatcher, additionMatcher);
        if (match == null) {
            return null;
        }

        var additions = new ArrayList<ItemStack>(3);
        for (var addition : match.additions()) {
            additions.add(addition.toStack(1));
        }
        return new SmithingMatch(
                match.template().toStack(1),
                match.base().toStack(1),
                List.copyOf(additions));
    }

    @Nullable
    private static List<AEItemKey> expandUnits(LinkedHashMap<AEItemKey, Long> available,
                                               long craftRatio) {
        if (craftRatio <= 0) {
            return null;
        }
        var units = new ArrayList<AEItemKey>(5);
        for (var entry : available.entrySet()) {
            long amount = entry.getValue();
            if (amount <= 0) {
                continue;
            }
            if (amount % craftRatio != 0) {
                return null;
            }
            long copies = amount / craftRatio;
            if (copies > 5 - units.size()) {
                return null;
            }
            for (long i = 0; i < copies; i++) {
                units.add(entry.getKey());
            }
        }
        return units.size() == 5 ? List.copyOf(units) : null;
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.length == 1 && outputs[0].what() instanceof AEItemKey;
    }

    private static boolean outputMatchesBatch(IPatternDetails pattern, ItemStack result,
                                              long expectedTotalCount) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)
                || expected.amount() != expectedTotalCount) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return AvaritiaExtremeSmithingOutputMatcher.matches(
                expected.amount(), expectedKey,
                expectedTotalCount, actual,
                AEItemKey::dropSecondary);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean recipeMatches(Object recipe, Container input, ServerLevel level) {
        try {
            return recipe instanceof Recipe<?> r && ((Recipe) r).matches(input, level);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack assemble(Object recipe, Container input, ServerLevel level) {
        try {
            return recipe instanceof Recipe<?> r
                    ? ((Recipe) r).assemble(input, level.registryAccess())
                    : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Recipe<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE_ID)
                .map(type -> (List<Recipe<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    private static boolean isAvaritiaLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation avaritiaId(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private record SmithingBindHandle(Object recipe, int craftRatio) {
    }

    private record SmithingMatch(ItemStack template, ItemStack base, List<ItemStack> additions) {
        SmithingMatch {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(base, "base");
            additions = List.copyOf(additions);
            if (additions.size() != 3) {
                throw new IllegalArgumentException("Extreme smithing requires exactly three additions");
            }
        }
    }

    private static final class AvaritiaSmithingReflection {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger(AvaritiaSmithingReflection.class);
        private static final String RECIPE_CLASS =
                "committee.nova.mods.avaritia.common.crafting.recipe.ExtremeSmithingRecipe";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Field templateField;
        private static volatile @Nullable Field baseField;
        private static volatile @Nullable Field additionsField;
        private static volatile @Nullable Method isTemplateIngredientMethod;
        private static volatile @Nullable Method isBaseIngredientMethod;
        private static volatile @Nullable Method isAdditionIngredientMethod;

        /**
         * 1.20.1 has no {@code ExtremeSmithingRecipeInput} (a 1.20.5+ class);
         * the recipe reads slots 0=template, 1=base, 2..4=additions from any
         * {@code Container}, so a plain {@link SimpleContainer} works.
         */
        static Container createInput(SmithingMatch match) {
            var container = new SimpleContainer(5);
            container.setItem(0, match.template().copy());
            container.setItem(1, match.base().copy());
            container.setItem(2, match.additions().get(0).copy());
            container.setItem(3, match.additions().get(1).copy());
            container.setItem(4, match.additions().get(2).copy());
            return container;
        }

        static boolean matchesTemplate(Object recipe, ItemStack stack) {
            return invokeIngredientMatcher(isTemplateIngredientMethod, recipe, stack);
        }

        static boolean matchesBase(Object recipe, ItemStack stack) {
            return invokeIngredientMatcher(isBaseIngredientMethod, recipe, stack);
        }

        static boolean matchesAddition(Object recipe, ItemStack stack) {
            return invokeIngredientMatcher(isAdditionIngredientMethod, recipe, stack);
        }

        static boolean templateAcceptsItemId(Object recipe, Item item) {
            return ingredientAcceptsItemId(readIngredient(templateField, recipe), item);
        }

        static boolean baseAcceptsItemId(Object recipe, Item item) {
            return ingredientAcceptsItemId(readIngredient(baseField, recipe), item);
        }

        static boolean additionAcceptsItemId(Object recipe, Item item) {
            return ingredientAcceptsItemId(readIngredient(additionsField, recipe), item);
        }

        @Nullable
        private static Ingredient readIngredient(@Nullable Field field, Object recipe) {
            ensureLookup();
            if (field == null || !field.getDeclaringClass().isInstance(recipe)) {
                return null;
            }
            try {
                var value = field.get(recipe);
                return value instanceof Ingredient ingredient ? ingredient : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        private static boolean ingredientAcceptsItemId(@Nullable Ingredient ingredient, Item item) {
            if (ingredient == null) {
                return false;
            }
            try {
                for (var candidate : ingredient.getItems()) {
                    if (candidate.getItem() == item) {
                        return true;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
            }
            return false;
        }

        private static boolean invokeIngredientMatcher(@Nullable Method method, Object recipe, ItemStack stack) {
            ensureLookup();
            if (method == null || !method.getDeclaringClass().isInstance(recipe)) {
                return false;
            }
            try {
                var value = method.invoke(recipe, stack);
                return value instanceof Boolean b && b;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (AvaritiaSmithingReflection.class) {
                if (lookupDone) {
                    return;
                }
                doLookup();
                lookupDone = true;
            }
        }

        private static void doLookup() {
            try {
                var recipeClass = Class.forName(RECIPE_CLASS);
                templateField = recipeClass.getField("template");
                baseField = recipeClass.getField("base");
                additionsField = recipeClass.getField("additions");
                isTemplateIngredientMethod = recipeClass.getMethod("isTemplateIngredient", ItemStack.class);
                isBaseIngredientMethod = recipeClass.getMethod("isBaseIngredient", ItemStack.class);
                isAdditionIngredientMethod = recipeClass.getMethod("isAdditionIngredient", ItemStack.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("[ae2ltpp] Avaritia ExtremeSmithingRecipe member lookup failed: {}", e.toString());
            }
        }
    }
}

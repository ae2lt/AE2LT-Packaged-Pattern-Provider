package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.moakiee.ae2lt.packaged.testsupport.MinecraftTestBootstrap;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

class PetalApothecaryRecipeSelectionTest {
    private final Map<Field, Object> savedReflection = new LinkedHashMap<>();

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @BeforeEach
    void installReagentFixture() throws ReflectiveOperationException {
        setReflection("recipeWithReagentClass", WithReagent.class);
        setReflection("reagentGetReagent", WithReagent.class.getMethod("getReagent"));
    }

    @AfterEach
    void restoreReflection() throws ReflectiveOperationException {
        for (var entry : savedReflection.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
    }

    @Test
    void skipsFirstSameOutputRecipeWhenItsPetalsDoNotMatch() {
        var wrong = recipe(1, Ingredient.of(Items.WHEAT_SEEDS), Items.RED_DYE);
        var matching = recipe(1, Ingredient.of(Items.WHEAT_SEEDS), Items.WHITE_DYE);
        var pattern = pattern(1, input(1, stack(Items.WHITE_DYE, 1)), input(1, stack(Items.WHEAT_SEEDS, 1)));
        assertSame(matching, select(pattern, wrong, matching));
        assertSame(matching, select(pattern, matching, wrong));
        assertNull(select(pattern, wrong));
    }

    @Test
    void skipsCandidateWhoseReagentDoesNotMatch() {
        var wrong = recipe(1, Ingredient.of(Items.BEETROOT_SEEDS), Items.WHITE_DYE);
        var matching = recipe(1, Ingredient.of(Items.WHEAT_SEEDS), Items.WHITE_DYE);
        var pattern = pattern(1, input(1, stack(Items.WHITE_DYE, 1)), input(1, stack(Items.WHEAT_SEEDS, 1)));
        assertSame(matching, select(pattern, wrong, matching));
    }

    @Test
    void doesNotReuseOnePetalForRepeatedIngredientsOrTheReagent() {
        var tooMany = recipe(1, Ingredient.of(Items.WHEAT_SEEDS), Items.WHITE_DYE, Items.WHITE_DYE);
        var matching = recipe(1, Ingredient.of(Items.WHEAT_SEEDS), Items.WHITE_DYE);
        var pattern = pattern(1, input(1, stack(Items.WHITE_DYE, 1)), input(1, stack(Items.WHEAT_SEEDS, 1)));
        assertSame(matching, select(pattern, tooMany, matching));
        assertNull(select(pattern(1, input(1, stack(Items.WHITE_DYE, 1))),
                recipe(1, Ingredient.of(Items.WHITE_DYE), Items.WHITE_DYE)));
    }

    @Test
    void considersResultCountInputStackAmountAndMultiplier() {
        var onePerCraft = recipe(1, Ingredient.of(Items.WHEAT_SEEDS), Items.WHITE_DYE, Items.WHITE_DYE);
        var twoPerCraft = recipe(2, Ingredient.of(Items.WHEAT_SEEDS), Items.WHITE_DYE, Items.WHITE_DYE);
        var pattern = pattern(4, input(2, stack(Items.WHITE_DYE, 2)), input(2, stack(Items.WHEAT_SEEDS, 1)));
        assertSame(twoPerCraft, select(pattern, onePerCraft, twoPerCraft));
        assertNull(select(pattern(3, input(4, stack(Items.WHITE_DYE, 1))), twoPerCraft));
        assertNull(select(pattern(0, input(4, stack(Items.WHITE_DYE, 1))), twoPerCraft));
    }

    @Test
    void alternativesRemainChoicesAndCanUseANonFirstAlternative() {
        var matching = recipe(1, Ingredient.EMPTY, Items.WHITE_DYE);
        var alternatives = input(1, stack(Items.RED_DYE, 1), stack(Items.WHITE_DYE, 1));
        assertSame(matching, select(pattern(1, alternatives), matching));
        assertNull(select(pattern(1, alternatives), recipe(1, Ingredient.EMPTY, Items.RED_DYE, Items.WHITE_DYE)));
    }

    @Test
    void preservesFirstValidCandidateAndLeavesWaterAndExtrasToPlanning() {
        var first = recipe(1, Ingredient.EMPTY, Items.WHITE_DYE);
        var second = recipe(1, Ingredient.EMPTY, Items.WHITE_DYE);
        var water = new GenericStack(AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER),
                AEFluidKey.AMOUNT_BUCKET);
        for (var extra : List.of(stack(Items.WATER_BUCKET, 1), water, stack(Items.COBBLESTONE, 1))) {
            assertSame(first, select(pattern(1, input(1, stack(Items.WHITE_DYE, 1)), input(1, extra)), first, second));
        }
        assertSame(first, select(pattern(1, input(1, stack(Items.WHITE_DYE, 1))), first, second));
    }

    @Test
    void rejectsOverflowInsteadOfWrappingInputAmounts() {
        var recipe = recipe(1, Ingredient.EMPTY, Items.WHITE_DYE);
        assertNull(select(pattern(1, input(2, stack(Items.WHITE_DYE, Long.MAX_VALUE))), recipe));
        assertNull(select(pattern(1, input(1, stack(Items.WHITE_DYE, Long.MAX_VALUE)),
                input(1, stack(Items.WHITE_DYE, 1))), recipe));
    }

    @Test
    void keepsStrictOutputNbtValidation() {
        var result = new ItemStack(Items.DANDELION);
        result.getOrCreateTag().putString("variant", "special");
        var recipe = recipe(result, Ingredient.EMPTY, Items.WHITE_DYE);
        assertNull(select(pattern(1, input(1, stack(Items.WHITE_DYE, 1))), recipe));
    }

    @Test
    void largeFailedCartesianSearchStopsAfter4096Branches() {
        var attempts = new AtomicInteger();
        var alternatives = new GenericStack[16];
        Arrays.fill(alternatives, stack(Items.RED_DYE, 1));
        var inputs = new IPatternDetails.IInput[9];
        Arrays.fill(inputs, countedInput(attempts, alternatives));

        assertNull(select(pattern(1, inputs), recipe(1, Ingredient.EMPTY, Items.WHITE_DYE)));
        assertEquals(4096, attempts.get());
    }

    @Test
    void candidatesShareBudgetAndDoNotContinueWithAFreshBudget() {
        var attempts = new AtomicInteger();
        var alternatives = new GenericStack[3000];
        Arrays.fill(alternatives, stack(Items.RED_DYE, 1));
        var pattern = pattern(1, countedInput(attempts, alternatives));
        var wrong = recipe(1, Ingredient.EMPTY, Items.WHITE_DYE);
        var matching = recipe(1, Ingredient.EMPTY, Items.RED_DYE);

        // First candidate spends 3000 branches; the second gets only 1096.
        assertNull(select(pattern, wrong, wrong, matching));
        assertEquals(4096, attempts.get());

        // The budget belongs to one lookup, not static state or the pattern.
        attempts.set(0);
        assertSame(matching, select(pattern, matching));
        assertEquals(1, attempts.get());
    }

    @Test
    void nonFirstAlternativeStillSucceedsWithinSharedBudget() {
        var attempts = new AtomicInteger();
        var pattern = pattern(1,
                countedInput(attempts, stack(Items.RED_DYE, 1), stack(Items.WHITE_DYE, 1)),
                countedInput(attempts, stack(Items.WHEAT_SEEDS, 1)));
        var wrong = recipe(1, Ingredient.of(Items.WHEAT_SEEDS), Items.BLUE_DYE);
        var matching = recipe(1, Ingredient.of(Items.WHEAT_SEEDS), Items.WHITE_DYE);

        assertSame(matching, select(pattern, wrong, matching));
        assertEquals(8, attempts.get());
    }

    @Test
    void lastPermittedBranchCanStillMatch() {
        var attempts = new AtomicInteger();
        var alternatives = new GenericStack[4096];
        Arrays.fill(alternatives, stack(Items.RED_DYE, 1));
        alternatives[4095] = stack(Items.WHITE_DYE, 1);
        var matching = recipe(1, Ingredient.EMPTY, Items.WHITE_DYE);

        assertSame(matching, select(pattern(1, countedInput(attempts, alternatives)), matching));
        assertEquals(4096, attempts.get());
    }

    @Test
    void invalidAlternativesAlsoConsumeTheBranchBudget() {
        var attempts = new AtomicInteger();
        var alternatives = new GenericStack[4097];
        Arrays.fill(alternatives, stack(Items.WHITE_DYE, 0));
        alternatives[4096] = stack(Items.WHITE_DYE, 1);

        assertNull(select(pattern(1, countedInput(attempts, alternatives)),
                recipe(1, Ingredient.EMPTY, Items.WHITE_DYE)));
        assertEquals(4096, attempts.get());
    }

    @Test
    void accepts64InputsButRejects65BeforeTryingAnyBranch() {
        var attempts = new AtomicInteger();
        var inputs = new IPatternDetails.IInput[64];
        Arrays.fill(inputs, countedInput(attempts, stack(Items.WHITE_DYE, 1)));
        var matching = recipe(1, Ingredient.EMPTY, Items.WHITE_DYE);
        assertSame(matching, select(pattern(1, inputs), matching));
        assertEquals(64, attempts.get());

        attempts.set(0);
        assertNull(select(pattern(1, Arrays.copyOf(inputs, 65)), matching));
        assertEquals(0, attempts.get());
    }

    @Test
    void retainsGreedyIngredientAndInputOrderInsteadOfBacktrackingAssignments() {
        var ingredients = NonNullList.of(Ingredient.EMPTY,
                Ingredient.of(Items.RED_DYE, Items.WHITE_DYE), Ingredient.of(Items.RED_DYE));
        var recipe = (Recipe<?>) Proxy.newProxyInstance(Recipe.class.getClassLoader(),
                new Class<?>[] {Recipe.class, WithReagent.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getIngredients" -> ingredients;
                    case "getReagent" -> Ingredient.EMPTY;
                    case "getResultItem" -> new ItemStack(Items.DANDELION);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var red = input(1, stack(Items.RED_DYE, 1));
        var white = input(1, stack(Items.WHITE_DYE, 1));

        assertNull(select(pattern(1, red, white), recipe));
        assertSame(recipe, select(pattern(1, white, red), recipe));
    }

    private static IPatternDetails.IInput countedInput(AtomicInteger attempts, GenericStack... possible) {
        return (IPatternDetails.IInput) Proxy.newProxyInstance(IPatternDetails.IInput.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.IInput.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPossibleInputs" -> possible;
                    case "getMultiplier" -> {
                        assertTrue(attempts.incrementAndGet() <= 4096, "Search exceeded its branch budget");
                        yield 1L;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Recipe<?> select(IPatternDetails pattern, Recipe<?>... recipes) {
        return BotaniaRecipeLookup.findPetalApothecaryByOutput(List.of(recipes), RegistryAccess.EMPTY, pattern);
    }

    private static Recipe<?> recipe(int count, Ingredient reagent, Item... petals) {
        return recipe(new ItemStack(Items.DANDELION, count), reagent, petals);
    }

    private static Recipe<?> recipe(ItemStack result, Ingredient reagent, Item... petals) {
        var ingredients = NonNullList.<Ingredient>create();
        for (var petal : petals) {
            ingredients.add(Ingredient.of(petal));
        }
        return (Recipe<?>) Proxy.newProxyInstance(Recipe.class.getClassLoader(),
                new Class<?>[] {Recipe.class, WithReagent.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getIngredients" -> ingredients;
                    case "getReagent" -> reagent;
                    case "getResultItem" -> result;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IPatternDetails pattern(long count, IPatternDetails.IInput... inputs) {
        return (IPatternDetails) Proxy.newProxyInstance(IPatternDetails.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputs" -> inputs;
                    case "getOutputs" -> new GenericStack[] {stack(Items.DANDELION, count)};
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IPatternDetails.IInput input(long multiplier, GenericStack... possible) {
        return (IPatternDetails.IInput) Proxy.newProxyInstance(IPatternDetails.IInput.class.getClassLoader(),
                new Class<?>[] {IPatternDetails.IInput.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPossibleInputs" -> possible;
                    case "getMultiplier" -> multiplier;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static GenericStack stack(Item item, long count) {
        return new GenericStack(AEItemKey.of(item), count);
    }

    private void setReflection(String name, Object value) throws ReflectiveOperationException {
        var field = BotaniaReflection.class.getDeclaredField(name);
        field.setAccessible(true);
        savedReflection.put(field, field.get(null));
        field.set(null, value);
    }

    public interface WithReagent {
        Ingredient getReagent();
    }
}

package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;

import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;
import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;

/**
 * Centralised Botania recipe lookups + pattern-output validation.
 *
 * <p>Each {@code findXxx(...)} method iterates the relevant
 * {@link RecipeType} and returns the first {@link Recipe} whose
 * result item matches the pattern's declared output. For single-output
 * recipes, the helper also enforces the anti-duplication rule by
 * comparing the pattern key against the recipe's real result key
 * (STRICT == full {@link AEItemKey}, ID_ONLY == item id only).
 */
public final class BotaniaRecipeLookup {

    private BotaniaRecipeLookup() {
    }

    // ===== Generic helpers =====

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Recipe<?>> recipesOf(ServerLevel level, @Nullable RecipeType<?> type) {
        if (type == null) {
            return List.of();
        }
        try {
            return (List<Recipe<?>>) (List<?>) level.getRecipeManager()
                    .getAllRecipesFor((RecipeType) type);
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    @Nullable
    private static ItemStack safeResult(Recipe<?> recipe, ServerLevel level) {
        try {
            return recipe.getResultItem(level.registryAccess());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /**
     * Returns the pattern's primary output {@link AEItemKey}, or empty
     * when the pattern declares zero or multiple item outputs.
     */
    public static Optional<AEItemKey> patternPrimaryOutputKey(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return Optional.empty();
        }
        var first = outputs[0];
        return first.what() instanceof AEItemKey key ? Optional.of(key) : Optional.empty();
    }

    /**
     * Returns the pattern's primary output count (item amount), or 0
     * when not exactly one item output is declared.
     */
    public static long patternPrimaryOutputAmount(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return 0L;
        }
        return outputs[0].amount();
    }

    /**
     * Bind-time key validation: confirms the pattern's declared output
     * <i>key</i> equals {@code AEItemKey.of(actualResult)} (or
     * {@code dropSecondary} under {@code ID_ONLY}) without checking the
     * amount. Amount is verified at plan time by
     * {@link #validatePatternBatchOutput} once {@code craftRatio} is known.
     *
     * <p>This is the anti-dup invariant for the output side: a pattern can
     * only bind a recipe whose product NBT matches what the pattern claims
     * to deliver (STRICT), or whose item id matches under the explicit
     * ID_ONLY opt-in.
     */
    public static boolean validatePatternOutputKey(IPatternDetails pattern, ItemStack actualResult) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)) {
            return false;
        }
        var actualKey = AEItemKey.of(actualResult);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actualKey.dropSecondary())
                : expectedKey.equals(actualKey);
    }

    /**
     * Batch-output validation: the pattern's declared output key must
     * still equal {@code AEItemKey.of(actualResult)} (or {@code dropSecondary}
     * under ID_ONLY), and the pattern's amount must equal the batched
     * total count {@code craftRatio * actualResult.getCount()}.
     * Anti-dup invariant for batched single-output recipes (mana pool,
     * runic altar, terra plate, ...).
     */
    public static boolean validatePatternBatchOutput(IPatternDetails pattern, ItemStack actualResult,
                                                     long expectedTotalCount) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)) {
            return false;
        }
        if (expected.amount() != expectedTotalCount) {
            return false;
        }
        var actualKey = AEItemKey.of(actualResult);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actualKey.dropSecondary())
                : expectedKey.equals(actualKey);
    }

    /**
     * Multi-output validation (elven trade etc.): every recipe output
     * must appear in the pattern's outputs (as a {@link AEItemKey} with
     * matching count), and the pattern must not declare anything else.
     * Anti-dup invariant for multi-output recipes.
     */
    public static boolean validatePatternMultiOutput(IPatternDetails pattern, List<ItemStack> actualResults) {
        var patternOutputs = pattern.getOutputs();
        if (patternOutputs.length != actualResults.size()) {
            return false;
        }
        // Build expected list of (item, count) from recipe outputs.
        var expected = new ArrayList<int[]>();
        var expectedKeys = new ArrayList<AEItemKey>();
        for (var stack : actualResults) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            expectedKeys.add(AEItemKey.of(stack));
            expected.add(new int[] { stack.getCount() });
        }
        var matched = new boolean[expected.size()];
        boolean idOnly = OverloadPatternSemantics.isIdOnlyOutput(pattern, 0);
        for (var patternOut : patternOutputs) {
            if (!(patternOut.what() instanceof AEItemKey patternKey)) {
                return false;
            }
            int hitIdx = -1;
            for (int i = 0; i < expectedKeys.size(); i++) {
                if (matched[i]) {
                    continue;
                }
                var expectedKey = expectedKeys.get(i);
                boolean keyOk = idOnly
                        ? patternKey.dropSecondary().equals(expectedKey.dropSecondary())
                        : patternKey.equals(expectedKey);
                if (keyOk && patternOut.amount() == expected.get(i)[0]) {
                    hitIdx = i;
                    break;
                }
            }
            if (hitIdx < 0) {
                return false;
            }
            matched[hitIdx] = true;
        }
        for (boolean m : matched) {
            if (!m) {
                return false;
            }
        }
        return true;
    }

    // ===== Mana Infusion (Mana Pool) =====

    /**
     * Scans Mana Infusion recipes for one whose result item matches the
     * pattern's declared output. The pattern is validated against the
     * recipe's real result key on success (anti-dup).
     */
    @Nullable
    public static Recipe<?> findManaInfusionByOutput(ServerLevel level, IPatternDetails pattern) {
        var patternKey = patternPrimaryOutputKey(pattern).orElse(null);
        if (patternKey == null) {
            return null;
        }
        for (var recipe : recipesOf(level, BotaniaReflection.manaInfusionType())) {
            var result = safeResult(recipe, level);
            if (result == null || result.isEmpty()) {
                continue;
            }
            if (result.getItem() != patternKey.getItem()) {
                continue;
            }
            if (!validatePatternOutputKey(pattern, result)) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    // ===== Petal Apothecary =====

    @Nullable
    public static Recipe<?> findPetalApothecaryByOutput(ServerLevel level, IPatternDetails pattern) {
        var patternKey = patternPrimaryOutputKey(pattern).orElse(null);
        if (patternKey == null) {
            return null;
        }
        for (var recipe : recipesOf(level, BotaniaReflection.petalType())) {
            var result = safeResult(recipe, level);
            if (result == null || result.isEmpty()) {
                continue;
            }
            if (result.getItem() != patternKey.getItem()) {
                continue;
            }
            if (!validatePatternOutputKey(pattern, result)) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    // ===== Runic Altar =====

    /**
     * Locates a Rune Altar recipe whose declared outputs &mdash; primary
     * result <i>plus</i> any catalyst returns &mdash; exactly match the
     * pattern's outputs. RunicAltarRecipe extends {@code RecipeWithCatalysts}:
     * the catalysts (e.g. rune_water + rune_winter for envy) are placed in
     * the altar inventory together with the ingredients, are not consumed
     * by the craft, and are restored after assembly via
     * {@code getRemainingItems(input)}. AE2 patterns for such recipes
     * therefore include the catalysts in the declared outputs list as
     * one stack of count 1 per catalyst &mdash; a bind that only looked
     * at the primary output would fail to find the recipe and the player
     * would see the craft never dispatch.
     */
    @Nullable
    public static Recipe<?> findRunicAltarByOutput(ServerLevel level, IPatternDetails pattern) {
        var patternOutputs = pattern.getOutputs();
        if (patternOutputs.length == 0) {
            return null;
        }
        for (var recipe : recipesOf(level, BotaniaReflection.runeType())) {
            var result = safeResult(recipe, level);
            if (result == null || result.isEmpty()) {
                continue;
            }
            var catalysts = BotaniaReflection.recipeCatalysts(recipe);
            if (validatePatternRunicOutputs(pattern, result, catalysts)) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * Mirrors the altar tile's own recipe resolution ({@code updateRecipe} /
     * {@code onUsedByWand} fall back to
     * {@code getRecipeFor(RUNE_TYPE, inventory, level)}): the first runic
     * altar recipe whose {@code matches} accepts the live altar inventory.
     *
     * <p>This exists because Botania 1.20.x never assigns
     * {@code RunicAltarBlockEntity#currentRecipe} &mdash; the field is only
     * ever read (and reset to null after a craft), so a cached field read
     * from our extract scan is always null. Calling
     * {@code recipe.matches} directly through the vanilla {@link Recipe}
     * interface is SRG-safe: the call site is reobfuscated at build time.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Recipe<?> findRunicAltarMatch(ServerLevel level, Container inventory) {
        for (var recipe : recipesOf(level, BotaniaReflection.runeType())) {
            try {
                if (((Recipe) recipe).matches(inventory, level)) {
                    return recipe;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Malformed third-party recipe; try the next one.
            }
        }
        return null;
    }

    /**
     * Anti-dup validation for Rune Altar pattern outputs.
     *
     * <p>The pattern must declare <b>exactly</b>:
     * <ul>
     *   <li>one entry equal to {@code primaryResult} &mdash; NBT-strict
     *       under STRICT, item-id-only under ID_ONLY &mdash; with amount
     *       equal to {@code primaryResult.getCount()} (the altar runs
     *       one cycle per dispatch, so the pattern's primary count
     *       <i>must</i> equal the recipe's primary count).</li>
     *   <li>one entry per non-empty catalyst {@link Ingredient}, with
     *       amount {@code 1}, whose item passes {@code ingredient.test}.
     *       Catalyst matching uses {@code Ingredient.test} regardless of
     *       output match mode &mdash; catalysts are returned verbatim, so
     *       an "id-only" override on the output side cannot upgrade a
     *       cheaper rune variant.</li>
     * </ul>
     * No extras, no missing entries. Returns false for patterns that
     * either declare the primary alone (missing catalysts &rArr; AE
     * would silently lose the returned runes) or declare more than the
     * recipe really produces (the catalysts can't supply phantom counts).
     */
    public static boolean validatePatternRunicOutputs(IPatternDetails pattern,
                                                      ItemStack primaryResult,
                                                      List<Ingredient> catalysts) {
        var patternOutputs = pattern.getOutputs();
        int nonEmptyCatalystCount = 0;
        for (var c : catalysts) {
            if (!c.isEmpty()) {
                nonEmptyCatalystCount++;
            }
        }
        if (patternOutputs.length != 1 + nonEmptyCatalystCount) {
            return false;
        }
        boolean idOnly = OverloadPatternSemantics.isIdOnlyOutput(pattern, 0);
        var primaryKey = AEItemKey.of(primaryResult);
        boolean primaryMatched = false;
        var usedCatalysts = new boolean[catalysts.size()];

        for (var patOut : patternOutputs) {
            if (!(patOut.what() instanceof AEItemKey patKey)) {
                return false;
            }
            // Prefer the primary slot. Catalysts and primary share an id
            // space in theory but RunicAltarRecipe forbids overlap, so
            // first-come-first-served on primary is unambiguous here.
            if (!primaryMatched) {
                boolean primaryKeyOk = idOnly
                        ? patKey.dropSecondary().equals(primaryKey.dropSecondary())
                        : patKey.equals(primaryKey);
                if (primaryKeyOk && patOut.amount() == primaryResult.getCount()) {
                    primaryMatched = true;
                    continue;
                }
            }
            // Try assigning to a still-free catalyst slot.
            boolean catalystMatched = false;
            for (int i = 0; i < catalysts.size(); i++) {
                if (usedCatalysts[i]) {
                    continue;
                }
                var cat = catalysts.get(i);
                if (cat.isEmpty()) {
                    continue;
                }
                if (patOut.amount() != 1L) {
                    // Catalysts are always returned 1-for-1; any other
                    // amount means the pattern doesn't model this recipe.
                    continue;
                }
                if (!cat.test(patKey.toStack(1))) {
                    continue;
                }
                usedCatalysts[i] = true;
                catalystMatched = true;
                break;
            }
            if (!catalystMatched) {
                return false;
            }
        }
        return primaryMatched;
    }

    // ===== Terra Plate (Terrestrial Agglomeration) =====

    @Nullable
    public static Recipe<?> findTerraPlateByOutput(ServerLevel level, IPatternDetails pattern) {
        var patternKey = patternPrimaryOutputKey(pattern).orElse(null);
        if (patternKey == null) {
            return null;
        }
        for (var recipe : recipesOf(level, BotaniaReflection.terraPlateType())) {
            var result = safeResult(recipe, level);
            if (result == null || result.isEmpty()) {
                continue;
            }
            if (result.getItem() != patternKey.getItem()) {
                continue;
            }
            if (!validatePatternOutputKey(pattern, result)) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    // ===== Elven Trade (Alfheim Portal) =====

    /**
     * Elven Trade recipes can emit multiple stacks per craft. The
     * pattern's outputs list must contain exactly the recipe's outputs
     * (no more, no fewer, matching counts).
     */
    @Nullable
    public static Recipe<?> findElvenTradeByOutputs(ServerLevel level, IPatternDetails pattern) {
        var patternOutputs = pattern.getOutputs();
        if (patternOutputs.length == 0) {
            return null;
        }
        for (var recipe : recipesOf(level, BotaniaReflection.elvenTradeType())) {
            var outputs = elvenTradeOutputs(recipe);
            if (outputs.isEmpty()) {
                continue;
            }
            if (!validatePatternMultiOutput(pattern, outputs)) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    /**
     * Reflective accessor for {@code ElvenTradeRecipe.getOutputs()}, used
     * by the Alfheim Portal adapter to derive virtual product stacks.
     */
    @SuppressWarnings("unchecked")
    public static List<ItemStack> elvenTradeOutputs(Object recipe) {
        if (!BotaniaReflection.isElvenTradeRecipe(recipe)) {
            return List.of();
        }
        try {
            var m = ReflectionSupport.findMethodCached(recipe.getClass(), "getOutputs").orElse(null);
            if (m == null) {
                return List.of();
            }
            var v = m.invoke(recipe);
            return v instanceof List<?> list ? (List<ItemStack>) list : List.of();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return List.of();
        }
    }

    // ===== Recipe-specific scalar getters =====

    public static int manaInfusionCost(Object recipe) {
        return BotaniaReflection.invokeRecipeIntMethod(recipe, "getManaToConsume", 0);
    }

    public static int runicAltarManaCost(Object recipe) {
        return BotaniaReflection.invokeRecipeIntMethod(recipe, "getMana", 0);
    }

    public static int terraPlateManaCost(Object recipe) {
        return BotaniaReflection.invokeRecipeIntMethod(recipe, "getMana", 0);
    }

    /**
     * For Mana Infusion recipes: returns the {@code StateIngredient}
     * representing the required catalyst block under the pool (or
     * {@code null} when the recipe needs no catalyst).
     */
    @Nullable
    public static Object manaInfusionCatalyst(Object recipe) {
        try {
            var m = ReflectionSupport.findMethodCached(recipe.getClass(), "getRecipeCatalyst").orElse(null);
            if (m == null) {
                return null;
            }
            return m.invoke(recipe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}

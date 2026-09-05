package com.moakiee.ae2lt.packaged.logic.multiblock;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CompatibilityReflectionContractTest {
    @Test
    void DraconicFusionMutationsRequireHandlesAndPropagateFailures() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/de/"
                        + "DraconicFusionCraftingAdapter.java");

        assertTrue(source.contains("canStartCraft(be)"));
        assertTrue(source.contains("canStartCraft(core)"));
        assertTrue(source.contains("canSetCatalystStack(be)"));
        assertTrue(source.contains("canSetInjectorStack(core, be)"));
        assertTrue(source.contains("ReflectionSupport.invokeMutation(startCraftMethod, be)"));
        assertTrue(source.contains("ReflectionSupport.invokeMutation(setCatalystStackMethod, be, stack)"));
        assertTrue(source.contains("ReflectionSupport.invokeMutation(setInjectorStackMethod, be, stack)"));
        assertTrue(source.contains("setOutputStackMethod.invoke(be, ItemStack.EMPTY)"));
    }

    @Test
    void MalumCatalystExtractionRequiresTheFullIdleGuard() throws IOException {
        var adapter = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/malum/"
                        + "MalumSpiritFocusingAdapter.java");
        var reflection = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/malum/MalumReflection.java");

        assertTrue(adapter.contains("if (!MalumReflection.isCrucibleIdle(be))"));
        assertTrue(reflection.contains("crucibleRecipeField == null"));
        assertTrue(reflection.contains("crucibleProgressField == null"));
        assertTrue(reflection.contains("optionalFalse(crucibleCraftingField, crucibleCraftingMethod, be)"));
        assertTrue(reflection.contains("if (field == null)"));
        assertTrue(reflection.contains("progress instanceof Number number"));
        assertTrue(reflection.contains("return fieldIsNull(crucibleRecipeField, be)"));
        assertTrue(reflection.contains("return fieldIsNull(altarRecipeField, be)"));
        assertTrue(reflection.contains("catch (ReflectiveOperationException | RuntimeException | LinkageError ignored)"));
        assertTrue(reflection.contains("static boolean isCrucibleInactive(BlockEntity be)"));
        assertTrue(reflection.contains("crucibleUpdateMethod = methodInHierarchy(crucibleClass, \"init\")"));
        assertTrue(reflection.contains("fieldInHierarchy(altarClass, \"isCrafting\")"));
        assertTrue(reflection.contains("fieldInHierarchy(crucibleClass, \"isCrafting\")"));
        assertTrue(reflection.contains("methodInHierarchy(altarClass, \"recalculateRecipes\")"));
        assertTrue(reflection.contains("ensureLookup();"));
        assertTrue(reflection.contains("Actionable mode"));
        assertTrue(reflection.contains("mode == Actionable.SIMULATE"));
        assertTrue(reflection.contains("MalumInsertionSemantics.acceptedStackResult"));
        assertTrue(reflection.contains("MalumInsertionSemantics.remainderResult"));
        assertTrue(reflection.contains("altarStateSummary"));
        assertTrue(reflection.contains("static ResourceLocation altarRecipeId"));
        assertTrue(reflection.contains("expectedRecipeId.equals(recipeId)"));
        assertTrue(reflection.contains("return false;"));
    }

    @Test
    void AvaritiaResolvesBothKnownTableClassPaths() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/avaritia/"
                        + "AvaritiaTableAdapter.java");

        assertTrue(source.contains("private static final String[] TABLE_CLASSES"));
        assertTrue(source.contains(
                "committee.nova.mods.avaritia.common.tile.TierCraftTile"));
        assertTrue(source.contains(
                "committee.nova.mods.avaritia.common.tile.tiers.TierCraftTile"));
        assertTrue(source.contains("for (String candidate : TABLE_CLASSES)"));
        assertTrue(source.contains("tableClass = Class.forName(candidate)"));
    }

    @Test
    void MysticalAgricultureReflectionBootstrapsWithDiagnostics() throws IOException {
        var infusion = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/ma/"
                        + "InfusionAltarAdapter.java");
        var awakening = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/ma/"
                        + "AwakeningAltarAdapter.java");

        assertTrue(infusion.contains("ma-infusion-reflection"));
        assertTrue(infusion.contains("MA infusion reflection ready:"));
        assertTrue(infusion.contains("MA infusion class lookup failed:"));
        assertTrue(infusion.contains("MA infusion method lookup failed:"));
        assertTrue(infusion.contains("MA infusion field lookup failed:"));
        assertTrue(infusion.contains("vanillaRecipe.getIngredients()"));
        assertTrue(infusion.contains("activateOrThrow"));
        assertTrue(infusion.contains("isActiveMethod.invoke(be)"));
        assertTrue(infusion.contains("recoverUnstartedDispatch"));
        assertTrue(infusion.contains("isDefinitelyInactive"));
        assertTrue(!infusion.contains("getAltarIngredientMethod.invoke"));

        assertTrue(awakening.contains("ma-awakening-reflection"));
        assertTrue(awakening.contains("MA awakening reflection ready:"));
        assertTrue(awakening.contains("MA awakening class lookup failed:"));
        assertTrue(awakening.contains("MA awakening method lookup failed:"));
        assertTrue(awakening.contains("MA awakening field lookup failed:"));
        assertTrue(awakening.contains("vanillaRecipe.getIngredients()"));
        assertTrue(awakening.contains("activateOrThrow"));
        assertTrue(awakening.contains("isActiveMethod.invoke(be)"));
        assertTrue(awakening.contains("recoverUnstartedDispatch"));
        assertTrue(awakening.contains("isDefinitelyInactive"));
        assertTrue(awakening.contains("ingredients.size() != 9"));
        assertTrue(awakening.contains("for (int i = 2; i < alternating.size(); i += 2)"));
    }

    @Test
    void MalumFocusingHarvestRequiresDispatchOwnedCatalyst() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/malum/"
                        + "MalumSpiritFocusingAdapter.java");

        assertTrue(source.contains("CATALYST_STATE"));
        assertTrue(source.contains("scope.setState(mainPos, catalystStateKey(level)"));
        assertTrue(source.contains("scope.getState(mainPos, catalystStateKey(level))"));
        assertTrue(source.contains("stableCatalystFingerprint(currentCatalyst)"));
        assertTrue(source.contains("equals(expectedCatalyst)"));
        assertTrue(source.contains("itemTag.remove(\"Damage\")"));
        assertTrue(source.contains("scope.hasFlag(mainPos, legacyFlag)"));
        assertTrue(source.contains("scope.clearState(mainPos, catalystStateKey(level))"));
    }

    @Test
    void MalumDroppedOutputsRequirePersistentDispatchOwnership() throws IOException {
        var focusing = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/malum/"
                        + "MalumSpiritFocusingAdapter.java");
        var infusion = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/malum/"
                        + "MalumSpiritInfusionAdapter.java");
        var ownership = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/malum/"
                        + "MalumDroppedItemOwnership.java");

        assertTrue(focusing.contains("MalumDroppedItemOwnership.capture"));
        assertTrue(focusing.contains("MalumDroppedItemOwnership.store"));
        assertTrue(focusing.contains("MalumDroppedItemOwnership.load"));
        assertTrue(!focusing.contains("entity -> true"));
        assertTrue(infusion.contains("MalumDroppedItemOwnership.capture"));
        assertTrue(infusion.contains("MalumDroppedItemOwnership.store"));
        assertTrue(infusion.contains("MalumDroppedItemOwnership.load"));
        assertTrue(infusion.contains("MalumReflection.isAltarIdle(be)"));
        assertTrue(!infusion.contains("entity -> true"));
        assertTrue(ownership.contains("new StringBuilder(dimension.toString())"));
        assertTrue(ownership.contains("preexistingEntityCounts"));
        assertTrue(ownership.contains("Math.max(0, currentCount - baselineCount)"));
        assertTrue(ownership.contains("entry.attributableCount()"));
        assertTrue(ownership.contains("entity.setItem(remainder)"));
        assertTrue(ownership.contains("qualifiedKey(level, stateKey)"));
        assertTrue(ownership.contains("|recipe="));
        assertTrue(ownership.contains("@Nullable ResourceLocation recipeId"));
        assertTrue(focusing.contains("match.output(), match.recipeId()"));
        assertTrue(focusing.contains("ownership.recipeId()"));
        assertTrue(infusion.contains("match.output(), match.recipeId()"));
        assertTrue(!ownership.contains("if (!filter.matches(key))"));
        assertTrue(focusing.contains("throw new DispatchCommitException"));
        assertTrue(infusion.contains("throw new DispatchCommitException"));
        assertTrue(ownership.contains("Retaining stale Malum dispatch ownership"));
    }

    @Test
    void BotaniaInputAssignmentPreservesCompleteAeItemKeys() throws IOException {
        var runic = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/botania/RunicAltarAdapter.java");
        var terra = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/botania/TerraPlateAdapter.java");
        var alfheim = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/botania/AlfheimPortalAdapter.java");
        var petal = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/botania/PetalApothecaryAdapter.java");
        assertTrue(runic.contains("LinkedHashMap<AEItemKey, Long>"));
        assertTrue(terra.contains("LinkedHashMap<AEItemKey, Long>"));
        assertTrue(alfheim.contains("LinkedHashMap<AEItemKey, Long>"));
        assertTrue(petal.contains("previousKey != null && !previousKey.equals(itemKey)"));
    }

    @Test
    void OccultismRitualHarvestTracksNewEntities() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/occultism/"
                        + "OccultismRitualAdapter.java");
        assertTrue(source.contains("RITUAL_DISPATCHED_FLAG"));
        assertTrue(source.contains("RITUAL_HARVEST_STATE"));
        assertTrue(source.contains("OccultismHarvestState"));
        assertTrue(source.contains("captureEntityCounts"));
        assertTrue(source.contains("state.preexistingEntityCounts()"));
        assertTrue(source.contains("scope.setState(mainPos, harvestStateKey(level)"));
        assertTrue(source.contains("OccultismHarvestState.decode(encoded"));
        assertTrue(source.contains("clearHarvestState(level, mainPos, scope)"));
        assertTrue(source.contains("state.claimable"));
        assertTrue(source.contains("entity.setItem(remainder)"));
        var harvestState = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/occultism/OccultismHarvestState.java");
        assertTrue(harvestState.contains("FORMAT_VERSION = 4"));
        assertTrue(harvestState.contains("LEGACY_FORMAT_VERSION = 3"));
        assertTrue(source.contains("captureOutputBowls"));
        assertTrue(source.contains("state.claimableFromBowl(bowlPos, current)"));
        assertTrue(harvestState.contains("output.putLong(\"amount\""));
        assertTrue(harvestState.contains("output.getLong(\"amount\")"));
        assertTrue(source.contains("ritualOutputAabb"));
        assertTrue(source.contains("getEntityToSacrificeMethod = tryMethod(ritualRecipeClass, \"getEntityToSacrifice\")"));
        assertTrue(source.contains("isMatchingSacrificeEgg"));
        assertTrue(!source.contains("HARVEST_STATES"));
        assertTrue(!source.contains("HARVEST_STATE_TTL"));
    }

    @Test
    void ExtremeSmithingOnlyDropsSecondaryDataForFuzzyOutputs() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/avaritia/"
                        + "AvaritiaExtremeSmithingAdapter.java");
        assertTrue(source.contains("OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)"));
        assertTrue(source.contains("Function.identity()"));
    }

    @Test
    void AvaritiaPhysicalAndVirtualPathsShareTierChecksAndDiagnostics() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/avaritia/"
                        + "AvaritiaTableAdapter.java");

        assertTrue(source.contains("findMatchingRecipe(level, input, spec)"));
        assertTrue(source.contains("tableCanCraftRecipe(spec, recipe) && recipeMatches"));
        assertTrue(source.contains("tierRejected"));
        assertTrue(source.contains("recipeWidth"));
        assertTrue(source.contains("requiredTier"));
        assertTrue(source.contains("getInventory returned no IItemHandler"));
        assertTrue(source.contains("recipe type {} is not registered yet"));
        assertTrue(source.contains("runtimeTableSpec"));
        assertTrue(source.contains("declared.acceptsSlotCount(handler.getSlots())"));
        assertTrue(!source.contains("int compatibilityGrid = declared.gridSize() - 2"));
        assertTrue(!source.contains("isKnownTable(declared.blockId())"));
        assertTrue(source.contains("SCULK_TABLE_BLOCK"));
        assertTrue(source.contains("NETHER_TABLE_BLOCK"));
        assertTrue(source.contains("END_TABLE_BLOCK"));
        assertTrue(source.contains("EXTREME_TABLE_BLOCK"));
        assertTrue(source.contains("layoutProfile={}"));
        assertTrue(source.contains("handlerRemainingItemsMethod"));
        assertTrue(source.contains("\"getRemainingItems\", IItemHandler.class"));
        assertTrue(source.contains("remaining.get(slot)"));
        var remainderCheck = source.substring(
                source.indexOf("private static boolean canApplyRemaining"),
                source.indexOf("private static boolean applyCraftRemainders"));
        assertTrue(remainderCheck.contains(
                "!current.isEmpty() && handler.extractItem(slot, 1, true).getCount() != 1"));
        assertTrue(remainderCheck.indexOf("handler.extractItem(slot, 1, true)")
                < remainderCheck.indexOf("var remainder = remaining.get(slot)"));
        assertTrue(remainderCheck.contains("AvaritiaTableRemainderPlanner.canApply"));
        assertTrue(source.contains("AvaritiaTableSpecs.RecipeTier.unknownTiered()"));
        assertTrue(source.contains("AvaritiaTableSpecs.RecipeTier.untiered()"));
        assertTrue(source.contains("tierRecipeAccesses"));
        assertTrue(source.contains("Match Re-Avaritia's getRemainingItems search order exactly"));
        assertTrue(source.indexOf("for (int left = 0; left <= gridSize - width; left++)")
                < source.indexOf("for (int top = 0; top <= gridSize - height; top++)"));
        assertTrue(source.indexOf("shapedRecipeMatches(recipe, input, gridSize, width, height, left, top, true)")
                < source.indexOf("shapedRecipeMatches(recipe, input, gridSize, width, height, left, top, false)"));
        assertTrue(!source.contains("recipeTypeLookupDone"));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}

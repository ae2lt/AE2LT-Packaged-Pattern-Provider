package com.moakiee.ae2lt.packaged.logic.multiblock.occultism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.moakiee.ae2lt.packaged.testsupport.MinecraftTestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

class OccultismHarvestStateTest {
    private static final ResourceLocation DIMENSION =
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation RECIPE =
            ResourceLocation.fromNamespaceAndPath("occultism", "test_ritual");

    private static final BlockPos BOWL = new BlockPos(1, 2, 3);
    private static final BlockPos OTHER_BOWL = BOWL.above();

    @BeforeAll
    static void bootstrapItems() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void roundTripsPersistentItemlessJob() {
        var dispatchId = UUID.randomUUID();
        var entityId = UUID.randomUUID();
        var state = new OccultismHarvestState(dispatchId, DIMENSION, RECIPE, 42L,
                List.of(), Map.of(entityId, 2), List.of(new BlockPos(1, 2, 3)));

        var decoded = OccultismHarvestState.decode(state.encode(), DIMENSION);

        assertTrue(decoded != null);
        assertEquals(dispatchId, decoded.dispatchId());
        assertEquals(RECIPE, decoded.recipeId());
        assertTrue(decoded.complete());
        assertEquals(2, decoded.preexistingEntityCounts().get(entityId));
        assertEquals(new BlockPos(1, 2, 3), decoded.outputBowls().get(0));
    }

    @Test
    void offDispatchRoundTripsWithoutOutputDebtOrOwnership() {
        var state = new OccultismHarvestState(UUID.randomUUID(), DIMENSION, RECIPE, 42L,
                List.of(tagged(4, "a")), Map.of(), List.of(BOWL),
                Map.of(BOWL, ItemStack.EMPTY), false);
        state = roundTrip(roundTrip(state));

        assertFalse(state.autoHarvest());
        assertTrue(state.complete());
        assertTrue(state.remainingOutputs().isEmpty());
        assertEquals(0, state.claimableFromBowl(BOWL, tagged(4, "a")));
        assertEquals(0, state.claimable(tagged(4, "a"), 4));
        assertFalse(roundTrip(state.consume(tagged(4, "a"), 4)).autoHarvest());
    }

    @Test
    void versionFourRetainsAutoOwnedDebt() throws Exception {
        var tag = decodedTag(job(List.of(tagged(4, "a")), Map.of(BOWL, ItemStack.EMPTY)).encode());
        tag.putInt("version", 4);
        tag.remove("autoHarvest");
        var state = OccultismHarvestState.decode(encodedTag(tag), DIMENSION);
        assertNotNull(state);
        state = roundTrip(state);
        assertTrue(state.autoHarvest());
        assertFalse(state.complete());
        assertEquals(4, state.claimableFromBowl(BOWL, tagged(4, "a")));
    }

    @Test
    void malformedModeCannotDiscardAutoOwnedDebt() throws Exception {
        var original = decodedTag(job(List.of(tagged(4, "a")), Map.of(BOWL, ItemStack.EMPTY)).encode());
        var missing = original.copy();
        missing.remove("autoHarvest");
        assertNull(OccultismHarvestState.decode(encodedTag(missing), DIMENSION));
        var wrongType = original.copy();
        wrongType.putString("autoHarvest", "false");
        assertNull(OccultismHarvestState.decode(encodedTag(wrongType), DIMENSION));
        for (byte mode : new byte[] {0, 2, -1}) {
            var tag = original.copy();
            tag.putByte("autoHarvest", mode);
            assertNull(OccultismHarvestState.decode(encodedTag(tag), DIMENSION));
        }
    }

    @Test
    void rejectsLegacyMalformedAndWrongDimensionState() {
        var state = new OccultismHarvestState(UUID.randomUUID(), DIMENSION, RECIPE, 1L,
                List.of(), Map.of(), List.of());

        assertNull(OccultismHarvestState.decode("1,00000000-0000-0000-0000-000000000000", DIMENSION));
        assertNull(OccultismHarvestState.decode("not-base64", DIMENSION));
        assertNull(OccultismHarvestState.decode(state.encode(),
                ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether")));
    }

    @Test
    void partialHarvestAndReloadLeaveOldBowlItemsUntouched() {
        var handler = new ItemStackHandler(1);
        handler.setStackInSlot(0, tagged(5, "owned-before-dispatch"));
        var state = job(List.of(tagged(4, "owned-before-dispatch")),
                Map.of(BOWL, handler.getStackInSlot(0)));

        assertEquals(0, state.claimableFromBowl(BOWL, handler.getStackInSlot(0)));
        handler.setStackInSlot(0, tagged(7, "owned-before-dispatch"));
        int claim = state.claimableFromBowl(BOWL, handler.getStackInSlot(0));
        assertEquals(2, claim);
        var extracted = handler.extractItem(0, claim, false);
        state = roundTrip(state.consume(extracted, extracted.getCount()));

        assertEquals(5, handler.getStackInSlot(0).getCount());
        assertEquals(2, state.remainingOutputs().get(0).getCount());
        assertFalse(state.complete());
        assertEquals(0, state.claimableFromBowl(BOWL, handler.getStackInSlot(0)));
        assertEquals(0, state.claimableFromBowl(BOWL, tagged(3, "owned-before-dispatch")));

        handler.setStackInSlot(0, tagged(7, "owned-before-dispatch"));
        claim = state.claimableFromBowl(BOWL, handler.getStackInSlot(0));
        extracted = handler.extractItem(0, claim, false);
        state = roundTrip(state.consume(extracted, extracted.getCount()));
        assertTrue(state.complete());
        assertEquals(5, handler.getStackInSlot(0).getCount());
        assertEquals(0, state.claimableFromBowl(BOWL, tagged(20, "owned-before-dispatch")));
    }

    @Test
    void baselinesAreIndependentAndOnlyMatchingNbtCountsAreSubtracted() {
        var state = roundTrip(job(List.of(tagged(10, "a"), tagged(4, "b")),
                Map.of(BOWL, tagged(5, "a"), OTHER_BOWL, tagged(2, "b"))));

        assertEquals(2, state.claimableFromBowl(BOWL, tagged(7, "a")));
        assertEquals(4, state.claimableFromBowl(BOWL, tagged(4, "b")));
        assertEquals(2, state.claimableFromBowl(OTHER_BOWL, tagged(4, "b")));
        assertEquals(4, state.claimableFromBowl(OTHER_BOWL, tagged(4, "a")));
        assertEquals(0, state.claimableFromBowl(BOWL, tagged(20, "wrong-nbt")));
        assertEquals(0, state.claimableFromBowl(BOWL, new ItemStack(Items.DIAMOND, 20)));
        assertEquals(0, state.claimableFromBowl(BOWL.above(2), tagged(4, "a")));
    }

    @Test
    void explicitEmptyAndUnrelatedItemBaselinesAllowNewExpectedItems() {
        var state = roundTrip(job(List.of(tagged(4, "a")),
                Map.of(BOWL, ItemStack.EMPTY, OTHER_BOWL, new ItemStack(Items.DIAMOND, 9))));
        assertEquals(4, state.claimableFromBowl(BOWL, tagged(20, "a")));
        assertEquals(4, state.claimableFromBowl(OTHER_BOWL, tagged(4, "a")));
        assertEquals(0, state.claimableFromBowl(BOWL, ItemStack.EMPTY));
    }

    @Test
    void snapshotsAreDefensiveAndLargeCountsSurviveSave() {
        var baseline = tagged(300, "a");
        var expected = tagged(500, "a");
        var baselines = new HashMap<BlockPos, ItemStack>();
        baselines.put(BOWL, baseline);
        var state = job(List.of(expected), baselines);
        baseline.setCount(1);
        baseline.getOrCreateTag().putString("ritual", "changed");
        expected.setCount(1);
        baselines.clear();
        state = roundTrip(state);
        state.remainingOutputs().get(0).setCount(1);

        assertEquals(500, state.remainingOutputs().get(0).getCount());
        assertEquals(0, state.claimableFromBowl(BOWL, tagged(300, "a")));
        assertEquals(200, state.claimableFromBowl(BOWL, tagged(500, "a")));
    }

    @Test
    void versionThreeKeepsPaidJobButNeverGrantsBowlOwnership() throws Exception {
        var entityId = UUID.randomUUID();
        var original = new OccultismHarvestState(UUID.randomUUID(), DIMENSION, RECIPE, 42L,
                List.of(tagged(300, "a")), Map.of(entityId, 5), List.of(BOWL),
                Map.of(BOWL, ItemStack.EMPTY));
        var tag = decodedTag(original.encode());
        tag.putInt("version", 3);
        tag.remove("bowlBaselines");
        var state = OccultismHarvestState.decode(encodedTag(tag), DIMENSION);
        assertNotNull(state);
        assertFalse(state.complete());
        assertEquals(original.dispatchId(), state.dispatchId());
        assertEquals(RECIPE, state.recipeId());
        assertEquals(DIMENSION, state.dimension());
        assertEquals(42L, state.createdTick());
        assertEquals(List.of(BOWL), state.outputBowls());
        assertEquals(300, state.remainingOutputs().get(0).getCount());
        assertEquals(0, state.claimableFromBowl(BOWL, tagged(300, "a")));

        int entityClaim = state.claimable(tagged(7, "a"),
                OccultismHarvestState.attributableCount(7, state.preexistingEntityCounts().get(entityId)));
        assertEquals(2, entityClaim);
        state = roundTrip(state.consume(tagged(2, "a"), entityClaim));
        assertEquals(298, state.remainingOutputs().get(0).getCount());
        assertEquals(5, state.preexistingEntityCounts().get(entityId));
        assertEquals(0, state.claimableFromBowl(BOWL, tagged(500, "a")));
        assertFalse(state.complete());
    }

    @Test
    void missingBaselineStaysUnknownAcrossRepeatedSaves() throws Exception {
        var tag = decodedTag(job(List.of(tagged(4, "a")), Map.of(BOWL, ItemStack.EMPTY)).encode());
        tag.remove("bowlBaselines");
        var state = OccultismHarvestState.decode(encodedTag(tag), DIMENSION);
        assertNotNull(state);
        state = roundTrip(roundTrip(state));
        assertFalse(state.complete());
        assertEquals(4, state.remainingOutputs().get(0).getCount());
        assertEquals(0, state.claimableFromBowl(BOWL, tagged(4, "a")));
    }

    @Test
    void malformedBaselineCannotBecomeAnEmptyBaseline() throws Exception {
        var original = decodedTag(job(List.of(tagged(4, "a")), Map.of(BOWL, tagged(5, "a"))).encode());
        for (long amount : new long[] {-1, 0, (long) Integer.MAX_VALUE + 1}) {
            var tag = original.copy();
            tag.getList("bowlBaselines", Tag.TAG_COMPOUND).getCompound(0).putLong("amount", amount);
            assertNull(OccultismHarvestState.decode(encodedTag(tag), DIMENSION));
        }
        var missingIdentity = original.copy();
        missingIdentity.getList("bowlBaselines", Tag.TAG_COMPOUND).getCompound(0).remove("stack");
        assertNull(OccultismHarvestState.decode(encodedTag(missingIdentity), DIMENSION));
        var duplicate = original.copy();
        var list = duplicate.getList("bowlBaselines", Tag.TAG_COMPOUND);
        list.add(list.getCompound(0).copy());
        assertNull(OccultismHarvestState.decode(encodedTag(duplicate), DIMENSION));
    }

    private static ItemStack tagged(int count, String value) {
        var stack = new ItemStack(Items.IRON_INGOT, count);
        var nested = new CompoundTag();
        nested.putString("owner", value);
        nested.putIntArray("data", new int[] {1, 2, 3});
        stack.getOrCreateTag().put("ritual", nested);
        return stack;
    }

    private static OccultismHarvestState job(List<ItemStack> outputs, Map<BlockPos, ItemStack> baselines) {
        return new OccultismHarvestState(UUID.randomUUID(), DIMENSION, RECIPE, 42L,
                outputs, Map.of(), List.copyOf(baselines.keySet()), baselines);
    }

    private static OccultismHarvestState roundTrip(OccultismHarvestState state) {
        var decoded = OccultismHarvestState.decode(state.encode(), DIMENSION);
        assertNotNull(decoded);
        return decoded;
    }

    private static CompoundTag decodedTag(String encoded) throws Exception {
        return TagParser.parseTag(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
    }

    private static String encodedTag(CompoundTag tag) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                tag.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void attributableCountNeverClaimsBaselineItems() {
        assertEquals(4, OccultismHarvestState.attributableCount(4, 0));
        assertEquals(2, OccultismHarvestState.attributableCount(7, 5));
        assertEquals(0, OccultismHarvestState.attributableCount(3, 5));
    }
}

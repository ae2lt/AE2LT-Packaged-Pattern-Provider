package com.moakiee.ae2lt.packaged.logic.multiblock.binding;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;

class PatternBindingTableTest {
    @Test
    void expiresUnmatchedBindingsAfterNegativeTtl() {
        var table = new PatternBindingTable();
        IPatternDetails pattern = null;
        var binding = PatternBinding.unmatched(0);
        table.put(pattern, binding);
        assertSame(binding, table.getFresh(pattern, 39));
        assertNull(table.getFresh(pattern, 40));
    }

    @Test
    void expiresMatchedBindingsAfterPositiveTtl() {
        var table = new PatternBindingTable();
        IPatternDetails pattern = null;
        var binding = new PatternBinding(
                List.of(new LaneCandidate(
                        new LaneKey.FaceLane(net.minecraft.core.Direction.NORTH),
                        new DummyAdapter(), new Object(), BindingMode.REAL)),
                0);
        table.put(pattern, binding);
        assertSame(binding, table.getFresh(pattern, 199));
        assertNull(table.getFresh(pattern, 200));
    }

    private static final class DummyAdapter implements MultiblockAdapter {
        @Override public int priority() { return 0; }
        @Override public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) { return false; }
        @Override public BindingResult bind(ServerLevel level, BlockPos pos, IPatternDetails pattern) { return null; }
        @Override public boolean canDispatch(ServerLevel level, BlockPos pos, Object handle) { return false; }
        @Override public DispatchPlan planWithBinding(ServerLevel level, BlockPos pos, IPatternDetails pattern,
                KeyCounter[] inputs, Object handle, IActionSource source) { return null; }
        @Override public List<GenericStack> extractOutputs(ServerLevel level, BlockPos pos,
                AllowedOutputFilter filter, IActionSource source) { return List.of(); }
    }
}

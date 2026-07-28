package com.moakiee.ae2lt.packaged.logic.multiblock.mekmm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekmmMachineSpec.PortDef;
import com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekmmMachineSpec.PortGroup;
import com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekmmMachineSpec.PortType;

/**
 * Single adapter that drives every Mekanism: More Machines multiblock
 * controller (large variants of chemical infuser, electrolytic separator,
 * antiprotonic nucleosynthesizer, rotary condensentrator, solar neutron
 * activator, pigment mixer). All per-machine differences live in
 * {@link MekmmMachines} as declarative {@link MekmmMachineSpec} entries.
 *
 * <p>All machines share the same packaged core ({@link AdapterIds#MEKMM}) so the
 * user only needs one item in the provider regardless of which large machine
 * sits in front of it.
 */
public final class MekanismMoreMachinesAdapter implements MultiblockAdapter {

    @Override
    public int priority() {
        return 100;
    }

    @Override
    @Nullable
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.MEKMM;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        if (be == null || !MekReflection.isModLoaded()) return false;
        return findSpec(level, be) != null;
    }

    /**
     * Resolve the {@link MekmmMachineSpec} that owns the given block entity
     * (already known to live at {@code pos} in {@code level}). Walks bounding
     * blocks back to the controller and matches by both registry id and tile
     * class so two different mekmm machines that happen to share a class can
     * coexist.
     */
    @Nullable
    private static MekmmMachineSpec findSpec(ServerLevel level, BlockEntity be) {
        BlockEntity mainBe = be;
        if (MekReflection.isBoundingBlock(be)) {
            BlockPos mainPos = MekReflection.getMainPos(be);
            if (mainPos == null || !level.isLoaded(mainPos)) return null;
            mainBe = level.getBlockEntity(mainPos);
            if (mainBe == null) return null;
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(mainBe.getBlockState().getBlock());
        for (MekmmMachineSpec spec : MekmmMachines.ALL) {
            if (spec.block().equals(blockId)
                    && MekReflection.isTileEntityClass(mainBe, spec.tileClass())) {
                return spec;
            }
        }
        return null;
    }

    private static BlockPos resolveMainPos(ServerLevel level, BlockPos pos) {
        var be = level.getBlockEntity(pos);
        if (be != null && MekReflection.isBoundingBlock(be)) {
            BlockPos resolved = MekReflection.getMainPos(be);
            if (resolved != null) return resolved;
        }
        return pos;
    }

    @Nullable
    private static MekmmMachineSpec specAt(ServerLevel level, BlockPos mainPos) {
        var be = level.getBlockEntity(mainPos);
        if (be == null) return null;
        return findSpec(level, be);
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        mainPos = resolveMainPos(level, mainPos);
        MekmmMachineSpec spec = specAt(level, mainPos);
        if (spec == null) return null;
        return new BindingResult(spec, BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof MekmmMachineSpec spec)) return false;
        mainPos = resolveMainPos(level, mainPos);
        var be = level.getBlockEntity(mainPos);
        if (be == null || !MekReflection.isTileEntityClass(be, spec.tileClass())) return false;
        return !spec.idleGated() || !MekReflection.isActive(be);
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof MekmmMachineSpec spec)) return null;
        mainPos = resolveMainPos(level, mainPos);
        var be = level.getBlockEntity(mainPos);
        if (be == null || !MekReflection.isTileEntityClass(be, spec.tileClass())) return null;
        if (spec.idleGated() && MekReflection.isActive(be)) return null;

        PortGroup mode = spec.activeMode(be);
        Direction facing = MekReflection.getFacing(be);

        // Bucket the pattern inputs by type so we can hand them out to ports in
        // declaration order without caring which slot ended up at index 0.
        Map<PortType, List<Map.Entry<AEKey, Long>>> bucketed = new EnumMap<>(PortType.class);
        bucketed.put(PortType.ITEM, new ArrayList<>());
        bucketed.put(PortType.FLUID, new ArrayList<>());
        bucketed.put(PortType.CHEMICAL, new ArrayList<>());
        for (var counter : inputs) {
            for (var entry : counter) {
                AEKey key = entry.getKey();
                long amount = entry.getLongValue();
                if (amount <= 0) continue;
                PortType type = classify(key);
                if (type == null) return null;
                bucketed.get(type).add(Map.entry(key, amount));
            }
        }

        Map<PortType, Integer> cursor = new EnumMap<>(PortType.class);
        var targets = new ArrayList<TargetSlot>();
        for (PortDef port : mode.inputs()) {
            int idx = cursor.getOrDefault(port.type(), 0);
            var bucket = bucketed.get(port.type());
            if (idx >= bucket.size()) return null;
            var pair = bucket.get(idx);
            cursor.put(port.type(), idx + 1);

            BlockPos portPos = portPos(mainPos, facing, port.spec());
            Direction portSide = portSide(facing, port.spec());
            if (!level.isLoaded(portPos)) return null;

            targets.add(new TargetSlot(level, portPos, portSide,
                    List.of(new GenericStack(pair.getKey(), pair.getValue())),
                    InsertionStrategy.CUSTOM,
                    inserterFor(port.type(), level, portPos, portSide)));
        }

        // Refuse if any pattern input was left unassigned — would silently
        // drop materials otherwise.
        for (var e : bucketed.entrySet()) {
            int used = cursor.getOrDefault(e.getKey(), 0);
            if (used != e.getValue().size()) return null;
        }

        return new DispatchPlan(List.copyOf(targets), null);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        mainPos = resolveMainPos(level, mainPos);
        var be = level.getBlockEntity(mainPos);
        if (be == null) return List.of();
        MekmmMachineSpec spec = findSpec(level, be);
        if (spec == null) return List.of();
        if (spec.idleGated() && MekReflection.isActive(be)) return List.of();

        Direction facing = MekReflection.getFacing(be);
        PortGroup mode = spec.activeMode(be);
        var results = new ArrayList<GenericStack>();
        for (PortDef port : mode.outputs()) {
            BlockPos portPos = portPos(mainPos, facing, port.spec());
            Direction portSide = portSide(facing, port.spec());
            if (!level.isLoaded(portPos)) continue;
            switch (port.type()) {
                case ITEM -> extractItems(level, portPos, portSide, filter, results);
                case FLUID -> extractFluids(level, portPos, portSide, filter, results);
                case CHEMICAL -> extractChemicals(level, portPos, portSide, filter, results);
            }
        }
        return results;
    }

    // ---------- key / port helpers ----------

    @Nullable
    private static PortType classify(AEKey key) {
        if (key instanceof AEItemKey) return PortType.ITEM;
        if (key instanceof AEFluidKey) return PortType.FLUID;
        if (AppmekReflection.isMekanismKey(key)) return PortType.CHEMICAL;
        return null;
    }

    private static BlockPos portPos(BlockPos main, Direction facing, MekPortSpec spec) {
        Direction back = facing.getOpposite();
        Direction left = facing.getClockWise();
        return main.offset(
                back.getStepX() * spec.backSteps() + left.getStepX() * spec.leftSteps(),
                spec.y(),
                back.getStepZ() * spec.backSteps() + left.getStepZ() * spec.leftSteps());
    }

    private static Direction portSide(Direction facing, MekPortSpec spec) {
        return switch (spec.access()) {
            case FRONT -> facing;
            case BACK -> facing.getOpposite();
            case LEFT -> facing.getClockWise();
            case RIGHT -> facing.getCounterClockWise();
            case TOP -> Direction.UP;
            case BOTTOM -> Direction.DOWN;
        };
    }

    // ---------- insertion (per-type custom inserters) ----------

    private static BiFunction<GenericStack, Actionable, Long> inserterFor(
            PortType type, ServerLevel level, BlockPos pos, Direction side) {
        return switch (type) {
            case ITEM -> itemInserter(level, pos, side);
            case FLUID -> fluidInserter(level, pos, side);
            case CHEMICAL -> chemicalInserter(level, pos, side);
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> itemInserter(
            ServerLevel level, BlockPos pos, Direction side) {
        return (stack, mode) -> {
            if (!(stack.what() instanceof AEItemKey itemKey)) return 0L;
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
            if (handler == null) return 0L;
            ItemStack toInsert = itemKey.toStack((int) stack.amount());
            return MekItemInsertion.insertIntoAnySlot(toInsert, mode == Actionable.SIMULATE,
                    new MekItemInsertion.SlotAccess<>() {
                        @Override public int slots() { return handler.getSlots(); }
                        @Override public ItemStack insert(int slot, ItemStack s, boolean sim) {
                            return handler.insertItem(slot, s, sim);
                        }
                        @Override public int amount(ItemStack s) { return s.getCount(); }
                    });
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> fluidInserter(
            ServerLevel level, BlockPos pos, Direction side) {
        return (stack, mode) -> {
            if (!(stack.what() instanceof AEFluidKey fluidKey)) return 0L;
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            if (handler == null) return 0L;
            FluidStack toInsert = fluidKey.toStack((int) stack.amount());
            int filled = handler.fill(toInsert,
                    mode == Actionable.SIMULATE
                            ? IFluidHandler.FluidAction.SIMULATE
                            : IFluidHandler.FluidAction.EXECUTE);
            return (long) filled;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> chemicalInserter(
            ServerLevel level, BlockPos pos, Direction side) {
        return (stack, mode) -> {
            if (!AppmekReflection.isMekanismKey(stack.what())) return 0L;
            Object chemStack = AppmekReflection.toChemicalStackWithAmount(stack.what(), stack.amount());
            if (chemStack == null) return 0L;
            Object handler = MekReflection.getChemicalHandler(level, pos, side);
            if (handler == null) return 0L;
            return MekReflection.insertChemical(handler, chemStack, mode == Actionable.SIMULATE);
        };
    }

    // ---------- extraction (per-type) ----------

    private static void extractItems(ServerLevel level, BlockPos pos, Direction side,
                                     AllowedOutputFilter filter, List<GenericStack> out) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, side);
        if (handler == null) return;
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(stack);
            if (!filter.matches(key)) continue;
            ItemStack extracted = handler.extractItem(i, stack.getCount(), false);
            if (!extracted.isEmpty()) {
                out.add(new GenericStack(AEItemKey.of(extracted), extracted.getCount()));
            }
        }
    }

    private static void extractFluids(ServerLevel level, BlockPos pos, Direction side,
                                      AllowedOutputFilter filter, List<GenericStack> out) {
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
        if (handler == null) return;
        for (int i = 0; i < handler.getTanks(); i++) {
            FluidStack fluid = handler.getFluidInTank(i);
            if (fluid.isEmpty()) continue;
            AEFluidKey key = AEFluidKey.of(fluid);
            if (!filter.matches(key)) continue;
            FluidStack drained = handler.drain(fluid, IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty()) {
                out.add(new GenericStack(key, drained.getAmount()));
            }
        }
    }

    private static void extractChemicals(ServerLevel level, BlockPos pos, Direction side,
                                         AllowedOutputFilter filter, List<GenericStack> out) {
        Object handler = MekReflection.getChemicalHandler(level, pos, side);
        if (handler == null) return;
        int tanks = MekReflection.getChemicalTanks(handler);
        for (int i = 0; i < tanks; i++) {
            Object stack = MekReflection.getChemicalInTank(handler, i);
            if (MekReflection.isChemicalEmpty(stack)) continue;
            AEKey key = AppmekReflection.fromChemicalStack(stack);
            if (key == null || !filter.matches(key)) continue;
            long stored = MekReflection.getChemicalAmount(stack);
            Object extracted = MekReflection.extractChemical(handler, stored, false);
            if (extracted == null || MekReflection.isChemicalEmpty(extracted)) continue;
            out.add(new GenericStack(key, MekReflection.getChemicalAmount(extracted)));
        }
    }

}

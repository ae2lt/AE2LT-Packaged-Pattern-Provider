package com.moakiee.ae2lt.packaged.logic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.util.inv.filter.IAEItemFilter;

import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity;
import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity.ProviderMode;
import com.moakiee.ae2lt.blockentity.OverloadedPatternProviderBlockEntity.ReturnMode;
import com.moakiee.ae2lt.item.OverloadPatternItem;
import com.moakiee.ae2lt.logic.OverloadedPatternProviderLogic;
import com.moakiee.ae2lt.logic.SmartDoublingCompat;
import com.moakiee.ae2lt.logic.energy.PowerCostUtil;
import com.moakiee.ae2lt.mixin.PatternProviderLogicAccessor;
import com.moakiee.ae2lt.packaged.blockentity.PackagedPatternProviderBlockEntity;
import com.moakiee.ae2lt.packaged.item.MultiblockAdapterItem;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterPersistentScope;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchExecutor;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapterRegistry;
import com.moakiee.ae2lt.packaged.logic.multiblock.NeighborMainBlockIndex;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.LaneCandidate;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.LaneCooldownTable;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.LaneKey;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.LaneRateLimiter;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.PatternBinding;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.PatternBindingTable;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.VirtualBatchAccumulator;

/**
 * Packaged pattern provider scheduling.
 *
 * <p>Compared to the parent {@link OverloadedPatternProviderLogic} this class
 * implements:
 * <ul>
 *   <li><b>Bind once on first use.</b> Recipe / structure search runs in
 *       {@link MultiblockAdapter#bind} and caches an opaque handle inside
 *       {@link PatternBindingTable}. Subsequent pushes skip directly to layout.</li>
 *   <li><b>Virtual batch accumulation.</b> Virtual adapters (no in-world delay)
 *       deposit outputs into {@link VirtualBatchAccumulator}; the buffer
 *       flushes every {@value VirtualBatchAccumulator#FLUSH_INTERVAL_TICKS} ticks.
 *       A {@link LaneRateLimiter} caps each lane at 64 pushes per tick.</li>
 *   <li><b>Real dispatch three-step.</b> Per push: pre-extract outputs &rarr;
 *       {@link MultiblockAdapter#canDispatch} &rarr; {@code planWithBinding}.
 *       Failures route the lane through {@link LaneCooldownTable}'s retry
 *       schedule (1, 2, 3, 4, 5, 8, 10, 20, 40, then every 40 ticks).</li>
 *   <li><b>Unified 20-tick auto-return.</b> Real lanes get pulled exactly once
 *       per 20 ticks; virtual lanes are skipped because they never store outputs
 *       in the world.</li>
 * </ul>
 */
public class PackagedPatternProviderLogic extends OverloadedPatternProviderLogic {

    /** Maximum virtual-craft acquisitions per lane per game tick. */
    private static final int VIRTUAL_PUSH_CAP_PER_LANE_PER_TICK = 64;

    /** Auto-return polling interval for real-dispatch lanes. */
    private static final int AUTO_RETURN_INTERVAL_TICKS = 20;

    private final NeighborMainBlockIndex neighborIndex;
    private final PatternBindingTable bindingTable = new PatternBindingTable();
    private final LaneCooldownTable cooldownTable = new LaneCooldownTable();
    private final LaneRateLimiter virtualPushLimiter =
            new LaneRateLimiter(VIRTUAL_PUSH_CAP_PER_LANE_PER_TICK);
    private final VirtualBatchAccumulator virtualBatch = new VirtualBatchAccumulator();

    /** Round-robin cursor across real candidates within a single binding. */
    private int realPushRoundRobin;

    /** Last server tick at which {@link #runAutoReturnTick} executed; -MIN forces first run. */
    private long lastAutoReturnTick = Long.MIN_VALUE;

    /**
     * Sound id captured from the most recent virtual push, replayed by
     * {@link #playFlushSound} when the batch surfaces. Last-write-wins across
     * heterogeneous adapter mixes (rare in practice — providers usually only
     * border one machine kind), and cleared on each flush so an idle window
     * never spuriously replays a stale cue.
     */
    @Nullable
    private ResourceLocation pendingFlushSoundId;

    @Nullable
    private PendingVirtualFlushEffect pendingFlushEffect;

    public PackagedPatternProviderLogic(IManagedGridNode mainNode,
                                        OverloadedPatternProviderBlockEntity host,
                                        int patternInventorySize) {
        super(mainNode, host, patternInventorySize);
        ((PatternProviderLogicAccessor) this).getPatternInventory().setFilter(new IAEItemFilter() {
            @Override
            public boolean allowInsert(InternalInventory inv, int slot, ItemStack stack) {
                return isPackagedPatternStack(stack);
            }
        });
        this.neighborIndex = new NeighborMainBlockIndex(host.getBlockPos());
    }

    // ===== Pattern lifecycle =====

    @Override
    public void updatePatterns() {
        super.updatePatterns();
        bindingTable.invalidateAll();
    }

    @Override
    public void onNeighborChanged() {
        neighborIndex.invalidate();
        bindingTable.invalidateAll();
        cooldownTable.clear();
        super.onNeighborChanged();
    }

    @Override
    public void onHostStateChanged() {
        bindingTable.invalidateAll();
        pruneStaleLaneState();
        super.onHostStateChanged();
    }

    /**
     * Invoked from the mod-level {@code RecipesUpdatedEvent} listener.
     * Wipes every cached binding so the next push re-runs {@code bind()}.
     */
    public void onRecipesReloaded() {
        bindingTable.invalidateAll();
    }

    /**
     * Hook called by the BlockEntity when the packaged core slot is edited.
     * The set of unlock-able adapter ids may have changed, so every cached
     * binding (which encodes the per-candidate "is this core sufficient?"
     * decision implicitly) needs to be recomputed on next push.
     */
    public void onAdapterSlotChanged() {
        bindingTable.invalidateAll();
        cooldownTable.clear();
    }

    /**
     * Provider-owned persistent flag store handed to multiblock adapters.
     *
     * <p>The host {@link PackagedPatternProviderBlockEntity} implements
     * {@link AdapterPersistentScope} directly; this helper just narrows the
     * cast in one place so the rest of the logic can hand the scope around
     * without each call site repeating the type cast (and so a future host
     * variant that doesn't implement the interface still type-checks here,
     * falling back to {@link AdapterPersistentScope#NOOP}).
     */
    private AdapterPersistentScope adapterScope() {
        if (getOverloadedHost() instanceof AdapterPersistentScope scope) {
            return scope;
        }
        return AdapterPersistentScope.NOOP;
    }

    // ===== Push path =====

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!getGridNode().isActive()) {
            return false;
        }
        if (!SmartDoublingCompat.containsOrUnwrapped(getAvailablePatterns(), patternDetails)) {
            return false;
        }
        if (getCraftingLockedReason() != LockCraftingMode.NONE) {
            return false;
        }

        var level = getOverloadedHost().getLevel();
        if (!(level instanceof ServerLevel sl)) {
            return false;
        }

        double cost = PowerCostUtil.totalCost(inputHolder);
        var grid = getGridNode().getGrid();
        if (!PowerCostUtil.canAfford(grid, cost)) {
            return false;
        }

        long gameTick = sl.getGameTime();
        var binding = getOrComputeBinding(sl, patternDetails, gameTick);
        if (!binding.isMatched()) {
            return false;
        }

        if (tryVirtualPush(sl, patternDetails, inputHolder, binding, gameTick)) {
            PowerCostUtil.consumeRaw(grid, cost);
            syncPendingUnlockRule(patternDetails);
            alertGridTick();
            ((PatternProviderLogicAccessor) this).invokeOnPushPatternSuccess(patternDetails);
            return true;
        }

        if (tryRealDispatch(sl, patternDetails, inputHolder, binding, gameTick)) {
            PowerCostUtil.consumeRaw(grid, cost);
            syncPendingUnlockRule(patternDetails);
            alertGridTick();
            ((PatternProviderLogicAccessor) this).invokeOnPushPatternSuccess(patternDetails);
            return true;
        }

        return false;
    }

    /**
     * Tries to admit a single virtual craft into the batch accumulator.
     * One push acquires at most one lane: AE views the push as a single
     * completed craft, regardless of how many candidates exist.
     */
    private boolean tryVirtualPush(ServerLevel level, IPatternDetails pattern, KeyCounter[] inputs,
                                   PatternBinding binding, long gameTick) {
        for (var candidate : binding.candidates()) {
            if (candidate.mode() != BindingMode.VIRTUAL) {
                continue;
            }
            if (!virtualPushLimiter.hasCapacity(candidate.lane(), gameTick)) {
                continue;
            }

            var env = resolveLaneEnv(level, candidate);
            if (env == null) {
                continue;
            }

            if (!(candidate.adapter() instanceof VirtualCraftingAdapter virtualAdapter)) {
                continue;
            }

            var result = virtualAdapter.planVirtualWithBinding(
                    env.level(), env.pos(), pattern, inputs, candidate.handle(), getActionSource());
            if (result == null || result.outputs().isEmpty()) {
                continue;
            }

            if (!virtualPushLimiter.tryAcquire(candidate.lane(), gameTick)) {
                continue;
            }
            virtualBatch.enqueue(result.outputs());
            // Remember the adapter's flush cue for the next batch surface;
            // last-write-wins is fine because providers typically border one
            // machine kind and players hear at most one cue per 10t window.
            var sid = virtualAdapter.flushSoundId();
            if (sid != null) {
                pendingFlushSoundId = sid;
            }
            pendingFlushEffect = new PendingVirtualFlushEffect(
                    virtualAdapter, env.level(), env.pos(), candidate.handle());
            return true;
        }
        return false;
    }

    /**
     * Tries to dispatch one real candidate using the three-step flow:
     * extract first, gate on {@code canDispatch}, then plan + execute.
     */
    private boolean tryRealDispatch(ServerLevel level, IPatternDetails pattern, KeyCounter[] inputs,
                                    PatternBinding binding, long gameTick) {
        var realCandidates = new ArrayList<LaneCandidate>();
        for (var c : binding.candidates()) {
            if (c.mode() == BindingMode.REAL) {
                realCandidates.add(c);
            }
        }
        if (realCandidates.isEmpty()) {
            return false;
        }

        int total = realCandidates.size();
        var filter = getOrBuildOutputFilter();
        for (int i = 0; i < total; i++) {
            int idx = Math.floorMod(realPushRoundRobin + i, total);
            var candidate = realCandidates.get(idx);

            if (!cooldownTable.isReady(candidate.lane(), gameTick)) {
                continue;
            }

            var env = resolveLaneEnv(level, candidate);
            if (env == null) {
                cooldownTable.recordFailure(candidate.lane(), gameTick);
                continue;
            }

            // 1. Pre-extract outputs so the machine has output room
            var extracted = candidate.adapter().extractOutputs(
                    env.level(), env.pos(), filter, getActionSource(), adapterScope());
            if (!extracted.isEmpty()) {
                insertOutputsToReturnInv(extracted);
            }

            // 2. Cheap gate: is the machine accepting?
            if (!candidate.adapter().canDispatch(env.level(), env.pos(), candidate.handle())) {
                cooldownTable.recordFailure(candidate.lane(), gameTick);
                continue;
            }

            // 3. Build and execute the plan
            var plan = candidate.adapter().planWithBinding(
                    env.level(), env.pos(), pattern, inputs, candidate.handle(),
                    getActionSource(), adapterScope());
            if (plan == null
                    || !DispatchExecutor.execute(plan, getActionSource(), getInternalReturnInv()).success()) {
                cooldownTable.recordFailure(candidate.lane(), gameTick);
                continue;
            }

            cooldownTable.recordSuccess(candidate.lane());
            realPushRoundRobin = (idx + 1) % total;
            return true;
        }
        return false;
    }

    // ===== Tick =====

    /**
     * Tell the grid ticker we still have work whenever any virtual product is
     * waiting in the batch. Otherwise the ticker can put us to SLEEP between
     * pushes and the trailing partial batch (the last craft of an order, e.g.
     * the final 4 of a 12-product order on a 9x9 EC table) would never flush
     * &mdash; users see "短 4 个" no matter how large the order is.
     */
    @Override
    public boolean hasAnyTickWork() {
        return super.hasAnyTickWork() || virtualBatch.hasPending();
    }

    @Override
    public void tickAutoReturn() {
        if (!hasAnyTickWork()) {
            return;
        }

        tickWirelessInductionEnergy();

        var level = getOverloadedHost().getLevel();
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        long gameTick = sl.getGameTime();

        // Virtual batch always ticks; outputs surface every 10 ticks.
        // We swap a lambda for the method-ref so a non-empty flush also plays
        // a "products arrived" cue, mirroring vanilla pickup feedback. The
        // accumulator only invokes its sink when there is real product, so
        // the sound never fires on empty 10-tick windows.
        virtualBatch.tickFlush(gameTick, stacks -> {
            insertOutputsToReturnInv(stacks);
            runPendingFlushEffect();
            playFlushSound(sl);
        });

        if (getOverloadedHost().getReturnMode() != ReturnMode.AUTO || !getGridNode().isActive()) {
            return;
        }

        if (lastAutoReturnTick == Long.MIN_VALUE
                || gameTick - lastAutoReturnTick >= AUTO_RETURN_INTERVAL_TICKS) {
            runAutoReturnTick(sl, gameTick);
            lastAutoReturnTick = gameTick;
        }
    }

    private void runAutoReturnTick(ServerLevel level, long gameTick) {
        var filter = getOrBuildOutputFilter();
        if (filter.isEmpty()) {
            return;
        }

        if (getOverloadedHost().getProviderMode() == ProviderMode.NORMAL) {
            autoReturnNormal(level, filter);
        } else {
            autoReturnWireless(level, filter, gameTick);
        }
    }

    private void autoReturnNormal(ServerLevel level, com.moakiee.ae2lt.logic.AllowedOutputFilter filter) {
        for (var face : neighborIndex.adapterFaces(level)) {
            var adapter = neighborIndex.getAdapter(face);
            if (adapter == null || adapter instanceof VirtualCraftingAdapter) {
                continue;
            }
            var pos = getOverloadedHost().getBlockPos().relative(face);
            var outputs = adapter.extractOutputs(level, pos, filter, getActionSource(), adapterScope());
            if (!outputs.isEmpty()) {
                insertOutputsToReturnInv(outputs);
            }
        }
    }

    private void autoReturnWireless(ServerLevel providerLevel,
                                     com.moakiee.ae2lt.logic.AllowedOutputFilter filter,
                                     long gameTick) {
        var valid = getValidConnections(providerLevel, gameTick);
        if (valid.isEmpty()) {
            return;
        }
        var server = providerLevel.getServer();
        for (var conn : valid) {
            var targetLevel = server.getLevel(conn.dimension());
            if (targetLevel == null || !targetLevel.isLoaded(conn.pos())) {
                continue;
            }
            // BE may be null for block-only targets; pass through to find
            // regardless so each adapter can decide whether it applies.
            var be = targetLevel.getBlockEntity(conn.pos());
            var adapter = MultiblockAdapterRegistry.find(targetLevel, conn.pos(), be);
            if (adapter == null || adapter instanceof VirtualCraftingAdapter) {
                continue;
            }
            var outputs = adapter.extractOutputs(targetLevel, conn.pos(), filter, getActionSource(), adapterScope());
            if (!outputs.isEmpty()) {
                insertOutputsToReturnInv(outputs);
            }
        }
    }

    // ===== Binding =====

    private PatternBinding getOrComputeBinding(ServerLevel level, IPatternDetails pattern, long gameTick) {
        var existing = bindingTable.getFresh(pattern, gameTick);
        if (existing != null) {
            return existing;
        }
        var fresh = computeBinding(level, pattern, gameTick);
        bindingTable.put(pattern, fresh);
        return fresh;
    }

    private PatternBinding computeBinding(ServerLevel level, IPatternDetails pattern, long gameTick) {
        var candidates = new ArrayList<LaneCandidate>();
        var mode = getOverloadedHost().getProviderMode();
        var installedCard = installedAdapterStack();

        if (mode == ProviderMode.NORMAL) {
            for (var face : neighborIndex.adapterFaces(level)) {
                var adapter = neighborIndex.getAdapter(face);
                if (adapter == null) {
                    continue;
                }
                var pos = getOverloadedHost().getBlockPos().relative(face);
                if (!isAdapterUnlocked(adapter, level, pos, installedCard)) {
                    continue;
                }
                var result = adapter.bind(level, pos, pattern);
                if (result != null) {
                    candidates.add(new LaneCandidate(
                            new LaneKey.FaceLane(face), adapter, result.handle(), result.mode()));
                }
            }
        } else {
            var server = level.getServer();
            for (var conn : getValidConnections(level, gameTick)) {
                var targetLevel = server.getLevel(conn.dimension());
                if (targetLevel == null || !targetLevel.isLoaded(conn.pos())) {
                    continue;
                }
                // BE may be null for non-tile blocks; each adapter's
                // recognizesMain must filter for itself.
                var be = targetLevel.getBlockEntity(conn.pos());
                var adapter = MultiblockAdapterRegistry.find(targetLevel, conn.pos(), be);
                if (adapter == null) {
                    continue;
                }
                if (!isAdapterUnlocked(adapter, targetLevel, conn.pos(), installedCard)) {
                    continue;
                }
                var result = adapter.bind(targetLevel, conn.pos(), pattern);
                if (result != null) {
                    candidates.add(new LaneCandidate(
                            new LaneKey.ConnLane(conn), adapter, result.handle(), result.mode()));
                }
            }
        }

        return candidates.isEmpty()
                ? PatternBinding.unmatched(gameTick)
                : new PatternBinding(candidates, gameTick);
    }

    /**
     * Resolves a {@link LaneCandidate} to its live world position and verifies the
     * adapter still recognizes the block there. Returns {@code null} when the
     * binding is stale (block changed / chunk unloaded / wireless target gone).
     */
    @Nullable
    private LaneEnv resolveLaneEnv(ServerLevel providerLevel, LaneCandidate candidate) {
        var lane = candidate.lane();
        if (lane instanceof LaneKey.FaceLane(Direction face)) {
            var pos = getOverloadedHost().getBlockPos().relative(face);
            if (!providerLevel.isLoaded(pos)) {
                return null;
            }
            // BE may be null for plain blocks. Defer the "is this still my
            // multiblock?" check to recognizesMain, which each adapter
            // implements with the right BE / BlockState mix.
            var be = providerLevel.getBlockEntity(pos);
            if (!candidate.adapter().recognizesMain(providerLevel, pos, be)) {
                return null;
            }
            return new LaneEnv(providerLevel, pos);
        }
        if (lane instanceof LaneKey.ConnLane connLane) {
            var conn = connLane.connection();
            var targetLevel = providerLevel.getServer().getLevel(conn.dimension());
            if (targetLevel == null || !targetLevel.isLoaded(conn.pos())) {
                return null;
            }
            var be = targetLevel.getBlockEntity(conn.pos());
            if (!candidate.adapter().recognizesMain(targetLevel, conn.pos(), be)) {
                return null;
            }
            return new LaneEnv(targetLevel, conn.pos());
        }
        return null;
    }

    private void pruneStaleLaneState() {
        Set<LaneKey> active = new HashSet<>();
        for (var dir : EnumSet.allOf(Direction.class)) {
            active.add(new LaneKey.FaceLane(dir));
        }
        for (var conn : getOverloadedHost().getConnections()) {
            active.add(new LaneKey.ConnLane(conn));
        }
        cooldownTable.retainAll(active);
        virtualPushLimiter.retainAll(active);
    }

    // ===== Output delivery =====

    /**
     * Inserts packaged-provider outputs without silently swallowing products.
     *
     * <p>AE2LT's machine auto-return now uses transactional output sinks, but
     * virtual crafts produce their outputs directly inside this addon's batch
     * accumulator. If the grid cannot currently pay for or accept those outputs,
     * they must remain queued here because there is no remote machine to re-poll.
     *
     * <p>This override delivers whatever the grid can pay for right now, then
     * pushes any leftover back into {@link #virtualBatch} so the next flush (or
     * the next time energy is available) can finish delivering it.
     */
    protected void insertOutputsToReturnInv(List<GenericStack> outputs) {
        if (outputs.isEmpty()) {
            return;
        }
        var grid = getGridNode().getGrid();
        var returnInv = getInternalReturnInv();
        var retained = new ArrayList<GenericStack>();

        for (var stack : outputs) {
            long total = stack.amount();
            if (total <= 0) {
                continue;
            }
            long actuallyInserted = 0;
            long affordable = PowerCostUtil.maxAffordable(grid, stack.what(), total);
            if (affordable > 0) {
                long inserted = returnInv.insert(0, stack.what(), affordable, Actionable.MODULATE);
                if (inserted > 0) {
                    PowerCostUtil.consume(grid, stack.what(), inserted);
                    actuallyInserted = inserted;
                }
            }
            long undelivered = total - actuallyInserted;
            if (undelivered > 0) {
                retained.add(new GenericStack(stack.what(), undelivered));
            }
        }

        if (!retained.isEmpty()) {
            virtualBatch.enqueue(retained);
            alertGridTick();
        }
    }

    /**
     * Optional "products arrived" cue, played at the provider after a virtual
     * batch surfaces. Only fires when the contributing adapter explicitly
     * supplies a signature sound id via
     * {@link VirtualCraftingAdapter#flushSoundId()}; adapters that don't
     * (e.g. plain crafting tables) stay silent — the player already hears
     * their AE terminal click on receipt, so a generic chime would be noise.
     *
     * <p>Volume / pitch are tuned low so cluster setups (many providers
     * flushing in the same tick) don't roar.
     */
    private void playFlushSound(ServerLevel level) {
        var sound = resolveFlushSoundEvent();
        // Consume the cached id either way so a stale id from one batch can't
        // bleed into the next; the next push will set a fresh one.
        pendingFlushSoundId = null;
        if (sound == null) {
            return;
        }
        var pos = getOverloadedHost().getBlockPos();
        level.playSound(null, pos,
                sound,
                SoundSource.BLOCKS,
                0.5f, 1.0f);
    }

    /**
     * Looks up the most recently cached sound id in the live registry.
     * Returns {@code null} when there's no cached id, or when the id resolves
     * to nothing (mod absent / sound renamed) — callers fall back to the
     * generic cue in that case.
     */
    @Nullable
    private SoundEvent resolveFlushSoundEvent() {
        var id = pendingFlushSoundId;
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.SOUND_EVENT.get(id);
    }

    private void runPendingFlushEffect() {
        var effect = pendingFlushEffect;
        pendingFlushEffect = null;
        if (effect == null) {
            return;
        }
        effect.adapter().onVirtualBatchFlush(
                effect.level(), effect.pos(), effect.handle(), getActionSource());
    }

    // ===== Packaged core gating =====

    /**
     * @return the packaged-core ItemStack currently installed in the provider's
     * adapter slot, or {@link ItemStack#EMPTY} when the slot is empty.
     */
    private ItemStack installedAdapterStack() {
        if (getOverloadedHost() instanceof PackagedPatternProviderBlockEntity packaged) {
            return packaged.getInstalledAdapterStack();
        }
        return ItemStack.EMPTY;
    }

    /**
     * Decides whether the adapter at {@code pos} is allowed to participate in
     * binding given the currently-installed packaged core. Adapters whose
     * {@code requiredAdapterId} returns null are always allowed; otherwise the
     * installed core must {@link MultiblockAdapterItem#covers} the required id
     * (this handles EC's tier-cover-down chain transparently).
     */
    private static boolean isAdapterUnlocked(MultiblockAdapter adapter,
                                              ServerLevel level,
                                              BlockPos pos,
                                              ItemStack installedCard) {
        var required = adapter.requiredAdapterId(level, pos);
        if (required == null) {
            return true;
        }
        return MultiblockAdapterItem.stackCovers(installedCard, required);
    }

    // ===== Helpers =====

    private record LaneEnv(ServerLevel level, BlockPos pos) {}

    private record PendingVirtualFlushEffect(VirtualCraftingAdapter adapter,
                                             ServerLevel level,
                                             BlockPos pos,
                                             Object handle) {
    }

    private static boolean isPackagedPatternStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof OverloadPatternItem overloadPatternItem) {
            return overloadPatternItem.hasPayload(stack);
        }
        return PatternDetailsHelper.isEncodedPattern(stack);
    }

    // ===== Test / debug accessors (package-private) =====

    @SuppressWarnings("unused")
    PatternBindingTable bindingTableForTesting() {
        return bindingTable;
    }

    @SuppressWarnings("unused")
    LaneCooldownTable cooldownTableForTesting() {
        return cooldownTable;
    }

    @SuppressWarnings("unused")
    VirtualBatchAccumulator virtualBatchForTesting() {
        return virtualBatch;
    }

    @SuppressWarnings("unused")
    private List<LaneCandidate> dumpCandidatesForTesting(IPatternDetails pattern) {
        var binding = bindingTable.get(pattern);
        return binding == null ? List.of() : binding.candidates();
    }
}

package com.moakiee.ae2lt.packaged.patternprovider;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.LockCraftingMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.filter.IAEItemFilter;

import com.moakiee.ae2lt.api.patternprovider.WirelessPatternProviderPolicy;
import com.moakiee.ae2lt.api.patternprovider.EncodedPatternPayloadValidator;
import com.moakiee.ae2lt.logic.wireless.support.WirelessConnectionRange;
import com.moakiee.ae2lt.packaged.mixin.PatternProviderLogicAccessor;
import com.moakiee.thunderbolt.core.crafting.overload.OverloadedPatternDetails;

/**
 * PP-owned pattern-provider foundation extracted from AE2LT's 1.1/main logic.
 *
 * <p>This class intentionally owns only behavior used by compatible provider
 * addons: overload-aware pattern loading, return-inventory power accounting,
 * lock-until-result matching, wireless endpoint validation and the AE2 grid
 * ticker bridge. Machine-specific dispatch remains in the addon subclass.
 * AE2LT supplies overload-pattern primitives, while Thunderbolt owns generic
 * infrastructure only; neither dependency owns this packaged-provider
 * implementation.
 */
public class StablePatternProviderLogic extends PatternProviderLogic {
    private static final int GRID_TICK_MIN = 1;
    private static final int GRID_TICK_MAX = 20;
    private static final int VALIDATE_INTERVAL = 20;

    private static final String TAG_UNLOCK_MATCH_MODE = "Ae2ltUnlockMatchMode";
    private static final String TAG_UNLOCK_TEMPLATE = "Ae2ltUnlockTemplate";

    private final StablePatternProviderBlockEntity providerHost;
    private final IManagedGridNode gridNode;
    private final IActionSource actionSource;
    private final UnlimitedPatternProviderReturnInventory returnInventory;

    @Nullable
    private AllowedOutputFilter cachedOutputFilter;
    private boolean outputFilterDirty = true;

    @Nullable
    private Boolean pendingUnlockIdOnly;
    @Nullable
    private ItemStack pendingUnlockTemplate;

    private List<StablePatternProviderBlockEntity.WirelessConnection>
            validConnectionsCache = List.of();
    private long validConnectionsCacheTick = Long.MIN_VALUE;
    private boolean connectionsDirty = true;

    public StablePatternProviderLogic(
            IManagedGridNode mainNode,
            StablePatternProviderBlockEntity host) {
        super(mainNode, host, StablePatternProviderBlockEntity.SLOTS_PER_PAGE);
        this.providerHost = host;
        this.gridNode = mainNode;
        this.actionSource = new MachineSource(mainNode::getNode);

        mainNode.addService(IGridTickable.class, new Ticker());

        var accessor = accessor();
        accessor.ae2ltpp$getPatternInventory().setFilter(new IAEItemFilter() {
            @Override
            public boolean allowInsert(
                    InternalInventory inventory, int slot, ItemStack stack) {
                return isUsableEncodedPatternStack(stack);
            }
        });

        Runnable returnListener = () -> {
            alertGridTick();
            providerHost.saveChanges();
        };
        // 1.20.1's AEKeyFilter is slot-agnostic; the packaged provider's rule
        // never depended on the slot index anyway.
        AEKeyFilter returnFilter = key -> {
            if (!providerHost.isFilteredImport()) {
                return true;
            }
            var filter = getOrBuildOutputFilter();
            return !filter.isEmpty() && filter.matches(key);
        };

        this.returnInventory = UnlimitedPatternProviderReturnInventory.create(
                returnListener, returnFilter);
        accessor.ae2ltpp$setReturnInventory(returnInventory);
    }

    private PatternProviderLogicAccessor accessor() {
        return (PatternProviderLogicAccessor) this;
    }

    protected final StablePatternProviderBlockEntity getProviderHost() {
        return providerHost;
    }

    protected final IManagedGridNode getGridNode() {
        return gridNode;
    }

    protected final IActionSource getActionSource() {
        return actionSource;
    }

    /**
     * Keeps custom encoded-pattern shells out until their implementation says
     * the payload is complete, while retaining vanilla AE2 acceptance rules.
     */
    private boolean isUsableEncodedPatternStack(ItemStack stack) {
        if (!PatternDetailsHelper.isEncodedPattern(stack)) {
            return false;
        }
        return !(stack.getItem() instanceof EncodedPatternPayloadValidator validator)
                || validator.hasEncodedPatternPayload(stack);
    }

    /**
     * Completes the private AE2 success bookkeeping after a custom dispatcher
     * has atomically accepted a pattern.
     */
    protected final void recordSuccessfulPush(IPatternDetails pattern) {
        accessor().ae2ltpp$onPushPatternSuccess(pattern);
        syncPendingUnlockRule(pattern);
        alertGridTick();
    }

    public UnlimitedPatternProviderReturnInventory getInternalReturnInv() {
        return returnInventory;
    }

    long maxAffordableExternalReturn(AEKey what, long amount) {
        return PatternProviderPowerCost.maxAffordable(gridNode.getGrid(), what, amount);
    }

    void consumeExternalReturnPower(AEKey what, long amount) {
        PatternProviderPowerCost.consume(gridNode.getGrid(), what, amount);
    }

    @Override
    public void updatePatterns() {
        var accessor = accessor();
        var patterns = accessor.ae2ltpp$getPatterns();
        var patternInputs = accessor.ae2ltpp$getPatternInputs();
        var inventory = accessor.ae2ltpp$getPatternInventory();

        patterns.clear();
        patternInputs.clear();
        var level = providerHost.getLevel();
        for (int slot = 0; slot < inventory.size(); slot++) {
            var details = PatternDetailsHelper.decodePattern(
                    inventory.getStackInSlot(slot), level);
            if (details == null) {
                continue;
            }
            patterns.add(details);
            for (var input : details.getInputs()) {
                for (var possible : input.getPossibleInputs()) {
                    patternInputs.add(possible.what().dropSecondary());
                }
            }
        }

        outputFilterDirty = true;
        ICraftingProvider.requestUpdate(gridNode);
        alertGridTick();
    }

    protected final AllowedOutputFilter getOrBuildOutputFilter() {
        if (!outputFilterDirty && cachedOutputFilter != null) {
            return cachedOutputFilter;
        }
        var filter = new AllowedOutputFilter();
        for (var pattern : getAvailablePatterns()) {
            if (pattern instanceof OverloadedPatternDetails overload) {
                var actualOutputs = pattern.getOutputs();
                for (int i = 0; i < actualOutputs.length; i++) {
                    var key = actualOutputs[i].what();
                    if (overload.isFuzzyOutput(i)) {
                        filter.allowIdOnly(key);
                    } else {
                        filter.allowStrict(key);
                    }
                }
                continue;
            }
            for (var output : pattern.getOutputs()) {
                filter.allowStrict(output.what());
            }
        }
        cachedOutputFilter = filter;
        outputFilterDirty = false;
        return filter;
    }

    protected final List<StablePatternProviderBlockEntity.WirelessConnection>
            getValidConnections(ServerLevel providerLevel, long gameTick) {
        if (!connectionsDirty
                && gameTick - validConnectionsCacheTick < VALIDATE_INTERVAL) {
            return validConnectionsCache;
        }

        var valid = new ArrayList<StablePatternProviderBlockEntity.WirelessConnection>();
        int maxDistance = WirelessPatternProviderPolicy.maxDistance();
        for (var connection : providerHost.getConnections()) {
            if (!WirelessConnectionRange.isInRange(
                    providerLevel.dimension(),
                    providerHost.getBlockPos(),
                    connection.dimension(),
                    connection.pos(),
                    maxDistance)) {
                continue;
            }
            var targetLevel = providerLevel.getServer().getLevel(connection.dimension());
            if (targetLevel == null || !targetLevel.isLoaded(connection.pos())) {
                continue;
            }
            if (targetLevel.getBlockEntity(connection.pos()) == null) {
                continue;
            }
            valid.add(connection);
        }
        validConnectionsCache = List.copyOf(valid);
        validConnectionsCacheTick = gameTick;
        connectionsDirty = false;
        return validConnectionsCache;
    }

    public void onHostStateChanged() {
        invalidateConnections();
        alertGridTick();
    }

    public void onPersistentStateChanged() {
        alertGridTick();
    }

    public void onNeighborChanged() {
        alertGridTick();
    }

    private void invalidateConnections() {
        connectionsDirty = true;
        validConnectionsCache = List.of();
        validConnectionsCacheTick = Long.MIN_VALUE;
    }

    public void tickAutoReturn() {
        // Machine-specific extraction belongs in a subclass.
    }

    public boolean hasAnyTickWork() {
        return providerHost.getReturnMode()
                == StablePatternProviderBlockEntity.ReturnMode.AUTO
                || !returnInventory.isEmpty();
    }

    protected final void alertGridTick() {
        gridNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    @Override
    public boolean isBusy() {
        if (providerHost.getProviderMode()
                == StablePatternProviderBlockEntity.ProviderMode.WIRELESS) {
            return false;
        }
        return super.isBusy();
    }

    @Override
    public void resetCraftingLock() {
        super.resetCraftingLock();
        clearPendingUnlockRule();
    }

    /**
     * Called by PP's PatternProviderLogic mixin before AE2 performs strict
     * unlock matching.
     */
    public boolean handleReturnedStack(GenericStack returnedStack) {
        if (getCraftingLockedReason() != LockCraftingMode.LOCK_UNTIL_RESULT) {
            clearPendingUnlockRule();
            return false;
        }
        var unlockStack = getUnlockStack();
        if (unlockStack == null) {
            resetCraftingLock();
            return true;
        }
        if (!returnedStackMatchesUnlock(unlockStack, returnedStack)) {
            return false;
        }

        long remaining = unlockStack.amount() - returnedStack.amount();
        if (remaining <= 0) {
            resetCraftingLock();
        } else {
            accessor().ae2ltpp$setUnlockStack(
                    new GenericStack(unlockStack.what(), remaining));
            saveChanges();
        }
        return true;
    }

    private boolean returnedStackMatchesUnlock(
            GenericStack unlockStack, GenericStack returnedStack) {
        if (Boolean.TRUE.equals(pendingUnlockIdOnly)) {
            Item expectedItem = null;
            if (pendingUnlockTemplate != null && !pendingUnlockTemplate.isEmpty()) {
                expectedItem = pendingUnlockTemplate.getItem();
            } else if (unlockStack.what() instanceof AEItemKey itemKey) {
                expectedItem = itemKey.getItem();
            }
            return expectedItem != null
                    && returnedStack.what() instanceof AEItemKey returnedItem
                    && returnedItem.getItem() == expectedItem;
        }
        return unlockStack.what().equals(returnedStack.what());
    }

    private void syncPendingUnlockRule(IPatternDetails pattern) {
        clearPendingUnlockRule();
        if (getCraftingLockedReason() != LockCraftingMode.LOCK_UNTIL_RESULT
                || !(pattern instanceof OverloadedPatternDetails overload)) {
            return;
        }
        int outputIndex = resolveUnlockOutputIndex(pattern);
        var actualOutputs = pattern.getOutputs();
        if (outputIndex < 0 || outputIndex >= actualOutputs.length) {
            return;
        }
        pendingUnlockIdOnly = overload.isFuzzyOutput(outputIndex);
        var key = actualOutputs[outputIndex].what();
        pendingUnlockTemplate = key instanceof AEItemKey itemKey
                ? itemKey.toStack(1)
                : null;
    }

    private static int resolveUnlockOutputIndex(IPatternDetails pattern) {
        var actualOutputs = pattern.getOutputs();
        if (actualOutputs.length == 0) {
            return -1;
        }
        var primary = pattern.getPrimaryOutput();
        for (int i = 0; i < actualOutputs.length; i++) {
            var candidate = actualOutputs[i];
            if (candidate.what().equals(primary.what())
                    && candidate.amount() == primary.amount()) {
                return i;
            }
        }
        return 0;
    }

    private void clearPendingUnlockRule() {
        pendingUnlockIdOnly = null;
        pendingUnlockTemplate = null;
    }

    @Override
    public void writeToNBT(CompoundTag tag) {
        super.writeToNBT(tag);
        if (pendingUnlockIdOnly != null) {
            tag.putString(TAG_UNLOCK_MATCH_MODE,
                    pendingUnlockIdOnly ? "ID_ONLY" : "STRICT");
        }
        if (pendingUnlockTemplate != null && !pendingUnlockTemplate.isEmpty()) {
            tag.put(TAG_UNLOCK_TEMPLATE,
                    pendingUnlockTemplate.save(new CompoundTag()));
        }
    }

    @Override
    public void readFromNBT(CompoundTag tag) {
        super.readFromNBT(tag);
        pendingUnlockIdOnly = null;
        pendingUnlockTemplate = null;
        if (tag.contains(TAG_UNLOCK_MATCH_MODE, Tag.TAG_STRING)) {
            var savedMode = tag.getString(TAG_UNLOCK_MATCH_MODE);
            if ("ID_ONLY".equals(savedMode)) {
                pendingUnlockIdOnly = true;
            } else if ("STRICT".equals(savedMode)) {
                pendingUnlockIdOnly = false;
            }
        }
        if (tag.contains(TAG_UNLOCK_TEMPLATE, Tag.TAG_COMPOUND)) {
            var template = ItemStack.of(tag.getCompound(TAG_UNLOCK_TEMPLATE));
            pendingUnlockTemplate = template.isEmpty() ? null : template;
        }
        cachedOutputFilter = null;
        outputFilterDirty = true;
        invalidateConnections();
    }

    @Override
    public void clearContent() {
        super.clearContent();
        cachedOutputFilter = null;
        outputFilterDirty = true;
        invalidateConnections();
        clearPendingUnlockRule();
    }

    private boolean hasCombinedTickWork() {
        return accessor().ae2ltpp$hasWorkToDo() || hasAnyTickWork();
    }

    private final class Ticker implements IGridTickable {
        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(
                    GRID_TICK_MIN, GRID_TICK_MAX, !hasCombinedTickWork(), true);
        }

        @Override
        public TickRateModulation tickingRequest(
                IGridNode node, int ticksSinceLastCall) {
            if (!gridNode.isActive()) {
                return TickRateModulation.SLEEP;
            }
            boolean parentDidWork = accessor().ae2ltpp$doWork();
            tickAutoReturn();
            if (hasAnyTickWork()) {
                return TickRateModulation.URGENT;
            }
            if (accessor().ae2ltpp$hasWorkToDo()) {
                return parentDidWork
                        ? TickRateModulation.URGENT
                        : TickRateModulation.SLOWER;
            }
            return TickRateModulation.SLEEP;
        }
    }
}

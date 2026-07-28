package com.moakiee.ae2lt.packaged.patternprovider;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
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
import appeng.api.storage.AEKeySlotFilter;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.filter.IAEItemFilter;

import com.moakiee.ae2lt.api.patternprovider.WirelessPatternProviderPolicy;
import com.moakiee.ae2lt.api.patternprovider.EncodedPatternPayloadValidator;
import com.moakiee.thunderbolt.api.wireless.WirelessConnectionRange;
import com.moakiee.ae2lt.packaged.mixin.PatternProviderLogicAccessor;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadPatternDetails;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;

/**
 * PP-owned pattern-provider foundation extracted from AE2LT's 1.1/main logic.
 *
 * <p>This class intentionally owns only behavior used by compatible provider
 * addons: overload-aware pattern loading, return-inventory power accounting,
 * lock-until-result matching, wireless endpoint validation and the AE2 grid
 * ticker bridge. Machine-specific dispatch remains in the addon subclass.
 * Thunderbolt supplies overload-pattern primitives but owns no packaged-provider
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
    private MatchMode pendingUnlockMatchMode;
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
        AEKeySlotFilter returnFilter = (slot, key) -> {
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
            if (pattern instanceof OverloadedProviderOnlyPatternDetails overload
                    && overload.overloadPatternDetailsView() != null) {
                var actualOutputs = pattern.getOutputs();
                var overloadOutputs = overload.overloadPatternDetailsView().outputs();
                int count = Math.min(actualOutputs.size(), overloadOutputs.size());
                for (int i = 0; i < count; i++) {
                    var key = actualOutputs.get(i).what();
                    if (overloadOutputs.get(i).matchMode() == MatchMode.ID_ONLY) {
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
        if (pendingUnlockMatchMode == MatchMode.ID_ONLY) {
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
                || !(pattern instanceof OverloadedProviderOnlyPatternDetails overload)) {
            return;
        }
        var view = overload.overloadPatternDetailsView();
        if (view == null) {
            return;
        }
        int outputIndex = resolveUnlockOutputIndex(pattern, view);
        if (outputIndex < 0 || outputIndex >= view.outputs().size()) {
            return;
        }
        var output = view.outputs().get(outputIndex);
        pendingUnlockMatchMode = output.matchMode();
        pendingUnlockTemplate = output.template();
    }

    private static int resolveUnlockOutputIndex(
            IPatternDetails pattern, OverloadPatternDetails overloadDetails) {
        var actualOutputs = pattern.getOutputs();
        var overloadOutputs = overloadDetails.outputs();
        int count = Math.min(actualOutputs.size(), overloadOutputs.size());
        if (count <= 0) {
            return -1;
        }
        var primary = pattern.getPrimaryOutput();
        for (int i = 0; i < count; i++) {
            var candidate = actualOutputs.get(i);
            if (candidate.what().equals(primary.what())
                    && candidate.amount() == primary.amount()) {
                return i;
            }
        }
        for (int i = 0; i < count; i++) {
            if (overloadOutputs.get(i).primaryOutput()) {
                return i;
            }
        }
        return 0;
    }

    private void clearPendingUnlockRule() {
        pendingUnlockMatchMode = null;
        pendingUnlockTemplate = null;
    }

    @Override
    public void writeToNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeToNBT(tag, registries);
        if (pendingUnlockMatchMode != null) {
            tag.putString(TAG_UNLOCK_MATCH_MODE, pendingUnlockMatchMode.name());
        }
        if (pendingUnlockTemplate != null && !pendingUnlockTemplate.isEmpty()) {
            tag.put(TAG_UNLOCK_TEMPLATE,
                    pendingUnlockTemplate.saveOptional(registries));
        }
    }

    @Override
    public void readFromNBT(CompoundTag tag, HolderLookup.Provider registries) {
        super.readFromNBT(tag, registries);
        pendingUnlockMatchMode = null;
        pendingUnlockTemplate = null;
        if (tag.contains(TAG_UNLOCK_MATCH_MODE, Tag.TAG_STRING)) {
            try {
                pendingUnlockMatchMode =
                        MatchMode.valueOf(tag.getString(TAG_UNLOCK_MATCH_MODE));
            } catch (IllegalArgumentException ignored) {
                pendingUnlockMatchMode = null;
            }
        }
        if (tag.contains(TAG_UNLOCK_TEMPLATE, Tag.TAG_COMPOUND)) {
            var template = ItemStack.parseOptional(
                    registries, tag.getCompound(TAG_UNLOCK_TEMPLATE));
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
                    GRID_TICK_MIN, GRID_TICK_MAX, !hasCombinedTickWork());
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

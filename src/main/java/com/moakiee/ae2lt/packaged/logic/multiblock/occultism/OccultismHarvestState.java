package com.moakiee.ae2lt.packaged.logic.multiblock.occultism;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class OccultismHarvestState {
    private static final int LEGACY_FORMAT_VERSION = 3;
    private static final int BASELINE_FORMAT_VERSION = 4;
    private static final int FORMAT_VERSION = 5;

    private final UUID dispatchId;
    private final ResourceLocation dimension;
    private final ResourceLocation recipeId;
    private final long createdTick;
    private final boolean autoHarvest;
    private final List<ItemStack> remainingOutputs;
    private final Map<UUID, Integer> preexistingEntityCounts;
    private final List<BlockPos> outputBowls;
    private final Map<BlockPos, ItemStack> bowlBaselines;

    OccultismHarvestState(UUID dispatchId, ResourceLocation dimension,
                          ResourceLocation recipeId, long createdTick,
                          List<ItemStack> remainingOutputs,
                          Map<UUID, Integer> preexistingEntityCounts,
                          List<BlockPos> outputBowls) {
        this(dispatchId, dimension, recipeId, createdTick, remainingOutputs,
                preexistingEntityCounts, outputBowls, Map.of());
    }

    OccultismHarvestState(UUID dispatchId, ResourceLocation dimension,
                          ResourceLocation recipeId, long createdTick,
                          List<ItemStack> remainingOutputs,
                          Map<UUID, Integer> preexistingEntityCounts,
                          List<BlockPos> outputBowls,
                          Map<BlockPos, ItemStack> bowlBaselines) {
        this(dispatchId, dimension, recipeId, createdTick, remainingOutputs,
                preexistingEntityCounts, outputBowls, bowlBaselines, true);
    }

    OccultismHarvestState(UUID dispatchId, ResourceLocation dimension,
                          ResourceLocation recipeId, long createdTick,
                          List<ItemStack> remainingOutputs,
                          Map<UUID, Integer> preexistingEntityCounts,
                          List<BlockPos> outputBowls,
                          Map<BlockPos, ItemStack> bowlBaselines, boolean autoHarvest) {
        this.autoHarvest = autoHarvest;
        this.dispatchId = dispatchId;
        this.dimension = dimension;
        this.recipeId = recipeId;
        this.createdTick = createdTick;
        this.remainingOutputs = autoHarvest ? copyStacks(remainingOutputs) : List.of();
        this.preexistingEntityCounts = Map.copyOf(preexistingEntityCounts);
        this.outputBowls = List.copyOf(outputBowls);
        var baselines = new HashMap<BlockPos, ItemStack>();
        bowlBaselines.forEach((pos, stack) -> {
            if (pos != null && stack != null) {
                baselines.put(pos.immutable(), stack.copy());
            }
        });
        this.bowlBaselines = Collections.unmodifiableMap(baselines);
    }

    UUID dispatchId() {
        return dispatchId;
    }

    ResourceLocation dimension() {
        return dimension;
    }

    ResourceLocation recipeId() {
        return recipeId;
    }

    long createdTick() {
        return createdTick;
    }

    List<ItemStack> remainingOutputs() {
        return copyStacks(remainingOutputs);
    }

    Map<UUID, Integer> preexistingEntityCounts() {
        return preexistingEntityCounts;
    }

    List<BlockPos> outputBowls() {
        return outputBowls;
    }

    boolean autoHarvest() {
        return autoHarvest;
    }

    boolean complete() {
        return remainingOutputs.isEmpty();
    }

    int claimableFromBowl(BlockPos pos, ItemStack current) {
        var baseline = bowlBaselines.get(pos);
        // Version 3 knew only positions. Unknown is not an empty bowl:
        // keep the paid job without manufacturing ownership during migration.
        if (baseline == null || !outputBowls.contains(pos)) {
            return 0;
        }
        int baselineCount = ItemStack.isSameItemSameTags(current, baseline)
                ? baseline.getCount() : 0;
        return claimable(current, attributableCount(current.getCount(), baselineCount));
    }

    int claimable(ItemStack stack, int available) {
        if (stack.isEmpty() || available <= 0) {
            return 0;
        }
        long needed = 0L;
        for (var expected : remainingOutputs) {
            if (ItemStack.isSameItemSameTags(stack, expected)) {
                needed = Math.min((long) Integer.MAX_VALUE, needed + expected.getCount());
            }
        }
        return (int) Math.min((long) available, needed);
    }

    OccultismHarvestState consume(ItemStack stack, int amount) {
        if (stack.isEmpty() || amount <= 0) {
            return this;
        }
        int remaining = amount;
        var updated = new ArrayList<ItemStack>(remainingOutputs.size());
        for (var expected : remainingOutputs) {
            var copy = expected.copy();
            if (remaining > 0 && ItemStack.isSameItemSameTags(stack, copy)) {
                int taken = Math.min(remaining, copy.getCount());
                copy.shrink(taken);
                remaining -= taken;
            }
            if (!copy.isEmpty()) {
                updated.add(copy);
            }
        }
        return new OccultismHarvestState(dispatchId, dimension, recipeId,
                createdTick, updated, preexistingEntityCounts, outputBowls, bowlBaselines, autoHarvest);
    }

    String encode() {
        var tag = new CompoundTag();
        tag.putInt("version", FORMAT_VERSION);
        tag.putUUID("dispatch", dispatchId);
        tag.putString("dimension", dimension.toString());
        tag.putString("recipe", recipeId.toString());
        tag.putLong("created", createdTick);
        tag.putBoolean("autoHarvest", autoHarvest);

        var outputs = new ListTag();
        for (var stack : remainingOutputs) {
            var output = new CompoundTag();
            var identity = stack.save(new CompoundTag());
            // ItemStack#save stores Count in a byte on 1.20.1. Keep the
            // identity separately from the authoritative integer amount so
            // overstacked ritual outputs survive a provider/world save.
            identity.putByte("Count", (byte) 1);
            output.put("stack", identity);
            output.putLong("amount", stack.getCount());
            outputs.add(output);
        }
        tag.put("outputs", outputs);

        var entities = new ListTag();
        preexistingEntityCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    var entity = new CompoundTag();
                    entity.putUUID("id", entry.getKey());
                    entity.putInt("count", entry.getValue());
                    entities.add(entity);
                });
        tag.put("entities", entities);
        tag.putLongArray("bowls", outputBowls.stream().mapToLong(BlockPos::asLong).toArray());

        var baselines = new ListTag();
        for (var pos : outputBowls) {
            var stack = bowlBaselines.get(pos);
            if (stack == null) {
                continue;
            }
            var baseline = new CompoundTag();
            baseline.putLong("pos", pos.asLong());
            baseline.putLong("amount", stack.getCount());
            if (!stack.isEmpty()) {
                var identity = stack.save(new CompoundTag());
                identity.putByte("Count", (byte) 1);
                baseline.put("stack", identity);
            }
            baselines.add(baseline);
        }
        tag.put("bowlBaselines", baselines);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                tag.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    static OccultismHarvestState decode(@Nullable String encoded,
                                        ResourceLocation expectedDimension) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            var text = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            var tag = TagParser.parseTag(text);
            int version = tag.getInt("version");
            if ((version != FORMAT_VERSION && version != BASELINE_FORMAT_VERSION
                    && version != LEGACY_FORMAT_VERSION) || !tag.hasUUID("dispatch")) {
                return null;
            }
            // Older jobs did not record the mode. Preserve their ownership and debt.
            if (version == FORMAT_VERSION && (!tag.contains("autoHarvest", Tag.TAG_BYTE)
                    || (tag.getByte("autoHarvest") != 0 && tag.getByte("autoHarvest") != 1))) {
                return null;
            }
            boolean autoHarvest = version != FORMAT_VERSION || tag.getBoolean("autoHarvest");
            var dimension = ResourceLocation.tryParse(tag.getString("dimension"));
            var recipe = ResourceLocation.tryParse(tag.getString("recipe"));
            if (dimension == null || recipe == null || !dimension.equals(expectedDimension)) {
                return null;
            }

            var outputs = new ArrayList<ItemStack>();
            var outputTags = tag.getList("outputs", Tag.TAG_COMPOUND);
            for (int i = 0; i < outputTags.size(); i++) {
                var output = outputTags.getCompound(i);
                if (!output.contains("stack", Tag.TAG_COMPOUND)
                        || !output.contains("amount", Tag.TAG_LONG)) {
                    return null;
                }
                long amount = output.getLong("amount");
                if (amount <= 0 || amount > Integer.MAX_VALUE) {
                    return null;
                }
                var stack = ItemStack.of(output.getCompound("stack"));
                if (stack.isEmpty()) {
                    return null;
                }
                stack.setCount((int) amount);
                outputs.add(stack);
            }

            var counts = new HashMap<UUID, Integer>();
            var entityTags = tag.getList("entities", Tag.TAG_COMPOUND);
            for (int i = 0; i < entityTags.size(); i++) {
                var entity = entityTags.getCompound(i);
                if (!entity.hasUUID("id") || entity.getInt("count") < 0) {
                    return null;
                }
                counts.put(entity.getUUID("id"), entity.getInt("count"));
            }

            var bowls = new ArrayList<BlockPos>();
            for (long packed : tag.getLongArray("bowls")) {
                bowls.add(BlockPos.of(packed));
            }
            var baselines = new HashMap<BlockPos, ItemStack>();
            if (version >= BASELINE_FORMAT_VERSION) {
                var baselineTags = tag.getList("bowlBaselines", Tag.TAG_COMPOUND);
                for (int i = 0; i < baselineTags.size(); i++) {
                    var baseline = baselineTags.getCompound(i);
                    if (!baseline.contains("pos", Tag.TAG_LONG)
                            || !baseline.contains("amount", Tag.TAG_LONG)) {
                        return null;
                    }
                    var pos = BlockPos.of(baseline.getLong("pos"));
                    long amount = baseline.getLong("amount");
                    if (!bowls.contains(pos) || baselines.containsKey(pos)
                            || amount < 0 || amount > Integer.MAX_VALUE) {
                        return null;
                    }
                    var stack = ItemStack.EMPTY;
                    if (amount > 0) {
                        stack = ItemStack.of(baseline.getCompound("stack"));
                        if (stack.isEmpty()) {
                            return null;
                        }
                        stack.setCount((int) amount);
                    } else if (baseline.contains("stack")) {
                        return null;
                    }
                    baselines.put(pos, stack);
                }
            }
            if (!autoHarvest && !outputs.isEmpty()) {
                return null;
            }
            return new OccultismHarvestState(tag.getUUID("dispatch"), dimension,
                    recipe, tag.getLong("created"), outputs, counts, bowls, baselines, autoHarvest);
        } catch (Exception ignored) {
            return null;
        }
    }

    static int attributableCount(int currentCount, int baselineCount) {
        return Math.max(0, currentCount - baselineCount);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        var copies = new ArrayList<ItemStack>(stacks.size());
        for (var stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                copies.add(stack.copy());
            }
        }
        return List.copyOf(copies);
    }
}

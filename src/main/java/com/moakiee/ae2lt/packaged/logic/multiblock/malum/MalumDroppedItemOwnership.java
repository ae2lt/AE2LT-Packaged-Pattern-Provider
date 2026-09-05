package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterPersistentScope;

final class MalumDroppedItemOwnership {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("ae2ltpp/malum-ownership");
    private static final long STATE_TTL = 20L * 60L * 60L * 6L;
    private static final java.util.Set<String> STALE_LOGGED = ConcurrentHashMap.newKeySet();

    private MalumDroppedItemOwnership() {
    }

    static Snapshot capture(ServerLevel level, AABB bounds, ItemStack expectedOutput) {
        var counts = new HashMap<UUID, Integer>();
        for (var entity : matchingEntities(level, bounds, expectedOutput)) {
            counts.put(entity.getUUID(), entity.getItem().getCount());
        }
        return new Snapshot(counts);
    }

    static void store(ServerLevel level, BlockPos mainPos, AdapterPersistentScope scope,
                      String stateKey, Snapshot snapshot, ItemStack expectedOutput,
                      ResourceLocation recipeId) {
        if (expectedOutput.isEmpty()) {
            return;
        }
        var encoded = encodeState(
                level.dimension().location(), level.getGameTime(), snapshot,
                expectedOutput, recipeId);
        scope.setState(mainPos, qualifiedKey(level, stateKey), encoded);
    }

    @Nullable
    static HarvestState load(ServerLevel level, BlockPos mainPos,
                             AdapterPersistentScope scope, String stateKey) {
        var encoded = scope.getState(mainPos, qualifiedKey(level, stateKey));
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        return decodeState(encoded, level.dimension().location());
    }

    static String encodeState(ResourceLocation dimension, long createdTick,
                              Snapshot snapshot, ItemStack expectedOutput,
                              ResourceLocation recipeId) {
        var encoded = new StringBuilder(dimension.toString())
                .append('|')
                .append(createdTick)
                .append('|')
                .append(expectedOutput.getCount())
                .append('|')
                .append(encodeStack(expectedOutput))
                .append("|recipe=")
                .append(recipeId);
        snapshot.entityCounts().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> encoded.append('|')
                        .append(entry.getKey())
                        .append('=')
                        .append(entry.getValue()));
        return encoded.toString();
    }

    @Nullable
    static HarvestState decodeState(String encoded, ResourceLocation dimension) {
        var parts = encoded.split("\\|", -1);
        if (parts.length < 4 || !dimension.toString().equals(parts[0])) {
            return null;
        }
        try {
            long createdTick = Long.parseLong(parts[1]);
            int expectedCount = Integer.parseInt(parts[2]);
            var expectedOutput = decodeStack(parts[3]);
            if (expectedCount <= 0 || expectedOutput.isEmpty()) {
                return null;
            }
            expectedOutput.setCount(expectedCount);
            ResourceLocation recipeId = null;
            var counts = new HashMap<UUID, Integer>();
            for (int i = 4; i < parts.length; i++) {
                if (isRecipeMetadata(parts[i])) {
                    if (recipeId != null) {
                        return null;
                    }
                    recipeId = parseRecipeMetadata(parts[i]);
                    if (recipeId == null) {
                        return null;
                    }
                    continue;
                }
                int separator = parts[i].lastIndexOf('=');
                if (separator <= 0 || separator == parts[i].length() - 1) {
                    return null;
                }
                var id = UUID.fromString(parts[i].substring(0, separator));
                int count = Integer.parseInt(parts[i].substring(separator + 1));
                if (count < 0) {
                    return null;
                }
                counts.put(id, count);
            }
            return new HarvestState(expectedOutput, counts, createdTick, recipeId);
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean expired(ServerLevel level, HarvestState state) {
        long age = level.getGameTime() - state.createdTick();
        return age < 0 || age > STATE_TTL;
    }

    static void logStale(ServerLevel level, BlockPos mainPos,
                         HarvestState state, String stateKey) {
        var key = qualifiedKey(level, stateKey) + "@" + mainPos.asLong()
                + "@" + state.createdTick();
        if (STALE_LOGGED.add(key)) {
            LOG.warn("Retaining stale Malum dispatch ownership instead of allowing overwrite: "
                            + "dimension={} pos={} recipe={} createdTick={} age={}",
                    level.dimension().location(), mainPos, state.recipeId(),
                    state.createdTick(), level.getGameTime() - state.createdTick());
        }
    }

    static boolean blocksDispatch(ServerLevel level, BlockPos mainPos,
                                  AdapterPersistentScope scope, String stateKey) {
        var qualifiedKey = qualifiedKey(level, stateKey);
        var encoded = scope.getState(mainPos, qualifiedKey);
        if (encoded == null || encoded.isBlank()) {
            return false;
        }
        var state = load(level, mainPos, scope, stateKey);
        if (state == null) {
            LOG.warn("Retaining malformed Malum dispatch state instead of allowing overwrite: "
                            + "dimension={} pos={} key={}",
                    level.dimension().location(), mainPos, stateKey);
            return true;
        }
        if (expired(level, state)) {
            logStale(level, mainPos, state, stateKey);
        }
        return true;
    }

    static List<GenericStack> collectNewOutputs(ServerLevel level, AABB bounds,
                                                HarvestState state) {
        var expected = state.expectedOutput();
        var key = AEItemKey.of(expected);

        var attributable = new ArrayList<AttributableEntity>();
        long attributableCount = 0;
        for (var entity : matchingEntities(level, bounds, expected)) {
            int currentCount = entity.getItem().getCount();
            int baselineCount = state.preexistingEntityCounts()
                    .getOrDefault(entity.getUUID(), 0);
            int delta = attributableCount(currentCount, baselineCount);
            if (delta > 0) {
                attributable.add(new AttributableEntity(entity, delta));
                attributableCount += delta;
            }
        }

        int expectedCount = expected.getCount();
        if (attributableCount < expectedCount) {
            return List.of();
        }

        attributable.sort(Comparator.comparing(
                entry -> state.preexistingEntityCounts().containsKey(entry.entity().getUUID())));
        int remaining = expectedCount;
        for (var entry : attributable) {
            if (remaining <= 0) {
                break;
            }
            var entity = entry.entity();
            var stack = entity.getItem();
            int taken = Math.min(remaining, entry.attributableCount());
            if (taken == stack.getCount()) {
                entity.discard();
            } else {
                var remainder = stack.copy();
                remainder.shrink(taken);
                entity.setItem(remainder);
            }
            remaining -= taken;
        }
        if (remaining != 0) {
            return List.of();
        }
        return List.of(new GenericStack(key, expectedCount));
    }

    static void clear(ServerLevel level, BlockPos mainPos,
                      AdapterPersistentScope scope, String stateKey) {
        scope.clearState(mainPos, qualifiedKey(level, stateKey));
        scope.clearState(mainPos, stateKey);
        STALE_LOGGED.removeIf(key -> key.startsWith(qualifiedKey(level, stateKey)
                + "@" + mainPos.asLong() + "@"));
    }

    static String qualifiedKey(ServerLevel level, String stateKey) {
        return stateKey + "@" + level.dimension().location();
    }

    static int attributableCount(int currentCount, int baselineCount) {
        return Math.max(0, currentCount - baselineCount);
    }

    static boolean isRecipeMetadata(String part) {
        return part.startsWith("recipe=");
    }

    @Nullable
    static ResourceLocation parseRecipeMetadata(String part) {
        if (!isRecipeMetadata(part)) {
            return null;
        }
        var value = part.substring("recipe=".length());
        return value.isBlank() ? null : ResourceLocation.tryParse(value);
    }

    private static List<ItemEntity> matchingEntities(
            ServerLevel level, AABB bounds, ItemStack expectedOutput) {
        var matches = new ArrayList<ItemEntity>();
        for (var entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (!entity.isAlive()) {
                continue;
            }
            var stack = entity.getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(stack, expectedOutput)) {
                matches.add(entity);
            }
        }
        return matches;
    }

    private static String encodeStack(ItemStack stack) {
        var tag = new CompoundTag();
        stack.save(tag);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                tag.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ItemStack decodeStack(String encoded) throws Exception {
        var text = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        return ItemStack.of(TagParser.parseTag(text));
    }

    private record AttributableEntity(ItemEntity entity, int attributableCount) {
    }

    record Snapshot(Map<UUID, Integer> entityCounts) {
        Snapshot {
            entityCounts = Map.copyOf(entityCounts);
        }
    }

    record HarvestState(
            ItemStack expectedOutput,
            Map<UUID, Integer> preexistingEntityCounts,
            long createdTick,
            @Nullable ResourceLocation recipeId) {
        HarvestState {
            expectedOutput = expectedOutput.copy();
            preexistingEntityCounts = Map.copyOf(preexistingEntityCounts);
        }
    }
}

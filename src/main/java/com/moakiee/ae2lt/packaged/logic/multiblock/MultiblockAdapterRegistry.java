package com.moakiee.ae2lt.packaged.logic.multiblock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.moakiee.ae2lt.packaged.AE2LTPackagedProvider;

public final class MultiblockAdapterRegistry {

    private static final Map<ResourceLocation, AdapterRegistration> REGISTRATIONS = new LinkedHashMap<>();
    private static final BooleanSupplier ALWAYS_ENABLED = () -> true;
    private static List<AdapterRegistration> sortedRegistrations = List.of();
    private static List<MultiblockAdapter> activeAdapters = List.of();
    private static boolean[] activeEnabledStates = new boolean[0];
    private static boolean hasDynamicEnabledSupplier;
    private static boolean cacheDirty = true;

    private MultiblockAdapterRegistry() {
    }

    /** Register during mod setup. Not thread-safe with concurrent lookup. */
    public static void register(MultiblockAdapter adapter) {
        register(new AdapterRegistration(defaultId(adapter), adapter.priority(), ALWAYS_ENABLED, adapter));
    }

    /** Register during mod setup. Not thread-safe with concurrent lookup. */
    public static void register(AdapterRegistration registration) {
        var existing = REGISTRATIONS.putIfAbsent(registration.id(), registration);
        if (existing != null) {
            throw new IllegalStateException("Duplicate multiblock adapter: " + registration.id());
        }
        if (registration.enabled() != ALWAYS_ENABLED) {
            hasDynamicEnabledSupplier = true;
        }
        cacheDirty = true;
    }

    @Nullable
    public static MultiblockAdapter find(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        for (var adapter : activeAdapters()) {
            if (adapter.recognizesMain(level, pos, be)) {
                return adapter;
            }
        }
        return null;
    }

    public static List<MultiblockAdapter> getAll() {
        return activeAdapters();
    }

    public static List<MultiblockAdapter> activeAdapters() {
        refreshSortedRegistrationsIfNeeded();
        if (!hasDynamicEnabledSupplier) {
            return activeAdapters;
        }

        var enabledStates = new boolean[sortedRegistrations.size()];
        boolean changed = enabledStates.length != activeEnabledStates.length;
        for (int i = 0; i < sortedRegistrations.size(); i++) {
            enabledStates[i] = sortedRegistrations.get(i).isEnabled();
            if (!changed && enabledStates[i] != activeEnabledStates[i]) {
                changed = true;
            }
        }
        if (!changed) {
            return activeAdapters;
        }

        var adapters = new ArrayList<MultiblockAdapter>();
        for (int i = 0; i < sortedRegistrations.size(); i++) {
            if (enabledStates[i]) {
                adapters.add(sortedRegistrations.get(i).adapter());
            }
        }
        activeEnabledStates = Arrays.copyOf(enabledStates, enabledStates.length);
        activeAdapters = Collections.unmodifiableList(adapters);
        return activeAdapters;
    }

    public static List<AdapterRegistration> registrations() {
        return Collections.unmodifiableList(List.copyOf(REGISTRATIONS.values()));
    }

    static void clearForTests() {
        REGISTRATIONS.clear();
        sortedRegistrations = List.of();
        activeAdapters = List.of();
        activeEnabledStates = new boolean[0];
        hasDynamicEnabledSupplier = false;
        cacheDirty = true;
    }

    private static void refreshSortedRegistrationsIfNeeded() {
        if (!cacheDirty) {
            return;
        }
        var sorted = new ArrayList<>(REGISTRATIONS.values());
        sorted.sort(Comparator.comparingInt(AdapterRegistration::priority));
        sortedRegistrations = List.copyOf(sorted);
        activeEnabledStates = new boolean[0];
        if (hasDynamicEnabledSupplier) {
            activeAdapters = List.of();
        } else {
            var adapters = new ArrayList<MultiblockAdapter>(sortedRegistrations.size());
            for (var registration : sortedRegistrations) {
                adapters.add(registration.adapter());
            }
            activeAdapters = Collections.unmodifiableList(adapters);
        }
        cacheDirty = false;
    }

    private static ResourceLocation defaultId(MultiblockAdapter adapter) {
        return new ResourceLocation(
                AE2LTPackagedProvider.MODID,
                toSnakeCase(adapter.getClass().getSimpleName()));
    }

    private static String toSnakeCase(String value) {
        var out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    char previous = value.charAt(i - 1);
                    boolean nextLower = i + 1 < value.length() && Character.isLowerCase(value.charAt(i + 1));
                    if (Character.isLowerCase(previous) || Character.isDigit(previous) || nextLower) {
                        out.append('_');
                    }
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}

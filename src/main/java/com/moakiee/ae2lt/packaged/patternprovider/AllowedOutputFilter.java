package com.moakiee.ae2lt.packaged.patternprovider;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import appeng.api.stacks.AEKey;

/**
 * Output filter used by provider auto-return implementations.
 *
 * <p>Strict outputs include components. ID-only outputs compare
 * {@link AEKey#dropSecondary()} while still preserving the AE key type.
 */
public final class AllowedOutputFilter {
    private final Set<AEKey> strictOutputs = new LinkedHashSet<>();
    private final Set<AEKey> idOnlyKeys = new LinkedHashSet<>();

    public void allowStrict(AEKey key) {
        strictOutputs.add(Objects.requireNonNull(key, "key"));
    }

    public void allowIdOnly(AEKey key) {
        idOnlyKeys.add(Objects.requireNonNull(key, "key").dropSecondary());
    }

    public boolean isEmpty() {
        return strictOutputs.isEmpty() && idOnlyKeys.isEmpty();
    }

    public boolean matches(AEKey key) {
        Objects.requireNonNull(key, "key");
        return strictOutputs.contains(key) || idOnlyKeys.contains(key.dropSecondary());
    }

    @Override
    public String toString() {
        return "AllowedOutputFilter[strict=" + strictOutputs + ", idOnly=" + idOnlyKeys + "]";
    }
}

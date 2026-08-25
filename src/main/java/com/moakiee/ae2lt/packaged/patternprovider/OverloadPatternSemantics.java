package com.moakiee.ae2lt.packaged.patternprovider;

import appeng.api.crafting.IPatternDetails;

import com.moakiee.thunderbolt.core.crafting.overload.OverloadedPatternDetails;

/**
 * Bridges packaged-provider behavior to Thunderbolt's host-neutral overload
 * contract without depending on AE2LT's runtime implementation classes.
 */
public final class OverloadPatternSemantics {

    private OverloadPatternSemantics() {
    }

    public static boolean isIdOnlyOutput(IPatternDetails pattern, int outputIndex) {
        return outputIndex >= 0
                && pattern instanceof OverloadedPatternDetails overload
                && overload.isFuzzyOutput(outputIndex);
    }
}

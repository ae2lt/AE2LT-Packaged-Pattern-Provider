package com.moakiee.ae2lt.packaged.logic.multiblock.aa;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;

final class AtomicReconstructorOutputMatcher {

    private AtomicReconstructorOutputMatcher() {
    }

    static <T> boolean matches(T expected, T actual, MatchMode mode,
                               BiPredicate<T, T> strictMatcher,
                               Function<T, T> idExtractor) {
        Objects.requireNonNull(strictMatcher, "strictMatcher");
        Objects.requireNonNull(idExtractor, "idExtractor");
        if (mode == MatchMode.ID_ONLY) {
            return Objects.equals(idExtractor.apply(expected), idExtractor.apply(actual));
        }
        return strictMatcher.test(expected, actual);
    }
}

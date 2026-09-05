package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

import org.jetbrains.annotations.Nullable;

/** Pure capacity/identity check for one table craft and its container remainders. */
final class AvaritiaTableRemainderPlanner {

    private AvaritiaTableRemainderPlanner() {
    }

    static <T> boolean canApply(List<Stack<T>> current,
                                List<Stack<T>> remaining,
                                BiPredicate<T, T> sameItem) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(sameItem, "sameItem");
        if (current.size() != remaining.size()) {
            return false;
        }

        for (int i = 0; i < current.size(); i++) {
            var before = current.get(i);
            var remainder = remaining.get(i);
            if (before.isEmpty()) {
                if (!remainder.isEmpty()) {
                    return false;
                }
                continue;
            }
            if (remainder.isEmpty()) {
                continue;
            }

            int afterConsume = before.count() - 1;
            if (afterConsume <= 0) {
                if (remainder.count() > remainder.maxStackSize()) {
                    return false;
                }
                continue;
            }
            if (!sameItem.test(before.key(), remainder.key())) {
                return false;
            }
            if (afterConsume + remainder.count() > before.maxStackSize()) {
                return false;
            }
        }
        return true;
    }

    record Stack<T>(@Nullable T key, int count, int maxStackSize) {
        Stack {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
            if (maxStackSize <= 0) {
                throw new IllegalArgumentException("maxStackSize must be positive");
            }
            if ((key == null) != (count == 0)) {
                throw new IllegalArgumentException("only a null key with count zero represents empty");
            }
        }

        static <T> Stack<T> empty() {
            return new Stack<>(null, 0, 1);
        }

        boolean isEmpty() {
            return key == null;
        }
    }
}

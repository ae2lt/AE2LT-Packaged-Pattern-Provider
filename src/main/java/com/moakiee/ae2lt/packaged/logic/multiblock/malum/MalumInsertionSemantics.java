package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

/**
 * Pure result semantics for the two insertion APIs exposed by Malum's
 * Lodestone-backed inventories.
 */
final class MalumInsertionSemantics {
    private MalumInsertionSemantics() {
    }

    /** Lodestone's one-argument API returns the stack it accepted. */
    static boolean acceptedStackResult(boolean returnedEmpty, int returnedCount, int requestedCount) {
        return requestedCount > 0 && !returnedEmpty && returnedCount >= requestedCount;
    }

    /** Forge remainder APIs return only the portion that was not accepted. */
    static boolean remainderResult(boolean returnedEmpty) {
        return returnedEmpty;
    }
}

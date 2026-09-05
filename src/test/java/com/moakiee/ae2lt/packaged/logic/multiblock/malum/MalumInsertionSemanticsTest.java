package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.moakiee.ae2lt.packaged.testsupport.MinecraftTestBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

import appeng.api.config.Actionable;

class MalumInsertionSemanticsTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void acceptedStackApiRequiresTheFullRequestedAmount() {
        assertTrue(MalumInsertionSemantics.acceptedStackResult(false, 4, 4));
        assertTrue(MalumInsertionSemantics.acceptedStackResult(false, 5, 4));
        assertFalse(MalumInsertionSemantics.acceptedStackResult(false, 3, 4));
        assertFalse(MalumInsertionSemantics.acceptedStackResult(true, 0, 4));
    }

    @Test
    void remainderApiSucceedsOnlyWhenNothingRemains() {
        assertTrue(MalumInsertionSemantics.remainderResult(true));
        assertFalse(MalumInsertionSemantics.remainderResult(false));
    }

    @Test
    void nonPositiveRequestsAreRejected() {
        assertFalse(MalumInsertionSemantics.acceptedStackResult(false, 1, 0));
        assertFalse(MalumInsertionSemantics.acceptedStackResult(false, 1, -1));
    }

    @Test
    void directWriteExceptionsPropagateAndDoNotFallBackToAnotherInsertionApi() {
        var handler = new ThrowingSetterHandler(false);

        assertThrows(IllegalStateException.class,
                () -> MalumReflection.insertItem(null, handler, 0, new ItemStack(Items.DIAMOND),
                        Actionable.MODULATE));
        assertEquals(1, handler.setterCalls.get());
        assertEquals(0, handler.fallbackCalls.get());
        assertEquals(1, handler.getStackInSlot(0).getCount());
    }

    @Test
    void preWriteSetterExceptionsAlsoPropagateWithoutFallback() {
        var handler = new ThrowingSetterHandler(true);

        assertThrows(IllegalStateException.class,
                () -> MalumReflection.insertItem(null, handler, 0, new ItemStack(Items.DIAMOND),
                        Actionable.MODULATE));
        assertEquals(1, handler.setterCalls.get());
        assertEquals(0, handler.fallbackCalls.get());
        assertTrue(handler.getStackInSlot(0).isEmpty());
    }

    @Test
    void validationRejectionRemainsFalseWithoutMutation() {
        var handler = new ItemStackHandler(1) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return false;
            }
        };

        assertFalse(MalumReflection.placeItemInSlot(handler, 0, new ItemStack(Items.DIAMOND)));
        assertTrue(handler.getStackInSlot(0).isEmpty());
    }

    @Test
    void successfulDirectWriteStillReturnsTrue() {
        var handler = new ItemStackHandler(1);

        assertTrue(MalumReflection.placeItemInSlot(handler, 0, new ItemStack(Items.DIAMOND)));
        assertEquals(1, handler.getStackInSlot(0).getCount());
    }

    @Test
    void confirmedDirectRejectionStillUsesLegacyReflectiveFallback() {
        var handler = new VersionFallbackHandler();

        assertTrue(MalumReflection.insertItem(null, handler, 0, new ItemStack(Items.DIAMOND),
                Actionable.MODULATE));
        assertEquals(1, handler.fallbackCalls.get());
        assertTrue(handler.getStackInSlot(0).isEmpty());
    }

    @Test
    void reflectiveFallbackPreWriteExceptionPropagates() {
        var handler = new ReflectiveFallbackHandler(true);

        assertThrows(IllegalStateException.class,
                () -> MalumReflection.insertItem(null, handler, 0, new ItemStack(Items.DIAMOND),
                        Actionable.MODULATE));
        assertEquals(1, handler.fallbackCalls.get());
        assertTrue(handler.getStackInSlot(0).isEmpty());
    }

    @Test
    void reflectiveFallbackPostWriteExceptionPropagates() {
        var handler = new ReflectiveFallbackHandler(false);

        assertThrows(IllegalStateException.class,
                () -> MalumReflection.insertItem(null, handler, 0, new ItemStack(Items.DIAMOND),
                        Actionable.MODULATE));
        assertEquals(1, handler.fallbackCalls.get());
        assertEquals(1, handler.getStackInSlot(0).getCount());
    }

    private static final class VersionFallbackHandler extends ItemStackHandler {
        private final AtomicInteger validityCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();

        private VersionFallbackHandler() {
            super(1);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return validityCalls.incrementAndGet() == 1;
        }

        @SuppressWarnings("unused")
        public ItemStack insertItem(net.minecraft.server.level.ServerLevel level, ItemStack stack) {
            fallbackCalls.incrementAndGet();
            return stack;
        }
    }

    private static final class ReflectiveFallbackHandler extends ItemStackHandler {
        private final AtomicInteger validityCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final boolean preWrite;

        private ReflectiveFallbackHandler(boolean preWrite) {
            super(1);
            this.preWrite = preWrite;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return validityCalls.incrementAndGet() == 1;
        }

        @SuppressWarnings("unused")
        public ItemStack insertItem(net.minecraft.server.level.ServerLevel level, ItemStack stack) {
            fallbackCalls.incrementAndGet();
            if (preWrite) {
                throw new IllegalStateException("fallback pre-write failure");
            }
            super.setStackInSlot(0, stack.copy());
            throw new IllegalStateException("fallback post-write failure");
        }
    }

    private static final class ThrowingSetterHandler extends ItemStackHandler {
        private final AtomicInteger setterCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final boolean preWrite;

        private ThrowingSetterHandler(boolean preWrite) {
            super(1);
            this.preWrite = preWrite;
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            setterCalls.incrementAndGet();
            if (preWrite) {
                throw new IllegalStateException("pre-write failure");
            }
            super.setStackInSlot(slot, stack);
            throw new IllegalStateException("post-write failure");
        }

        @SuppressWarnings("unused")
        public ItemStack insertItem(net.minecraft.server.level.ServerLevel level, ItemStack stack) {
            fallbackCalls.incrementAndGet();
            return ItemStack.EMPTY;
        }
    }
}

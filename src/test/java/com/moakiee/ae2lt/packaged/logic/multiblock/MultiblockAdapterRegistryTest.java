package com.moakiee.ae2lt.packaged.logic.multiblock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;
import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;

class MultiblockAdapterRegistryTest {

    @AfterEach
    void tearDown() {
        MultiblockAdapterRegistry.clearForTests();
    }

    @Test
    void shouldRejectDuplicateAdapterId() {
        MultiblockAdapterRegistry.register(newAdapter("first", 0));

        assertThrows(IllegalStateException.class,
                () -> MultiblockAdapterRegistry.register(newAdapter("second", 0)));
    }

    @Test
    void shouldReturnOnlyEnabledAdapters() throws ReflectiveOperationException {
        var enabled = newAdapter("enabled", 1);
        var disabled = newAdapter("disabled", 2);

        MultiblockAdapterRegistry.register(registration("enabled", 1, () -> true, enabled));
        MultiblockAdapterRegistry.register(registration("disabled", 2, () -> false, disabled));

        assertEquals(List.of(enabled), MultiblockAdapterRegistry.activeAdapters());
    }

    @Test
    void shouldSortAdaptersByPriorityAscending() throws ReflectiveOperationException {
        var low = newAdapter("low", 1);
        var high = newAdapter("high", 10);

        MultiblockAdapterRegistry.register(registration("low", 1, () -> true, low));
        MultiblockAdapterRegistry.register(registration("high", 10, () -> true, high));

        assertEquals(List.of(low, high), MultiblockAdapterRegistry.activeAdapters());
    }

    @Test
    void shouldKeepLegacyRegisterApi() throws ReflectiveOperationException {
        var adapter = newAdapter("legacy", 0);

        MultiblockAdapterRegistry.register(adapter);

        assertEquals(List.of(adapter), MultiblockAdapterRegistry.activeAdapters());
        var registration = MultiblockAdapterRegistry.registrations().get(0);
        var id = registration.getClass().getMethod("id").invoke(registration);
        var getNamespace = id.getClass().getMethod("getNamespace");
        assertEquals("ae2ltpp", getNamespace.invoke(id));
    }

    @Test
    void shouldKeepLegacyRegisterApiPriority() throws ReflectiveOperationException {
        var adapter = newAdapter("legacy-priority", 7);

        MultiblockAdapterRegistry.register(adapter);

        var registration = MultiblockAdapterRegistry.registrations().get(0);
        assertEquals(7, registration.getClass().getMethod("priority").invoke(registration));
    }

    @Test
    void shouldPreserveLegacyPriorityOrdering() {
        var lowest = new LowestPriorityAdapter();
        var middle = new MiddlePriorityAdapter();
        var highest = new HighestPriorityAdapter();

        MultiblockAdapterRegistry.register(highest);
        MultiblockAdapterRegistry.register(middle);
        MultiblockAdapterRegistry.register(lowest);

        assertEquals(List.of(lowest, middle, highest), MultiblockAdapterRegistry.activeAdapters());
    }

    @Test
    void shouldExposeRegistrationsInInsertionOrder() throws ReflectiveOperationException {
        MultiblockAdapterRegistry.register(registration("first", 0, () -> true, newAdapter("first", 0)));
        MultiblockAdapterRegistry.register(registration("second", 0, () -> true, newAdapter("second", 0)));

        assertEquals(
                List.of("first", "second"),
                MultiblockAdapterRegistry.registrations().stream()
                        .map(registration -> {
                            try {
                                var id = registration.getClass().getMethod("id").invoke(registration);
                                return (String) id.getClass().getMethod("getPath").invoke(id);
                            } catch (ReflectiveOperationException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .toList());
    }

    @Test
    void shouldNotRequireProductionNoopAdapter() {
        assertTrue(ReflectionSupport.findClass(
                "com.moakiee.ae2lt.packaged.logic.multiblock.NoopMultiblockAdapter").isEmpty());
    }

    @Test
    void shouldContinueFindingAdapterWhenOneAdapterDoesNotRecognizeMain() {
        var first = new RecognizingAdapter("first", 0, false);
        var second = new RecognizingAdapter("second", 10, true);

        assertDoesNotThrow(() -> {
            MultiblockAdapterRegistry.register(registration("first-recognizer", 0, () -> true, first));
            MultiblockAdapterRegistry.register(registration("second-recognizer", 10, () -> true, second));
        });

        assertEquals(second, MultiblockAdapterRegistry.find(null, null, null));
    }

    @Test
    void shouldUseLowerPriorityValueFirstWhenFindingAdapter() throws ReflectiveOperationException {
        var higherPriorityValue = new RecognizingAdapter("higher-value", 10, true);
        var lowerPriorityValue = new RecognizingAdapter("lower-value", -10, true);

        MultiblockAdapterRegistry.register(registration("higher-priority-value", 10, () -> true, higherPriorityValue));
        MultiblockAdapterRegistry.register(registration("lower-priority-value", -10, () -> true, lowerPriorityValue));

        assertEquals(lowerPriorityValue, MultiblockAdapterRegistry.find(null, null, null));
    }

    @Test
    void shouldReturnNullWhenNoAdapterRecognizesMain() throws ReflectiveOperationException {
        MultiblockAdapterRegistry.register(registration(
                "first-non-recognizer", 0, () -> true, new RecognizingAdapter("first", 0, false)));
        MultiblockAdapterRegistry.register(registration(
                "second-non-recognizer", 1, () -> true, new RecognizingAdapter("second", 1, false)));

        assertNull(MultiblockAdapterRegistry.find(null, null, null));
    }

    private static MultiblockAdapter newAdapter(String name, int priority) {
        return new TestAdapter(name, priority);
    }

    private static AdapterRegistration registration(
            String path,
            int priority,
            BooleanSupplier enabled,
            MultiblockAdapter adapter) throws ReflectiveOperationException {
        var resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation");
        var fromNamespaceAndPath = resourceLocationClass.getMethod("fromNamespaceAndPath", String.class, String.class);
        var id = fromNamespaceAndPath.invoke(null, "ae2ltpp", path);
        var of = AdapterRegistration.class.getMethod(
                "of",
                resourceLocationClass,
                int.class,
                BooleanSupplier.class,
                MultiblockAdapter.class);
        return (AdapterRegistration) of.invoke(null, id, priority, enabled, adapter);
    }

    private static class TestAdapter implements MultiblockAdapter {
        private final String name;
        private final int priority;

        private TestAdapter(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
            return false;
        }

        @Override
        public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
            return null;
        }

        @Override
        public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
            return false;
        }

        @Override
        public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                            IPatternDetails pattern, KeyCounter[] inputs,
                                            Object handle, IActionSource source) {
            return null;
        }

        @Override
        public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos, AllowedOutputFilter filter,
                                                 IActionSource source) {
            return List.of();
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class LowestPriorityAdapter extends TestAdapter {
        private LowestPriorityAdapter() {
            super("lowest", 1);
        }
    }

    private static final class MiddlePriorityAdapter extends TestAdapter {
        private MiddlePriorityAdapter() {
            super("middle", 5);
        }
    }

    private static final class HighestPriorityAdapter extends TestAdapter {
        private HighestPriorityAdapter() {
            super("highest", 10);
        }
    }

    private static final class RecognizingAdapter extends TestAdapter {
        private final boolean recognizes;

        private RecognizingAdapter(String name, int priority, boolean recognizes) {
            super(name, priority);
            this.recognizes = recognizes;
        }

        @Override
        public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
            return recognizes;
        }
    }
}

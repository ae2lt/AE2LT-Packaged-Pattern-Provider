package com.moakiee.ae2lt.packaged.logic.multiblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReflectionSupportTest {

    @Test
    void shouldReturnEmptyWhenClassMissing() {
        assertTrue(ReflectionSupport.findClass("missing.fake.ClassName").isEmpty());
    }

    @Test
    void shouldFindExistingClass() {
        var found = ReflectionSupport.findClass("java.lang.String");

        assertTrue(found.isPresent());
        assertEquals(String.class, found.orElseThrow());
    }

    @Test
    void shouldFindAndInvokeExistingMethod() {
        var method = ReflectionSupport.findMethod(String.class, "substring", int.class);
        var result = ReflectionSupport.invoke(method.orElseThrow(), "value", 1);

        assertTrue(result.isPresent());
        assertEquals("alue", result.orElseThrow());
    }

    @Test
    void shouldReturnEmptyForMissingOptionalModClass() {
        assertTrue(ReflectionSupport.findClass("missing.fake.optional.ModClass").isEmpty());
    }

    @Test
    void shouldReturnEmptyForMissingMethod() {
        var result = ReflectionSupport.findMethod(String.class, "missingMethodName");

        assertTrue(result.isEmpty());
    }

    @Test
    void shouldReturnEmptyWhenInvocationFails() {
        var method = ReflectionSupport.findMethod(String.class, "substring", int.class, int.class);
        var result = ReflectionSupport.invoke(method.orElseThrow(), "value", 4, 1);

        assertTrue(result.isEmpty());
    }

    @Test
    void mutationInvocationPropagatesUnderlyingRuntimeException() {
        var method = ReflectionSupport.findMethod(FaultyMutator.class, "mutate").orElseThrow();

        var error = assertThrows(IllegalStateException.class,
                () -> ReflectionSupport.invokeMutation(method, new FaultyMutator()));
        assertEquals("mutation failed", error.getMessage());
    }

    @Test
    void mutationInvocationRejectsMissingHandle() {
        assertThrows(IllegalStateException.class,
                () -> ReflectionSupport.invokeMutation(null, new FaultyMutator()));
    }

    private static final class FaultyMutator {
        @SuppressWarnings("unused")
        public void mutate() {
            throw new IllegalStateException("mutation failed");
        }
    }
}

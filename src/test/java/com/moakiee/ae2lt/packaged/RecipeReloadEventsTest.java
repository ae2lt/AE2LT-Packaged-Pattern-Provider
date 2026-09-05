package com.moakiee.ae2lt.packaged;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.minecraft.server.packs.resources.PreparableReloadListener;

class RecipeReloadEventsTest {
    @Test
    void waitsForPreparationBarrierBeforeInvalidatingBindings() {
        var barrierCalled = new AtomicBoolean();
        var barrierRelease = new CompletableFuture<Void>();
        var invalidations = new AtomicInteger();
        Queue<Runnable> gameTasks = new ArrayDeque<>();
        var listener = RecipeReloadEvents.createRecipeReloadListener(
                invalidations::incrementAndGet);

        PreparableReloadListener.PreparationBarrier barrier =
                new PreparableReloadListener.PreparationBarrier() {
                    @Override
                    public <T> CompletableFuture<T> wait(T preparedObject) {
                        barrierCalled.set(true);
                        return barrierRelease.thenApply(ignored -> preparedObject);
                    }
                };

        var reload = listener.reload(barrier, null, null, null,
                Runnable::run, gameTasks::add);

        assertTrue(barrierCalled.get());
        assertFalse(reload.isDone());
        assertEquals(0, invalidations.get());
        assertTrue(gameTasks.isEmpty());

        barrierRelease.complete(null);

        assertFalse(reload.isDone());
        assertEquals(0, invalidations.get());
        var applyTask = gameTasks.poll();
        assertNotNull(applyTask);
        applyTask.run();

        assertTrue(reload.isDone());
        assertEquals(1, invalidations.get());
        assertTrue(gameTasks.isEmpty());
    }
}

package com.moakiee.ae2lt.packaged;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.moakiee.ae2lt.packaged.logic.multiblock.binding.PatternBindingTable;

/** Invalidates opaque adapter recipe handles when server recipes reload. */
@Mod.EventBusSubscriber(modid = AE2LTPackagedProvider.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecipeReloadEvents {
    private RecipeReloadEvents() {
    }

    @SubscribeEvent
    public static void addRecipeReloadListener(AddReloadListenerEvent event) {
        event.addListener(createRecipeReloadListener(
                PatternBindingTable::invalidateAllForRecipeReload));
    }

    static PreparableReloadListener createRecipeReloadListener(Runnable invalidateBindings) {
        return (barrier, resources, preparationsProfiler, reloadProfiler,
                backgroundExecutor, gameExecutor) -> barrier.wait(null)
                        .thenRunAsync(invalidateBindings, gameExecutor);
    }
}

package com.moakiee.ae2lt.packaged.logic.multiblock;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;

import net.minecraftforge.registries.ForgeRegistries;

/**
 * Resolves a modded {@link RecipeType} by registry id.
 *
 * <p>On Forge 1.20.1 a modded recipe type can live in either of two
 * <b>separate</b> registries, depending on how its author registered it:
 * <ul>
 *   <li>{@code ForgeRegistries.RECIPE_TYPES} &mdash; the documented path
 *       ({@code DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, ...)}),
 *       used by e.g. Mystical Agriculture, Extended Crafting, Malum;</li>
 *   <li>vanilla {@code BuiltInRegistries.RECIPE_TYPE} &mdash; direct
 *       {@code Registry.register(BuiltInRegistries.RECIPE_TYPE, ...)},
 *       used by e.g. Occultism.</li>
 * </ul>
 * The two registries are not bridged, so looking a modded id up in only
 * one of them silently returns empty for mods registered on the other
 * side &mdash; the adapter then finds no recipes and the machine appears
 * permanently unusable. This helper tries Forge first, then vanilla, so
 * both registration styles resolve.
 */
public final class AdapterRecipeTypes {

    private AdapterRecipeTypes() {
    }

    @Nullable
    public static RecipeType<?> find(ResourceLocation id) {
        var forgeType = ForgeRegistries.RECIPE_TYPES.getValue(id);
        if (forgeType != null) {
            return forgeType;
        }
        return BuiltInRegistries.RECIPE_TYPE.getOptional(id).orElse(null);
    }
}

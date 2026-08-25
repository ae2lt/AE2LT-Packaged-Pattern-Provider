package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import net.minecraft.world.item.crafting.Ingredient;

/**
 * An ingredient paired with a required count, as read off a Malum recipe.
 *
 * <p>NeoForge 1.21 ships {@code SizedIngredient} in its common crafting API and
 * Malum's 1.21 builds expose that type directly. Forge 1.20.1 has no
 * equivalent, and Malum's 1.20.1 builds carry Lodestone's own
 * ingredient-with-count class instead. This record is the addon-side view that
 * {@link MalumReflection} fills in reflectively, which keeps the matcher code
 * independent of whichever class the target mod actually uses.
 */
record SizedIngredientView(Ingredient ingredient, int count) {
}

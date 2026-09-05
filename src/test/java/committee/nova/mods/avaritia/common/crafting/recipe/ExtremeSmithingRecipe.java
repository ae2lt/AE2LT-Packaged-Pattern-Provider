package committee.nova.mods.avaritia.common.crafting.recipe;

import net.minecraft.world.item.crafting.Ingredient;

/** Test-only reflection fixture for the optional Re-Avaritia recipe class. */
public final class ExtremeSmithingRecipe {
    public final Ingredient template;
    public final Ingredient base;
    public final Ingredient additions;

    public ExtremeSmithingRecipe(Ingredient template, Ingredient base, Ingredient additions) {
        this.template = template;
        this.base = base;
        this.additions = additions;
    }
}

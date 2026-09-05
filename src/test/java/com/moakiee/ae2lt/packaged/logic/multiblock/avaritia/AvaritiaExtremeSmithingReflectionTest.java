package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.moakiee.ae2lt.packaged.testsupport.MinecraftTestBootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import committee.nova.mods.avaritia.common.crafting.recipe.ExtremeSmithingRecipe;

class AvaritiaExtremeSmithingReflectionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void everyStrictAccessorMatchesOnItsFirstCallAfterColdLookup() throws ReflectiveOperationException {
        assertColdAccessors(new String[] {"matchesTemplate", "matchesBase", "matchesAddition"}, false);
    }

    @Test
    void everyItemIdAccessorMatchesOnItsFirstCallAfterColdLookup() throws ReflectiveOperationException {
        assertColdAccessors(new String[] {
                "templateAcceptsItemId", "baseAcceptsItemId", "additionAcceptsItemId"}, true);
    }

    private static void assertColdAccessors(String[] methods, boolean itemId) throws ReflectiveOperationException {
        var reflection = Class.forName(AvaritiaExtremeSmithingAdapter.class.getName()
                + "$AvaritiaSmithingReflection");
        var saved = new LinkedHashMap<Field, Object>();
        for (var name : new String[] {"lookupDone", "templateField", "baseField", "additionsField"}) {
            var field = reflection.getDeclaredField(name);
            field.setAccessible(true);
            saved.put(field, field.get(null));
        }
        var items = new Item[] {Items.PAPER, Items.IRON_INGOT, Items.DIAMOND};
        var recipe = new ExtremeSmithingRecipe(
                Ingredient.of(items[0]), Ingredient.of(items[1]), Ingredient.of(items[2]));
        try {
            for (int i = 0; i < methods.length; i++) {
                for (var field : saved.keySet()) {
                    field.set(null, field.getType() == boolean.class ? false : null);
                }
                var method = reflection.getDeclaredMethod(methods[i], Object.class,
                        itemId ? Item.class : ItemStack.class);
                method.setAccessible(true);
                Object matching = itemId ? items[i] : new ItemStack(items[i]);
                Object wrong = itemId ? Items.COBBLESTONE : new ItemStack(Items.COBBLESTONE);
                assertTrue((boolean) method.invoke(null, recipe, matching), methods[i]);
                assertFalse((boolean) method.invoke(null, recipe, wrong), methods[i]);
                assertFalse((boolean) method.invoke(null, new Object(), matching), methods[i]);
                assertTrue((boolean) method.invoke(null, recipe, matching), methods[i] + " warm lookup");
            }
        } finally {
            for (var entry : saved.entrySet()) {
                entry.getKey().set(null, entry.getValue());
            }
        }
    }
}

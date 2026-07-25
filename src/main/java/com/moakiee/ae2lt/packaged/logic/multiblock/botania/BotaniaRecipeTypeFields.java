package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

final class BotaniaRecipeTypeFields {

    private BotaniaRecipeTypeFields() {
    }

    static Object staticFieldValue(Class<?> owner, String... fieldNames)
            throws ReflectiveOperationException {
        for (var fieldName : fieldNames) {
            try {
                return owner.getField(fieldName).get(null);
            } catch (NoSuchFieldException ignored) {
                // Try the next alias. Botania snapshots may rename public recipe type fields.
            }
        }
        throw new NoSuchFieldException(
                owner.getName() + " has none of the public fields: " + String.join(", ", fieldNames));
    }
}

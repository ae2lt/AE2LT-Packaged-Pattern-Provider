---
navigation:
  parent: index.md
  title: Ritual
  icon: occultism_ritual_packaged_core
  position: 160
item_ids:
- ae2ltpp:occultism_ritual_packaged_core
---

# Occultism Ritual

**Packaged Core:** <ItemLink id="ae2ltpp:occultism_ritual_packaged_core" />
**Mod:** Occultism

Automatically performs Occultism rituals. Place the provider against the **Golden Ritual Bowl** used to start the ritual. The provider automatically reads the recipe and then lets the ritual proceed normally.

## ⚠ Ritual setup and pattern encoding

* You must have the full ritual ready: the correct **pentacle drawn** and the **sacrificial bowls** placed
  around the Golden Ritual Bowl, exactly as the ritual requires.
* Encode patterns with no additional requirements in the normal way. For rituals that require a sacrifice, add one spawn egg; for rituals that require an item to be used, add that item. The provider consumes these inputs to complete the corresponding steps.
* The Sacrificial Bowls must be empty before crafting starts. The ritual still takes its normal amount of time and displays its effects.

## Output extraction

When the ritual finishes, output extraction only checks the vertical column above the **Golden Ritual
Bowl**. It looks 1, 2, then 3 blocks above the bowl for an upside-down **Sacrificial Bowl**, and
pulls the first allowed stack from that bowl's item slot.

The provider does not extract from the Golden Ritual Bowl itself or from the surrounding Sacrificial Bowls
used for ritual ingredients.

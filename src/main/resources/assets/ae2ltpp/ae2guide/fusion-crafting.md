---
navigation:
  parent: index.md
  title: Fusion Crafting
  icon: de_fusion_packaged_core
  position: 50
item_ids:
- ae2ltpp:de_fusion_packaged_core
---

# Fusion Crafting

**Packaged Core:** <ItemLink id="ae2ltpp:de_fusion_packaged_core" />
**Mod:** Draconic Evolution

Automates Fusion Crafting. A regular Packaged Pattern Provider must touch the **Fusion Crafting Core**;
a Wireless Packaged Pattern Provider does not need to touch it and only needs its wireless connection
target set to the core. The provider finds the surrounding **Fusion Crafting Injectors** automatically,
puts the catalyst into the core and one ingredient into each injector, starts the craft, and pulls the
result back when it finishes.

## Requirements

* Build the multiblock normally: a Crafting Core with enough **Injectors** around it, charged with energy.
* When using a **regular Packaged Pattern Provider**, it must touch the Crafting Core but **must not be placed
  between the core and an injector**. It blocks the injector's direct path to the core, preventing that injector
  from being detected. Attach it to a side of the core that does not have an injector facing it.
* When using a **Wireless Packaged Pattern Provider**, set its wireless connection target directly to the
  Crafting Core. It does not need to touch the core and therefore does not block any injector paths.
* The injectors must meet the recipe's **tier** (Basic / Wyvern / Draconic / Chaotic). Lower-tier injectors are
  skipped, so make sure you have enough injectors of the required tier.
* Before starting, the core catalyst slot and the injectors used for this craft must be empty.

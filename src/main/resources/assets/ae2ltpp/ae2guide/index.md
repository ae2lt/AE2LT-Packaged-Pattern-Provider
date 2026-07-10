---
navigation:
  title: AE2LT Packaged Pattern Provider
  position: 67
item_ids:
- ae2ltpp:packaged_pattern_provider
- ae2ltpp:wireless_packaged_pattern_provider
---

# AE2LT Packaged Pattern Provider

`Congratulations on making it this far! This is the pinnacle of the lightning power you have mastered!`

The **Packaged Pattern Provider** (and its wireless version) lets AE2 autocrafting drive crafting machines from **other mods**, greatly simplifying automation setups that would otherwise be complex and inefficient.
~~`Although it seems you still need a thorough understanding of these crafting structures...`~~
Insert the corresponding **Packaged Core** into the provider to select the type of machine it will control.

## General usage

1. Place a (Wireless) Packaged Pattern Provider **touching the target machine's main block / core**.
2. Put the matching Packaged Core into the provider's **Packaged Core slot**. Each provider can serve only one type of machine at a time.
3. As with a normal Pattern Provider, encode the recipe as a **processing pattern** and insert it.
4. When AE2 requests the output, the provider reads the machine recipe (including custom recipes), sends the ingredients to the machine, and automatically returns the completed result to your ME network.

> The Packaged Pattern Provider behaves much like a simulated player. Multiblock machines must still be built according to **their own structure**, with the provider touching the **main block / core**. You must also supply the machine with enough power, mana, Source, or other required resources.
>
> If a craft cannot be dispatched correctly, check that the machine structure is complete, the machine contains no leftover items, and the correct Packaged Core is installed.
>
> A very small number of recipes, such as Occultism Spirit Fire crafting, cannot use the Wireless Packaged Pattern Provider. If a recipe does not work, try a regular Packaged Pattern Provider.
>
> If materials are not returned correctly, check that automatic return is enabled on the provider.
>
> Machines marked **⚠** have additional requirements—read their individual pages before using them.

## Supported machines

### Actually Additions
* [Atomic Reconstructor](atomic-reconstructor.md)
* [Empowerer](empowerer.md)

### Ars Nouveau
* [Enchanting Apparatus](enchanting-apparatus.md)
* [Imbuement Chamber](imbuement-chamber.md) ⚠

### Draconic Evolution
* [Fusion Crafting](fusion-crafting.md)

### Extended Crafting
* [Crafting Tables — Basic / Advanced / Elite / Ultimate](ec-tables.md)
* [Ender Crafter](ender-crafter.md)
* [Flux Crafter](flux-crafter.md)
* [Combination Crafter](combination-crafter.md)

### Mystical Agriculture
* [Infusion Altar](infusion-altar.md)
* [Awakening Altar](awakening-altar.md) ⚠

### Malum
* [Spirit Focusing — Spirit Crucible](spirit-focusing.md) ⚠
* [Spirit Infusion — Spirit Altar](spirit-infusion.md)

### Occultism
* [Ritual](occultism-ritual.md) ⚠
* [Spirit Fire](spirit-fire.md)

### Mekanism: More Machines
* [Large Machines — one packaged core for all](mekanism-more-machines.md)

### Botania
* [Petal Apothecary](petal-apothecary.md) ⚠
* [Mana Pool](mana-pool.md) ⚠
* [Alfheim Portal](alfheim-portal.md) ⚠
* [Terra Plate](terra-plate.md)
* [Runic Altar](runic-altar.md) ⚠

### Re-Avaritia
* [Crafting Tables — Sculk / Nether / End / Extreme](avaritia-tables.md)
* [Extreme Smithing Table](avaritia-extreme-smithing.md)

---
navigation:
  parent: index.md
  title: Imbuement Chamber
  icon: ars_imbuement_packaged_core
  position: 40
item_ids:
- ae2ltpp:ars_imbuement_packaged_core
---

# Imbuement Chamber

**Packaged Core:** <ItemLink id="ae2ltpp:ars_imbuement_packaged_core" />
**Mod:** Ars Nouveau

Automatically performs Imbuement Chamber recipes. Place the provider against the Chamber, then place **enough** Arcane Pedestals and/or Arcane Platforms anywhere within the one-block-radius cube around it.
The core ingredient goes into the Chamber, catalysts go on the pedestals, and the result is retrieved when imbuing finishes.

## ⚠ Catalysts are recycled

Ars does **not** consume the pedestal items for an Imbuement recipe — it only checks that they are
present. So this provider treats them as **reusable catalysts**: it places them on the pedestals for
the craft, then **pulls them back into your ME network together with the finished product**.

You still need the catalyst items in stock so they can be placed, but they are not lost. You must therefore include these catalysts as **byproducts** in the encoded outputs of the pattern.

Before starting, the Chamber and every Arcane Pedestal within 1 block must be empty; any occupied pedestal in that range blocks the craft.
`Like a clam, a delicate imbuement ritual cannot tolerate even a grain of sand.`

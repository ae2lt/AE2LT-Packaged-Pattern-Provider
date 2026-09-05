---
navigation:
  parent: index.md
  title: Spirit Focusing
  icon: malum_spirit_focusing_packaged_core
  position: 140
item_ids:
- ae2ltpp:malum_spirit_focusing_packaged_core
---

# Spirit Focusing (Spirit Crucible)

**Packaged Core:** <ItemLink id="ae2ltpp:malum_spirit_focusing_packaged_core" />
**Mod:** Malum

Automatically performs Spirit Focusing in the Spirit Crucible.
Place the provider against the **Spirit Crucible** and encode the corresponding **processing pattern**.
For each craft, the provider performs Spirit Focusing automatically, then retrieves the result and the catalyst when focusing finishes.
The provider starts only when the Crucible is idle and its slots are empty.

## ⚠ Catalyst, ingredients, and patterns

* When encoding the pattern, include the recipe **catalyst** and every **spirit shard** as pattern inputs. The only pattern output should be the focusing result.
* The catalyst is reusable rather than consumed. The provider returns it to your ME network together with the result when its post-craft identity can be verified; an unrecognized cracked or replaced variant is conservatively left in the Crucible.

---
navigation:
  parent: index.md
  title: 通量合成器
  icon: ec_flux_crafter_packaged_core
  position: 80
item_ids:
- ae2ltpp:ec_flux_crafter_packaged_core
---

# 通量合成器

**所需封包核心：** <ItemLink id="ae2ltpp:ec_flux_crafter_packaged_core" />
**来源模组：** Extended Crafting（扩展合成）

自动完成通量合成器，把供应器贴着通量合成器放置，编写相应**处理样板**，供应器会取回合成产物。

## 要求

* 按通量合成器本身的规则放置**通量发电机**并提供 FE。
* 结构与末影合成器相同的 3×3，但进度由 **FE 电力**驱动而非时间。
* 合成进度随发电机供电推进；电力不足时只是变慢，等待期间不会丢失材料。
* 开始前 3×3 合成格和输出槽必须为空。

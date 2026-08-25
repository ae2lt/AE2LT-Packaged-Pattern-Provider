package com.moakiee.ae2lt.packaged.item;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import com.moakiee.ae2lt.packaged.client.PackagedCoreClientExtensions;

/**
 * Packaged core item for the packaged pattern provider.
 *
 * <p>Each {@code MultiblockAdapterItem} declares one or more {@link #primaryId
 * primary adapter ids} it unlocks (e.g. {@code ae2ltpp:extendedcrafting/table_tier4_packaged_core}).
 * Higher-tier items also cover lower-tier ids in the same family (an
 * ultimate-table packaged core also unlocks elite/advanced/basic). The packaged
 * provider holds exactly one of these in its single adapter slot, so each
 * provider is locked to one machine family at a time.
 *
 * <p>Why a single class instead of 12: the only thing that differs between
 * tiers/families is the metadata (primary id + covered set + tooltip), so we
 * keep one immutable {@code Item} class and instantiate it 12 times with
 * different constructor args. That mirrors how AE2 builds its cell/upgrade
 * items.
 */
public class MultiblockAdapterItem extends Item {

    private final ResourceLocation primaryId;
    private final Set<ResourceLocation> coveredIds;

    /**
     * @param props      vanilla Item properties (use {@code stacksTo(1)} since
     *                   only one slot exists on the provider)
     * @param primaryId  the canonical id this item exposes (matches
     *                   {@code MultiblockAdapter.requiredAdapterId})
     * @param coveredIds full set of ids unlocked by holding this item;
     *                   must include {@code primaryId}. For non-tiered adapters
     *                   pass {@code Set.of(primaryId)}.
     */
    public MultiblockAdapterItem(Properties props,
                                 ResourceLocation primaryId,
                                 Set<ResourceLocation> coveredIds) {
        super(props);
        this.primaryId = primaryId;
        this.coveredIds = Set.copyOf(coveredIds);
        if (!this.coveredIds.contains(primaryId)) {
            throw new IllegalArgumentException(
                    "coveredIds must contain primaryId: " + primaryId + " not in " + coveredIds);
        }
    }

    public ResourceLocation primaryAdapterId() {
        return primaryId;
    }

    public Set<ResourceLocation> coveredAdapterIds() {
        return coveredIds;
    }

    /** @return true if this item unlocks the given required adapter id. */
    public boolean covers(@Nullable ResourceLocation requiredId) {
        return requiredId != null && coveredIds.contains(requiredId);
    }

    /** Static helper: check an ItemStack against a required id. */
    public static boolean stackCovers(ItemStack stack, @Nullable ResourceLocation requiredId) {
        return requiredId != null
                && stack.getItem() instanceof MultiblockAdapterItem item
                && item.covers(requiredId);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        if (coveredIds.size() > 1) {
            tooltip.add(Component.translatable("tooltip.ae2ltpp.packaged_core.covers",
                    Component.literal(String.valueOf(coveredIds.size())).withStyle(ChatFormatting.GOLD))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        PackagedCoreClientExtensions.accept(consumer);
    }
}

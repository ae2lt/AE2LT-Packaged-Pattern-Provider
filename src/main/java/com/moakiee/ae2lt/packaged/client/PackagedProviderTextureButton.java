package com.moakiee.ae2lt.packaged.client;

import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.ITooltip;

/**
 * Local copy of the toolbar rendering used by the pre-refactor provider UI.
 * The textures remain AE2LT resources, but the addon does not depend on an
 * AE2LT implementation class to render them.
 */
final class PackagedProviderTextureButton extends Button implements ITooltip {
    private static final ResourceLocation AUTO_RETURN_OFF = texture("auto_input_off");
    private static final ResourceLocation AUTO_RETURN_ON = texture("auto_input_on");
    private static final ResourceLocation FREQUENCY_BIND = texture("frequency_select");

    private final ResourceLocation offTexture;
    private final ResourceLocation onTexture;
    private final Runnable listener;
    private boolean state;
    private List<Component> tooltipOff = List.of();
    private List<Component> tooltipOn = List.of();

    private PackagedProviderTextureButton(
            ResourceLocation offTexture,
            ResourceLocation onTexture,
            Runnable listener) {
        super(0, 0, 16, 16, Component.empty(), ignored -> listener.run(), DEFAULT_NARRATION);
        this.offTexture = offTexture;
        this.onTexture = onTexture;
        this.listener = listener;
    }

    static PackagedProviderTextureButton autoReturn(Runnable listener) {
        return new PackagedProviderTextureButton(
                AUTO_RETURN_OFF, AUTO_RETURN_ON, listener);
    }

    static PackagedProviderTextureButton frequencyBinding(Runnable listener) {
        return new PackagedProviderTextureButton(
                FREQUENCY_BIND, FREQUENCY_BIND, listener);
    }

    void setState(boolean state) {
        this.state = state;
    }

    void setTooltipOff(List<Component> tooltip) {
        this.tooltipOff = List.copyOf(tooltip);
    }

    void setTooltipOn(List<Component> tooltip) {
        this.tooltipOn = List.copyOf(tooltip);
    }

    @Override
    public void onPress() {
        listener.run();
    }

    @Override
    protected void renderWidget(
            GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        var yOffset = isHovered() ? 1 : 0;
        var background = isHovered()
                ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                : isFocused()
                        ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS
                        : Icon.TOOLBAR_BUTTON_BACKGROUND;
        background.getBlitter()
                .dest(getX() - 1, getY() + yOffset, 18, 20)
                .zOffset(2)
                .blit(guiGraphics);

        var texture = state ? onTexture : offTexture;
        var blitter = Blitter.texture(texture, 16, 16).src(0, 0, 16, 16);
        if (!active) {
            blitter.opacity(0.5f);
        }
        blitter.dest(getX(), getY() + 1 + yOffset)
                .zOffset(3)
                .blit(guiGraphics);
    }

    @Override
    public List<Component> getTooltipMessage() {
        return state ? tooltipOn : tooltipOff;
    }

    @Override
    public Rect2i getTooltipArea() {
        return new Rect2i(getX(), getY(), 16, 16);
    }

    @Override
    public boolean isTooltipAreaVisible() {
        return visible && !getTooltipMessage().isEmpty();
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(
                "ae2lt", "textures/gui/buttons/" + name + ".png");
    }
}

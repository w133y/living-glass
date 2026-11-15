package com.w1z4r_d.residuum.compat;

import com.w1z4r_d.residuum.gui.Painter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A widget of the settings screen that draws itself through a Painter, so the screen's own widgets are the same code
 * on every Minecraft version; clicks, dragging, keys and focus are the game's own (mc1219).
 */
public abstract class PaintedWidget extends AbstractWidget {
    protected PaintedWidget(int x, int y, int width, int height, Component message) {
        super(x, y, width, height, message);
    }

    /** Draws the widget, the mouse at mouseX, mouseY. */
    protected abstract void paint(Painter painter, int mouseX, int mouseY);

    @Override
    protected void renderWidget(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        paint(painter(g), mouseX, mouseY);
    }

    /** Only a widget that does something takes the mouse, so a backdrop never swallows clicks meant for what is on it. */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return this.active && super.isMouseOver(mouseX, mouseY);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    static Painter painter(net.minecraft.client.gui.GuiGraphics g) {
        return new Painter() {
            @Override
            public void blit(String namespace, String path, int x, int y, int w, int h, float u, float v, int uw, int vh,
                             int texW, int texH, int argb) {
                g.blit(RenderPipelines.GUI_TEXTURED, ResourceLocation.fromNamespaceAndPath(namespace, path), x, y, u, v, w, h, uw, vh,
                        texW, texH, argb);
            }

            @Override
            public void fill(int x0, int y0, int x1, int y1, int argb) {
                g.fill(x0, y0, x1, y1, argb);
            }

            @Override
            public void text(Component text, int x, int y, int argb, boolean shadow) {
                g.drawString(Minecraft.getInstance().font, text, x, y, argb, shadow);
            }

            @Override
            public void scissor(int x0, int y0, int x1, int y1) {
                g.enableScissor(x0, y0, x1, y1);
            }

            @Override
            public void endScissor() {
                g.disableScissor();
            }

            @Override
            public void rotate(float x, float y, float radians) {
                g.pose().pushMatrix();
                g.pose().translate(x, y);
                g.pose().rotate(radians);
                g.pose().translate(-x, -y);
            }

            @Override
            public void pop() {
                g.pose().popMatrix();
            }
        };
    }
}

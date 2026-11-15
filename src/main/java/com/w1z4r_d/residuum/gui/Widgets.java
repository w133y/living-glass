package com.w1z4r_d.residuum.gui;

import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.Settings;
import com.w1z4r_d.residuum.compat.PaintedWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** The widgets of the settings screen and the colours and small drawing helpers they share. */
final class Widgets {
    static final int TEXT = 0xFFF1F4F8, SUB = 0xFF97A1AE, DIM = 0xFF5F6874, ACCENT = 0xFF55B4FF;
    static final int SHADE = 0xC80E1116, PANEL = 0xF0161A21, CARD = 0xFF1E242D, HOVER = 0xFF283040, LINE = 0xFF2C3440,
            TRACK = 0xFF3A4350;
    /** The defaults, to reset to and to tell which settings were changed. */
    static final ResiduumConfig DEFAULTS = new ResiduumConfig();

    private Widgets() {
    }

    static Font font() {
        return Minecraft.getInstance().font;
    }

    /** A rectangle with its corner pixels cut off, so it reads as rounded. */
    static void box(Painter p, int x0, int y0, int x1, int y1, int argb) {
        p.fill(x0 + 1, y0, x1 - 1, y1, argb);
        p.fill(x0, y0 + 1, x0 + 1, y1 - 1, argb);
        p.fill(x1 - 1, y0 + 1, x1, y1 - 1, argb);
    }

    /** s cut to fit width, with an ellipsis where it was cut. */
    static String fit(String s, int width) {
        Font font = font();
        if (font.width(s) <= width) {
            return s;
        }
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end) + "…") > width) {
            end--;
        }
        return s.substring(0, end).stripTrailing() + "…";
    }

    /** s broken into lines no wider than width, at spaces. */
    static List<String> wrap(String s, int width) {
        Font font = font();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (font.width(next) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    static int mix(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = a >>> shift & 0xFF, cb = b >>> shift & 0xFF;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    static boolean confirms(KeyEvent key) {
        return key.isSelection() || key.isConfirmation();
    }

    /** Something that eases toward a target each frame, a share of the way per second. */
    static final class Ease {
        float value;
        private long last = System.nanoTime();

        Ease(float value) {
            this.value = value;
        }

        float to(float target, float rate) {
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - last) / 1.0e9f);
            last = now;
            value += (target - value) * Math.min(1, dt * rate);
            if (Math.abs(target - value) < 0.002f) {
                value = target;
            }
            return value;
        }
    }

    /** A page of the settings down the side: an icon and its name, a bar of colour when it is open. */
    static final class Tab extends PaintedWidget {
        private final String icon;
        private final int tint;
        private final Runnable open;
        boolean selected;
        /** A number beside the name, such as how many settings are changed; empty for none. */
        String badge = "";

        Tab(int x, int y, int width, Component name, String icon, int tint, Runnable open) {
            super(x, y, width, 20, name);
            this.icon = icon;
            this.tint = tint;
            this.open = open;
        }

        @Override
        protected void paint(Painter p, int mouseX, int mouseY) {
            int x = getX(), y = getY();
            if (selected || isHoveredOrFocused()) {
                box(p, x, y, x + width, y + height, selected ? CARD : HOVER);
            }
            if (selected) {
                p.fill(x, y + 4, x + 2, y + height - 4, ACCENT);
            }
            p.blit("minecraft", icon, x + 6, y + 2, 16, 16, 0, 0, 16, 16, 16, 16, tint);
            int room = width - 30;
            if (!badge.isEmpty()) {
                int bw = font().width(badge) + 6;
                room -= bw + 3;
                box(p, x + width - 4 - bw, y + 5, x + width - 4, y + 15, selected ? ACCENT : LINE);
                p.text(Component.literal(badge), x + width - 1 - bw, y + 6, selected ? 0xFF0D1520 : TEXT, false);
            }
            p.text(Component.literal(fit(getMessage().getString(), room)), x + 26, y + 6, selected ? TEXT : SUB, false);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            open.run();
        }

        @Override
        public boolean keyPressed(KeyEvent key) {
            if (confirms(key)) {
                open.run();
                return true;
            }
            return false;
        }
    }

    /** A plain button: its label in a rounded box, lit up under the mouse. */
    static final class TextButton extends PaintedWidget {
        private final Runnable action;
        boolean accent;

        TextButton(int x, int y, int width, int height, Component label, Runnable action) {
            super(x, y, width, height, label);
            this.action = action;
        }

        @Override
        protected void paint(Painter p, int mouseX, int mouseY) {
            int x = getX(), y = getY();
            box(p, x, y, x + width, y + height, accent ? (isHoveredOrFocused() ? 0xFF7CC6FF : ACCENT)
                    : isHoveredOrFocused() ? HOVER : CARD);
            String label = fit(getMessage().getString(), width - 6);
            p.text(Component.literal(label), x + (width - font().width(label)) / 2, y + (height - 8) / 2,
                    !active ? DIM : accent ? 0xFF0D1520 : TEXT, false);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            action.run();
        }

        @Override
        public boolean keyPressed(KeyEvent key) {
            if (confirms(key)) {
                action.run();
                return true;
            }
            return false;
        }
    }

    /** The heading over a group of settings. */
    static final class Heading extends PaintedWidget {
        private final ResiduumConfigScreen screen;

        Heading(ResiduumConfigScreen screen, int width, Component name) {
            super(0, 0, width, 20, name);
            this.screen = screen;
            this.active = false;
        }

        @Override
        protected void paint(Painter p, int mouseX, int mouseY) {
            screen.clip(p);
            int x = getX(), y = getY();
            p.text(getMessage(), x + 4, y + 8, ACCENT, false);
            int after = x + 10 + font().width(getMessage());
            p.fill(after, y + 12, x + width - 4, y + 13, LINE);
            p.endScissor();
        }
    }

    /**
     * One setting: its name, and what it does in a line under it or its slider there; the switch or the value to the
     * right, and a reset arrow beside it when it differs from the default. Dimmed while the switch of its effect, or the
     * master switch, is off, or while the effect is left to another mod.
     */
    abstract static class Row extends PaintedWidget {
        static final int HEIGHT = 32;
        /** The setting's key, which names it and picks what the preview shows. */
        final String key;
        final ResiduumConfigScreen screen;
        /** The switch of the effect this setting belongs to, or null. */
        private final Settings.Toggle owner;

        Row(ResiduumConfigScreen screen, String key, Settings.Toggle owner, int width) {
            super(0, 0, width, HEIGHT, Settings.name(key));
            this.screen = screen;
            this.key = key;
            this.owner = owner;
        }

        static ResiduumConfig config() {
            return ResiduumConfig.get();
        }

        boolean live() {
            ResiduumConfig c = config();
            return key.equals("enabled") || c.enabled && (owner == null || owner.get().apply(c))
                    && (key.startsWith("leave_") || Settings.leftTo(key, c) == null);
        }

        String name() {
            return Settings.name(key).getString();
        }

        String description() {
            return Settings.description(key).getString();
        }

        abstract boolean isDefault();

        abstract void reset();

        /** Where the reset arrow is: its left edge, the right edge of the value or switch it sits beside. */
        abstract int resetX();

        /** Whether any of the row is inside the list, so there is something of it to draw. */
        boolean inView() {
            return screen.inList(getY() + height - 1) || screen.inList(getY());
        }

        boolean overReset(double mouseX, double mouseY) {
            int rx = resetX();
            return !isDefault() && mouseX >= rx - 2 && mouseX < rx + 10 && mouseY >= getY() + 2 && mouseY < getY() + 16;
        }

        /** Only the part of the row inside the list takes the mouse, not a part scrolled under the edge. */
        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            return super.isMouseOver(mouseX, mouseY) && screen.inList(mouseY);
        }

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (focused) {
                screen.reveal(this);
            }
        }

        /** The row's back, name and reset arrow; the rest is the kind of row's own. */
        void paintBase(Painter p, int mouseX, int mouseY) {
            int x = getX(), y = getY();
            boolean over = isMouseOver(mouseX, mouseY);
            if (over || isFocused()) {
                box(p, x, y, x + width, y + height - 2, HOVER);
            }
            if (isFocused()) {
                p.fill(x, y + 5, x + 2, y + height - 7, ACCENT);
            }
            p.text(Component.literal(fit(name(), resetX() - x - 14)), x + 8, y + 5, live() ? TEXT : DIM, false);
            if (!isDefault()) {
                p.text(Component.literal("↺"), resetX(), y + 5, overReset(mouseX, mouseY) ? ACCENT : SUB, false);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (overReset(event.x(), event.y())) {
                reset();
            } else {
                press(event.x(), event.y());
            }
            screen.changed();
        }

        abstract void press(double mouseX, double mouseY);
    }

    /** A switch, flipped by a click anywhere on its row or by Enter or Space. */
    static final class ToggleRow extends Row {
        private final Settings.Toggle toggle;
        private final Ease knob;

        ToggleRow(ResiduumConfigScreen screen, Settings.Toggle toggle, Settings.Toggle owner, int width) {
            super(screen, toggle.key(), owner, width);
            this.toggle = toggle;
            this.knob = new Ease(on() ? 1 : 0);
        }

        boolean on() {
            return toggle.get().apply(config());
        }

        @Override
        boolean isDefault() {
            return Settings.same(toggle, config(), DEFAULTS);
        }

        @Override
        void reset() {
            toggle.set().accept(config(), toggle.get().apply(DEFAULTS));
        }

        @Override
        int resetX() {
            return getX() + width - 44;
        }

        @Override
        void press(double mouseX, double mouseY) {
            toggle.set().accept(config(), !on());
        }

        @Override
        protected void paint(Painter p, int mouseX, int mouseY) {
            setMessage(CommonComponents.optionStatus(Component.literal(name()), on()));
            if (!inView()) {
                return;
            }
            screen.clip(p);
            paintBase(p, mouseX, mouseY);
            int x = getX(), y = getY();
            p.text(Component.literal(fit(description(), width - 16)), x + 8, y + 17, live() ? SUB : DIM, false);
            float k = knob.to(on() ? 1 : 0, 14);
            int tx = x + width - 30, ty = y + 5;
            int track = mix(TRACK, ACCENT, k);
            box(p, tx, ty, tx + 22, ty + 10, live() ? track : mix(track, CARD, 0.5f));
            int kx = tx + 1 + Math.round(k * 12);
            box(p, kx, ty + 1, kx + 8, ty + 9, live() ? TEXT : SUB);
            p.endScissor();
        }

        @Override
        public boolean keyPressed(KeyEvent key) {
            if (confirms(key)) {
                press(0, 0);
                screen.changed();
                return true;
            }
            return false;
        }
    }

    /** A slider: click or drag along its track, or step with the arrow keys. */
    static final class SliderRow extends Row {
        private final Settings.Slider slider;
        private final Ease shown;

        SliderRow(ResiduumConfigScreen screen, Settings.Slider slider, Settings.Toggle owner, int width) {
            super(screen, slider.key(), owner, width);
            this.slider = slider;
            this.shown = new Ease((float) position());
        }

        double value() {
            return slider.get().applyAsDouble(config());
        }

        double position() {
            return Math.clamp((value() - slider.min()) / (slider.max() - slider.min()), 0.0, 1.0);
        }

        void set(double value) {
            slider.set().accept(config(), slider.snap(value));
        }

        @Override
        boolean isDefault() {
            return Settings.same(slider, config(), DEFAULTS);
        }

        @Override
        void reset() {
            slider.set().accept(config(), slider.get().applyAsDouble(DEFAULTS));
        }

        @Override
        int resetX() {
            return getX() + width - 14 - font().width(slider.format(value()));
        }

        private int trackLeft() {
            return getX() + 8;
        }

        private int trackRight() {
            return getX() + width - 8;
        }

        @Override
        void press(double mouseX, double mouseY) {
            if (mouseY >= getY() + 14) {
                set(slider.min() + (slider.max() - slider.min())
                        * Math.clamp((mouseX - trackLeft()) / (trackRight() - trackLeft()), 0.0, 1.0));
            }
        }

        @Override
        protected void onDrag(MouseButtonEvent event, double dx, double dy) {
            set(slider.min() + (slider.max() - slider.min())
                    * Math.clamp((event.x() - trackLeft()) / (trackRight() - trackLeft()), 0.0, 1.0));
            screen.changed();
        }

        @Override
        public boolean keyPressed(KeyEvent key) {
            int step = key.isLeft() ? -1 : key.isRight() ? 1 : 0;
            if (step != 0) {
                set(value() + step * slider.step());
                screen.changed();
                return true;
            }
            return false;
        }

        @Override
        protected void paint(Painter p, int mouseX, int mouseY) {
            String value = slider.format(value());
            setMessage(Component.literal(name() + ": " + value));
            if (!inView()) {
                return;
            }
            screen.clip(p);
            paintBase(p, mouseX, mouseY);
            int x = getX(), y = getY();
            p.text(Component.literal(value), x + width - 8 - font().width(value), y + 5, live() ? ACCENT : DIM, false);
            float k = shown.to((float) position(), 18);
            int l = trackLeft(), r = trackRight(), ty = y + 20;
            box(p, l, ty, r, ty + 4, TRACK);
            int at = l + Math.round(k * (r - l));
            if (at > l + 1) {
                box(p, l, ty, at, ty + 4, live() ? ACCENT : mix(ACCENT, CARD, 0.6f));
            }
            box(p, at - 3, ty - 3, at + 3, ty + 7, live() ? TEXT : SUB);
            p.endScissor();
        }
    }

    /**
     * A row that does something rather than hold a setting: a preset to apply, settings to copy or paste. Its name and
     * what it does are lang keys like a setting's; the button-like label to the right says what a click does or how it
     * stands (a preset in use). What it did shows for a while in place of its description.
     */
    static final class ActionRow extends Row {
        private static final long STATUS_NANOS = 4_000_000_000L;
        private final Supplier<Component> label;
        /** Does it and says what it did, or returns null to say nothing. */
        private final Supplier<Component> action;
        private Component status;
        private long statusAt;

        ActionRow(ResiduumConfigScreen screen, String key, int width, Supplier<Component> label, Supplier<Component> action) {
            super(screen, key, null, width);
            this.label = label;
            this.action = action;
        }

        @Override
        boolean isDefault() {
            return true;
        }

        @Override
        void reset() {
        }

        @Override
        int resetX() {
            return getX() + width - 18 - font().width(label.get());
        }

        @Override
        void press(double mouseX, double mouseY) {
            Component said = action.get();
            if (said != null) {
                status = said;
                statusAt = System.nanoTime();
            }
        }

        @Override
        public boolean keyPressed(KeyEvent key) {
            if (confirms(key)) {
                press(0, 0);
                screen.changed();
                return true;
            }
            return false;
        }

        @Override
        protected void paint(Painter p, int mouseX, int mouseY) {
            Component text = label.get();
            setMessage(Component.literal(name() + ": " + text.getString()));
            if (!inView()) {
                return;
            }
            screen.clip(p);
            paintBase(p, mouseX, mouseY);
            int x = getX(), y = getY();
            boolean said = status != null && System.nanoTime() - statusAt < STATUS_NANOS;
            p.text(Component.literal(fit(said ? status.getString() : description(), width - 16)), x + 8, y + 17,
                    said ? ACCENT : SUB, false);
            int w = font().width(text), bx = x + width - 14 - w;
            box(p, bx, y + 3, bx + w + 8, y + 15, isMouseOver(mouseX, mouseY) || isFocused() ? ACCENT : CARD);
            p.text(text, bx + 4, y + 5, isMouseOver(mouseX, mouseY) || isFocused() ? 0xFF0D1520 : TEXT, false);
            p.endScissor();
        }
    }
}

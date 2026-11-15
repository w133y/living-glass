package com.w1z4r_d.residuum.gui;

import com.w1z4r_d.residuum.ResiduumClient;
import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.Settings;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.compat.PaintedWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import static com.w1z4r_d.residuum.gui.Widgets.*;

/**
 * Every setting, by effect: the pages down the left under a search box, with ready presets first and the settings
 * changed from the defaults last; the settings of the page in the middle as rows that say what they do, each with its
 * switch or slider; and on the right a live preview of the setting under the mouse or selected, with its full
 * description. Works with the keyboard as well (Tab, arrows, Enter, Ctrl+Z to undo, Shift to see the default).
 */
public class ResiduumConfigScreen extends Screen {
    private static final int PAD = 6, HEAD = 26, GAP = 6;
    /** The tabs past the pages of settings: presets before them, the changed settings after. */
    private static final int PRESETS = 0, FIRST_PAGE = 1, CHANGED = Settings.PAGES.size() + 1;
    /** Each tab's icon in the side bar, a vanilla texture, and its tint. */
    private static final String[] ICONS = {"textures/item/nether_star.png", "textures/block/glass.png",
            "textures/item/flint_and_steel.png", "textures/block/short_grass.png", "textures/item/snowball.png",
            "textures/item/redstone.png", "textures/item/writable_book.png"};
    private static final int[] TINTS = {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF79C05A, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
    private static final long CONFIRM_NANOS = 3_000_000_000L;
    /** How many changes back Ctrl+Z goes. */
    private static final int UNDO_STEPS = 64;

    /** The tab last open and what was searched for, so the screen comes back as it was left. */
    private static int page = FIRST_PAGE;
    private static String query = "";

    private final Screen parent;
    private final Preview preview = new Preview();
    /** The headings and rows in the list, and where each sits in it. */
    private final List<AbstractWidget> listed = new ArrayList<>();
    private final List<Integer> offsets = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    /** The settings after each change made here, oldest first, the last one as they were after the latest. */
    private final List<ResiduumConfig> history = new ArrayList<>();
    private final Ease scroll = new Ease(0);
    private float scrollTarget;
    private int listX, listTop, listWidth, listBottom, contentHeight;
    private EditBox search;
    private TextButton resetAll, undo;
    private long resetArmed;
    /** Whether the keyboard was used last rather than the mouse, so the preview follows the focus, not the pointer. */
    private boolean keyboard;

    public ResiduumConfigScreen(Screen parent) {
        super(Component.translatable("residuum.config.title"));
        this.parent = parent;
    }

    private static ResiduumConfig config() {
        return ResiduumConfig.get();
    }

    @Override
    protected void init() {
        listed.clear();
        offsets.clear();
        tabs.clear();
        if (history.isEmpty()) {
            history.add(Settings.snapshot(config()));
        }
        page = Math.clamp(page, 0, CHANGED);
        int side = this.width < 480 ? 92 : 116;
        int previewWidth = this.width >= 400 ? Math.min(240, (int) (this.width * 0.32)) : 0;
        listX = PAD + side + GAP;
        listTop = HEAD + 6;
        listBottom = this.height - PAD;
        listWidth = (previewWidth > 0 ? this.width - PAD - previewWidth - GAP : this.width - PAD) - listX;

        this.addRenderableWidget(Compat.canvas(0, 0, this.width, this.height, this::paintBack));
        undo = new TextButton(this.width - PAD - 64 - 4 - 64, 5, 64, 16, Component.translatable("residuum.config.undo"),
                this::undo);
        this.addRenderableWidget(undo);
        TextButton done = new TextButton(this.width - PAD - 64, 5, 64, 16, CommonComponents.GUI_DONE, this::onClose);
        done.accent = true;
        this.addRenderableWidget(done);

        search = new EditBox(this.font, PAD + 1, listTop, side - 2, 16, Component.translatable("residuum.config.search"));
        search.setHint(Component.translatable("residuum.config.search").withColor(DIM));
        search.setValue(query);
        search.setResponder(q -> {
            query = q;
            scrollTarget = 0;
            fill();
        });
        this.addRenderableWidget(search);
        for (int i = 0; i <= CHANGED; i++) {
            int index = i;
            Tab tab = new Tab(PAD, listTop + 22 + i * 21, side, tabName(i), ICONS[i], TINTS[i], () -> {
                page = index;
                search.setValue("");
                scrollTarget = 0;
                scroll.value = 0;
                fill();
            });
            tabs.add(this.addRenderableWidget(tab));
        }
        resetAll = this.addRenderableWidget(new TextButton(PAD, listBottom - 16, side, 16,
                Component.translatable("residuum.config.reset_all"), this::resetAll));

        // Lays the rows out each frame as the scroll eases, so it is drawn before them
        this.addRenderableWidget(Compat.canvas(listX, listTop, listWidth, listBottom - listTop, this::paintList));
        if (previewWidth > 0) {
            this.addRenderableWidget(new PreviewPanel(this.width - PAD - previewWidth, listTop, previewWidth,
                    listBottom - listTop));
        }
        fill();
    }

    private static Component tabName(int tab) {
        if (tab == PRESETS) {
            return Component.translatable("residuum.page.presets");
        }
        if (tab == CHANGED) {
            return Component.translatable("residuum.page.changed");
        }
        return Component.translatable("residuum.page." + Settings.PAGES.get(tab - FIRST_PAGE).key());
    }

    /**
     * Puts in the list the rows of the open tab, or of every setting that matches the search: the presets and sharing,
     * a page of settings, or the settings changed from the defaults.
     */
    private void fill() {
        for (AbstractWidget w : listed) {
            this.removeWidget(w);
        }
        listed.clear();
        offsets.clear();
        int y = 0;
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).selected = q.isEmpty() && i == page;
        }
        if (q.isEmpty() && page == PRESETS) {
            y = presets(y);
        } else {
            ResiduumConfig c = config();
            // Changed settings and search results come from every page, so their headings say which
            boolean everyPage = !q.isEmpty() || page == CHANGED;
            Predicate<Settings.Entry> shown = !q.isEmpty() ? e -> matches(e, q)
                    : page == CHANGED ? e -> !Settings.same(e, c, DEFAULTS) : e -> true;
            for (int i = 0; i < Settings.PAGES.size(); i++) {
                if (everyPage || i == page - FIRST_PAGE) {
                    for (Settings.Group group : Settings.PAGES.get(i).groups()) {
                        y = group(Settings.PAGES.get(i), group, shown, everyPage, y);
                    }
                }
            }
        }
        if (listed.isEmpty()) {
            String none = page == CHANGED && q.isEmpty() ? "residuum.config.unchanged" : "residuum.config.nothing";
            y = add(new Heading(this, listWidth - 6, Component.translatable(none)), y, 20);
        }
        contentHeight = y + 4;
        scrollTarget = Math.clamp(scrollTarget, 0, maxScroll());
    }

    private int group(Settings.Page page, Settings.Group group, Predicate<Settings.Entry> shown, boolean named, int y) {
        Settings.Toggle owner = group.entries().get(0) instanceof Settings.Toggle t ? t : null;
        List<Settings.Entry> rows = group.entries().stream().filter(shown).toList();
        if (rows.isEmpty()) {
            return y;
        }
        Component name = Component.translatable("residuum.group." + group.key());
        if (named) {
            name = Component.translatable("residuum.page." + page.key()).append(" › ").append(name);
        }
        y = add(new Heading(this, listWidth - 6, name), y, 20);
        for (Settings.Entry e : rows) {
            Settings.Toggle own = e == owner ? null : owner;
            Row row = switch (e) {
                case Settings.Toggle t -> new ToggleRow(this, t, own, listWidth - 6);
                case Settings.Slider s -> new SliderRow(this, s, own, listWidth - 6);
            };
            y = add(row, y, Row.HEIGHT);
        }
        return y + 4;
    }

    /** The presets, each applied with a click, then copying the settings for someone and pasting theirs. */
    private int presets(int y) {
        y = add(new Heading(this, listWidth - 6, Component.translatable("residuum.group.presets")), y, 20);
        for (Settings.Preset preset : Settings.PRESETS) {
            y = add(new ActionRow(this, preset.key(), listWidth - 6,
                    () -> Component.translatable(preset.matches(config()) ? "residuum.config.current" : "residuum.config.apply"),
                    () -> {
                        preset.apply(config());
                        return Component.translatable("residuum.config.applied");
                    }), y, Row.HEIGHT);
        }
        y = add(new Heading(this, listWidth - 6, Component.translatable("residuum.group.share")), y + 4, 20);
        y = add(new ActionRow(this, "copy", listWidth - 6, () -> Component.translatable("residuum.config.copy"), () -> {
            this.minecraft.keyboardHandler.setClipboard(Settings.code(config()));
            return Component.translatable("residuum.config.copied");
        }), y, Row.HEIGHT);
        y = add(new ActionRow(this, "paste", listWidth - 6, () -> Component.translatable("residuum.config.paste"), () -> {
            int set = Settings.paste(this.minecraft.keyboardHandler.getClipboard(), config());
            return set < 0 ? Component.translatable("residuum.config.paste_none")
                    : Component.translatable("residuum.config.pasted", set);
        }), y, Row.HEIGHT);
        return y + 4;
    }

    private static boolean matches(Settings.Entry e, String q) {
        String name = Settings.name(e.key()).getString().toLowerCase(Locale.ROOT);
        String what = Settings.description(e.key()).getString().toLowerCase(Locale.ROOT);
        return name.contains(q) || what.contains(q) || e.key().contains(q);
    }

    private int add(AbstractWidget widget, int y, int height) {
        listed.add(this.addRenderableWidget(widget));
        offsets.add(y);
        return y + height;
    }

    private float maxScroll() {
        return Math.max(0, contentHeight - (listBottom - listTop));
    }

    /** Clips what a row draws to the list, so rows scrolled half out of it are cut at its edge. */
    void clip(Painter p) {
        p.scissor(listX, listTop, listX + listWidth, listBottom);
    }

    boolean inList(double y) {
        return y >= listTop && y < listBottom;
    }

    /** Scrolls the list so a row given focus with the keyboard is in view. */
    void reveal(AbstractWidget row) {
        int i = listed.indexOf(row);
        if (i < 0) {
            return;
        }
        int top = offsets.get(i), view = listBottom - listTop;
        if (top - 22 < scrollTarget) {
            scrollTarget = top - 22;
        } else if (top + row.getHeight() > scrollTarget + view) {
            scrollTarget = top + row.getHeight() - view;
        }
        scrollTarget = Math.clamp(scrollTarget, 0, maxScroll());
    }

    /** A setting was changed here; Reset all asks again. */
    void changed() {
        resetArmed = 0;
    }

    private void resetAll() {
        if (System.nanoTime() - resetArmed > CONFIRM_NANOS) {
            resetArmed = System.nanoTime();
            return;
        }
        Settings.copy(DEFAULTS, config());
        resetArmed = 0;
    }

    /** Whether the settings differ from how they were after the last change kept for undoing. */
    private boolean unkept() {
        ResiduumConfig last = history.getLast(), c = config();
        return !Settings.ALL.stream().allMatch(e -> Settings.same(e, last, c));
    }

    /** Puts the settings back as they were before the last change. */
    private void undo() {
        if (unkept()) {
            history.add(Settings.snapshot(config()));
        }
        if (history.size() > 1) {
            history.removeLast();
            Settings.copy(history.getLast(), config());
            changed();
        }
    }

    private void paintBack(Painter p) {
        p.fill(0, 0, this.width, this.height, SHADE);
        p.fill(0, 0, this.width, HEAD, PANEL);
        p.fill(0, HEAD, this.width, HEAD + 1, LINE);
        p.blit("residuum", "icon.png", PAD, 5, 16, 16, 0, 0, 1, 1, 1, 1, 0xFFFFFFFF);
        Component title = Component.literal("Residuum");
        p.text(title, PAD + 22, 9, TEXT, false);
        p.text(Component.literal(ResiduumClient.version()), PAD + 28 + this.font.width(title), 9, DIM, false);
        box(p, listX - 3, listTop - 3, listX + listWidth + 3, listBottom + 3, PANEL);
    }

    /** Eases the scroll, lays the rows out where it leaves them, and draws the scroll bar. */
    private void paintList(Painter p) {
        float s = scroll.to(scrollTarget, 16);
        for (int i = 0; i < listed.size(); i++) {
            AbstractWidget w = listed.get(i);
            int y = listTop + offsets.get(i) - Math.round(s);
            // Rows scrolled out stay visible to the game, so Tab still reaches them and scrolls them in; they draw
            // nothing there and take no clicks (see Widgets.Row)
            w.setX(listX);
            w.setY(y);
        }
        int view = listBottom - listTop;
        if (contentHeight > view) {
            int thumb = Math.max(16, view * view / contentHeight);
            int at = listTop + Math.round((view - thumb) * s / maxScroll());
            box(p, listX + listWidth - 3, at, listX + listWidth - 1, at + thumb, LINE);
        }
        boolean armed = System.nanoTime() - resetArmed < CONFIRM_NANOS;
        resetAll.setMessage(Component.translatable(armed ? "residuum.config.reset_confirm" : "residuum.config.reset_all"));
        resetAll.accent = armed;
        undo.active = history.size() > 1 || unkept();
        ResiduumConfig c = config();
        long changed = Settings.ALL.stream().filter(e -> !Settings.same(e, c, DEFAULTS)).count();
        tabs.get(CHANGED).badge = changed > 0 ? String.valueOf(changed) : "";
    }

    /**
     * The preview follows the setting picked with the keyboard after a key, the one under the mouse after it moves.
     * Each change is kept for undoing once it is done: a slider dragged along is one change, not one a step.
     */
    @Override
    public void tick() {
        for (AbstractWidget w : listed) {
            if (w instanceof Row row && (keyboard ? row.isFocused()
                    : row.isHovered() && inList(row.getY() + row.getHeight() / 2.0))) {
                preview.show(row.key);
            }
        }
        if (!this.isDragging() && unkept()) {
            history.add(Settings.snapshot(config()));
            if (history.size() > UNDO_STEPS) {
                history.removeFirst();
            }
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
        keyboard = true;
        if (Compat.isUndo(key) && !(getFocused() instanceof EditBox)) {
            undo();
            return true;
        }
        return super.keyPressed(key);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        keyboard = false;
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX < listX + listWidth && inList(mouseY) && scrollY != 0) {
            scrollTarget = Math.clamp(scrollTarget - (float) scrollY * 36, 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        config().save();
        SodiumBridge.refresh();
        Compat.setScreen(this.minecraft, this.parent);
    }

    /**
     * The preview: the setting's name, the picture, then all it does in words. A click pauses or plays it; Shift held
     * shows the setting at its default instead.
     */
    private final class PreviewPanel extends PaintedWidget {
        PreviewPanel(int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable("residuum.preview.title"));
        }

        @Override
        protected void paint(Painter p, int mouseX, int mouseY) {
            // Not while typing in the search box, where Shift makes capitals
            preview.compare = Minecraft.getInstance().hasShiftDown() && !(ResiduumConfigScreen.this.getFocused() instanceof EditBox);
            int x = getX(), y = getY(), w = width;
            box(p, x - 3, y - 3, x + w + 3, y + height + 3, PANEL);
            p.text(Component.literal(fit(preview.title().getString(), w - 8)), x + 4, y + 2, TEXT, false);
            Component note = preview.note();
            p.text(Component.literal(fit(note.getString(), w - 8)), x + 4, y + 13,
                    preview.off() ? 0xFFE0796B : ACCENT, false);
            int sceneTop = y + 26, sceneHeight = Math.min((w - 8) * 2 / 3, (height - 26) / 2);
            box(p, x + 2, sceneTop - 2, x + w - 2, sceneTop + sceneHeight + 2, CARD);
            p.scissor(x + 4, sceneTop, x + w - 4, sceneTop + sceneHeight);
            preview.paint(p, x + 4, sceneTop, w - 8, sceneHeight);
            p.endScissor();
            if (isHoveredOrFocused() || preview.paused()) {
                String hint = Component.translatable(preview.paused() ? "residuum.preview.paused" : "residuum.preview.pause").getString();
                p.text(Component.literal(fit(hint, w - 12)), x + 6, sceneTop + sceneHeight - 10, TEXT, true);
            }
            // The default and how to see it go last, under as much of the description as fits
            List<String> extra = new ArrayList<>();
            String byDefault = preview.defaultText();
            if (!byDefault.isEmpty()) {
                extra.add(byDefault);
            }
            if (preview.changed()) {
                extra.add(Component.translatable("residuum.preview.hold_shift").getString());
            }
            int ty = sceneTop + sceneHeight + 8, room = y + height - 12 * extra.size();
            for (String line : wrap(preview.description(), w - 8)) {
                if (ty + 9 > room) {
                    break;
                }
                p.text(Component.literal(line), x + 4, ty, SUB, false);
                ty += 10;
            }
            for (String line : extra) {
                if (ty + 13 <= y + height) {
                    p.text(Component.literal(fit(line, w - 8)), x + 4, ty + 4, DIM, false);
                    ty += 12;
                }
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            preview.togglePause();
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
            if (confirms(key)) {
                preview.togglePause();
                return true;
            }
            return false;
        }
    }
}

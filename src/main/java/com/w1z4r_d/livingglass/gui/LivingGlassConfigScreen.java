package com.w1z4r_d.livingglass.gui;

import com.w1z4r_d.livingglass.LivingGlassConfig;
import com.w1z4r_d.livingglass.compat.Compat;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Mod Menu settings: effect switches on the main page, sliders for timing, climate and performance on sub-pages. */
public class LivingGlassConfigScreen extends Screen {
    private enum Page {
        MAIN, EFFECTS, CLIMATE, PERFORMANCE;

        String key() {
            return "livingglass.config.page." + name().toLowerCase(Locale.ROOT);
        }
    }

    private static final int ROW = 24;
    private static final int COLUMN = 150;
    private static final int GAP = 10;

    private final Screen parent;
    private final Page page;

    public LivingGlassConfigScreen(Screen parent) {
        this(parent, Page.MAIN);
    }

    private LivingGlassConfigScreen(Screen parent, Page page) {
        super(Component.translatable(page == Page.MAIN ? "livingglass.config.title" : page.key()));
        this.parent = parent;
        this.page = page;
    }

    @Override
    protected void init() {
        LivingGlassConfig c = LivingGlassConfig.get();
        int left = this.width / 2 - COLUMN - GAP / 2;
        int right = this.width / 2 + GAP / 2;
        int y = Math.max(28, this.height / 6);
        int rows = switch (page) {
            case MAIN -> mainPage(c, left, right, y);
            case EFFECTS -> effectsPage(c, left, right, y);
            case CLIMATE -> climatePage(c, left, right, y);
            case PERFORMANCE -> performancePage(c, left, right, y);
        };
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(this.width / 2 - 100, y + rows * ROW + 12, 200, 20).build());
    }

    private int mainPage(LivingGlassConfig c, int left, int right, int y) {
        toggle("enabled", left, y, () -> c.enabled, v -> c.enabled = v);
        toggle("debug", right, y, () -> c.debug, v -> c.debug = v);
        toggle("condensation", left, y + ROW, () -> c.condensation.enabled, v -> c.condensation.enabled = v);
        toggle("frost", right, y + ROW, () -> c.frost.enabled, v -> c.frost.enabled = v);
        toggle("raindrops", left, y + ROW * 2, () -> c.raindrops.enabled, v -> c.raindrops.enabled = v);
        toggle("breath_spot", right, y + ROW * 2, () -> c.breathSpot.enabled, v -> c.breathSpot.enabled = v);
        toggle("wipe", left, y + ROW * 3, () -> c.wipe.enabled, v -> c.wipe.enabled = v);
        toggle("tint_stained_glass", right, y + ROW * 3, () -> c.tintStainedGlass, v -> c.tintStainedGlass = v);
        toggle("ice", left, y + ROW * 4, () -> c.ice.enabled, v -> c.ice.enabled = v);
        toggle("ash", right, y + ROW * 4, () -> c.ash.enabled, v -> c.ash.enabled = v);
        toggle("snowballs", left, y + ROW * 5, () -> c.snowballs.enabled, v -> c.snowballs.enabled = v);
        int navY = y + ROW * 6 + 8;
        int navW = (COLUMN * 2 + GAP - 2 * 4) / 3;
        int x = left;
        for (Page sub : new Page[] {Page.EFFECTS, Page.CLIMATE, Page.PERFORMANCE}) {
            this.addRenderableWidget(Button.builder(Component.translatable(sub.key()),
                    button -> Compat.setScreen(this.minecraft, new LivingGlassConfigScreen(this, sub)))
                    .bounds(x, navY, navW, 20).build());
            x += navW + 4;
        }
        return 7;
    }

    private int effectsPage(LivingGlassConfig c, int left, int right, int y) {
        LivingGlassConfig.Timing t = c.timing;
        slider("fog_strength", left, y, 0, 2, 0.05, c.condensation.strength, v -> c.condensation.strength = v, "%.2f");
        slider("fog_build", right, y, 5, 180, 5, t.fogBuildSeconds, v -> t.fogBuildSeconds = v, "%.0f s");
        slider("fog_clear", left, y + ROW, 5, 180, 5, t.fogClearSeconds, v -> t.fogClearSeconds = v, "%.0f s");
        slider("frost_build", right, y + ROW, 10, 300, 10, t.frostBuildSeconds, v -> t.frostBuildSeconds = v, "%.0f s");
        slider("frost_melt", left, y + ROW * 2, 5, 180, 5, t.frostMeltSeconds, v -> t.frostMeltSeconds = v, "%.0f s");
        slider("drying", right, y + ROW * 2, 0, 60, 5, t.dryingSeconds, v -> t.dryingSeconds = v, "%.0f s");
        slider("melt", right, y + ROW * 3, 5, 120, 5, t.meltSeconds, v -> t.meltSeconds = v, "%.0f s");
        slider("ice_build", left, y + ROW * 3, 30, 600, 10, t.iceBuildSeconds, v -> t.iceBuildSeconds = v, "%.0f s");
        slider("ice_fogged", left, y + ROW * 4, 10, 600, 10, t.iceFoggedSeconds, v -> t.iceFoggedSeconds = v, "%.0f s");
        slider("ash_build", right, y + ROW * 4, 1, 60, 1, t.ashBuildSeconds, v -> t.ashBuildSeconds = v, "%.0f s");
        slider("ash_side", left, y + ROW * 5, 5, 300, 5, t.ashSideBuildSeconds, v -> t.ashSideBuildSeconds = v, "%.0f s");
        slider("ash_fade", right, y + ROW * 5, 10, 600, 10, t.ashFadeSeconds, v -> t.ashFadeSeconds = v, "%.0f s");
        slider("snow", left, y + ROW * 6, 5, 120, 5, t.snowSeconds, v -> t.snowSeconds = v, "%.0f s");
        return 7;
    }

    private int climatePage(LivingGlassConfig c, int left, int right, int y) {
        LivingGlassConfig.Climate cl = c.climate;
        slider("comfort_temp", left, y, 0.3, 1.5, 0.05, cl.comfortTemp, v -> cl.comfortTemp = v, "%.2f");
        slider("night_penalty", right, y, 0, 0.5, 0.05, cl.nightPenalty, v -> cl.nightPenalty = v, "%.2f");
        slider("rain_penalty", left, y + ROW, 0, 0.5, 0.05, cl.rainPenalty, v -> cl.rainPenalty = v, "%.2f");
        slider("heat_radius", right, y + ROW, 2, 12, 1, cl.heatScanRadius, v -> cl.heatScanRadius = (int) v, "%.0f");
        return 2;
    }

    private int performancePage(LivingGlassConfig c, int left, int right, int y) {
        slider("scan_radius", left, y, 8, 48, 4, c.scan.radius, v -> c.scan.radius = (int) v, "%.0f");
        slider("scan_budget", right, y, 64, 2048, 64, c.scan.budgetPerTick, v -> c.scan.budgetPerTick = (int) v, "%.0f");
        slider("render_radius", left, y + ROW, 8, 64, 4, c.render.radius, v -> c.render.radius = (int) v, "%.0f");
        slider("tracked_max", right, y + ROW, 256, 8192, 256, c.tracked.max, v -> c.tracked.max = (int) v, "%.0f");
        return 2;
    }

    private void toggle(String key, int x, int y, BooleanSupplier getter, Consumer<Boolean> setter) {
        this.addRenderableWidget(Button.builder(label(key, getter.getAsBoolean()), button -> {
            setter.accept(!getter.getAsBoolean());
            button.setMessage(label(key, getter.getAsBoolean()));
        }).bounds(x, y, COLUMN, 20).build());
    }

    private static Component label(String key, boolean value) {
        return Component.translatable("livingglass.config." + key,
                value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }

    /** A slider over min..max snapped to step; the label shows the value with the given format. */
    private void slider(String key, int x, int y, double min, double max, double step, double value,
                        DoubleConsumer setter, String format) {
        String translationKey = "livingglass.config." + key;
        OptionInstance<Double> option = new OptionInstance<>(translationKey,
                OptionInstance.noTooltip(),
                (caption, slider) -> Component.translatable(translationKey,
                        String.format(Locale.ROOT, format, snap(slider, min, max, step))),
                OptionInstance.UnitDouble.INSTANCE,
                Math.clamp((value - min) / (max - min), 0.0, 1.0),
                slider -> setter.accept(snap(slider, min, max, step)));
        this.addRenderableWidget(option.createButton(this.minecraft.options, x, y, COLUMN));
    }

    private static double snap(double slider, double min, double max, double step) {
        double value = min + Math.round((max - min) * slider / step) * step;
        return Math.clamp(value, min, max);
    }

    @Override
    public void onClose() {
        LivingGlassConfig.get().save();
        Compat.setScreen(this.minecraft, this.parent);
    }
}

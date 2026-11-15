package com.w1z4r_d.residuum.sodium;

import com.w1z4r_d.residuum.ResiduumClient;
import com.w1z4r_d.residuum.ResiduumConfig;
import com.w1z4r_d.residuum.Settings;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.gui.ResiduumConfigScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.IntegerOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Residuum in Sodium's own settings screen, next to Sodium and Iris: one short page of the main switches
 * (Settings.QUICK), and a page that opens the screen of our own with every setting and a live preview. Only on
 * Minecraft versions whose Sodium has this API.
 */
public final class SodiumConfig implements ConfigEntryPoint {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath(ResiduumClient.MOD_ID, "textures/gui/config-icon.png");
    /** The light blue of the logo, tinting the icon and the page's accents. */
    private static final int THEME = 0xFF55B4FF;
    /** One handler for every option, so the config is written once per Apply. */
    private static final StorageEventHandler SAVE = () -> ResiduumConfig.get().save();
    /** What Sodium shows as the cost of an option, for the ones that cost frame or tick time. */
    private static final Map<String, OptionImpact> IMPACT = Map.of(
            "scan_radius", OptionImpact.MEDIUM,
            "scan_budget", OptionImpact.MEDIUM,
            "render_distance", OptionImpact.MEDIUM,
            "tracked_windows", OptionImpact.LOW);

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        ModOptionsBuilder mod = builder.registerOwnModOptions()
                .setName("Residuum")
                .setIcon(ICON)
                .setColorTheme(builder.createColorTheme().setBaseThemeRGB(THEME))
                .setVersion(ResiduumClient.version());
        OptionPageBuilder page = builder.createOptionPage().setName(Component.translatable("residuum.page.quick"));
        for (Settings.Group group : Settings.QUICK) {
            OptionGroupBuilder groupBuilder = builder.createOptionGroup()
                    .setName(Component.translatable("residuum.group." + group.key()));
            for (Settings.Entry entry : group.entries()) {
                groupBuilder.addOption(option(builder, entry));
            }
            page.addOptionGroup(groupBuilder);
        }
        mod.addPage(page);
        mod.addPage(builder.createExternalPage()
                .setName(Component.translatable("residuum.page.all"))
                .setScreenConsumer(current -> Compat.setScreen(Minecraft.getInstance(), new ResiduumConfigScreen(current))));
    }

    private static OptionBuilder option(ConfigBuilder builder, Settings.Entry entry) {
        Identifier id = Identifier.fromNamespaceAndPath(ResiduumClient.MOD_ID, entry.key());
        Component name = Component.translatable("residuum.option." + entry.key());
        Component tooltip = Component.translatable("residuum.option." + entry.key() + ".tooltip");
        ResiduumConfig defaults = new ResiduumConfig();
        return switch (entry) {
            case Settings.Toggle toggle -> builder.createBooleanOption(id)
                    .setName(name)
                    .setTooltip(tooltip)
                    .setStorageHandler(SAVE)
                    .setDefaultValue(toggle.get().apply(defaults))
                    .setBinding(value -> toggle.set().accept(ResiduumConfig.get(), value),
                            () -> toggle.get().apply(ResiduumConfig.get()));
            case Settings.Slider slider -> {
                // Sodium's sliders are whole numbers: a step of 0.05 becomes a step of 5 on a hundred times the value
                int scale = slider.step() < 1 ? 100 : 1;
                IntegerOptionBuilder option = builder.createIntegerOption(id)
                        .setName(name)
                        .setTooltip(tooltip)
                        .setStorageHandler(SAVE)
                        .setRange((int) Math.round(slider.min() * scale), (int) Math.round(slider.max() * scale),
                                (int) Math.round(slider.step() * scale))
                        .setDefaultValue((int) Math.round(slider.get().applyAsDouble(defaults) * scale))
                        .setBinding(value -> slider.set().accept(ResiduumConfig.get(), value / (double) scale),
                                () -> (int) Math.round(slider.get().applyAsDouble(ResiduumConfig.get()) * scale))
                        .setValueFormatter(value -> Component.literal(slider.format(value / (double) scale)));
                if (IMPACT.containsKey(slider.key())) {
                    option.setImpact(IMPACT.get(slider.key()));
                }
                yield option;
            }
        };
    }
}

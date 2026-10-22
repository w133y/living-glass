package com.w1z4r_d.livingglass;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.w1z4r_d.livingglass.gui.LivingGlassConfigScreen;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LivingGlassConfigScreen::new;
    }
}

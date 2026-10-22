package com.w1z4r_d.livingglass.compat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.livingglass.compat.Compat;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Collects overlay submits together with entities, so they are drawn in the translucent feature pass. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Inject(method = "submitEntities", at = @At("TAIL"))
    private void livingglass$submitOverlays(PoseStack poseStack, LevelRenderState state, SubmitNodeCollector collector,
                                            CallbackInfo ci) {
        if (Compat.levelRenderCallback != null) {
            Compat.levelRenderCallback.accept(poseStack, collector);
        }
    }
}

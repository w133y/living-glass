package com.w1z4r_d.residuum.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.residuum.render.ShieldMarks;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.ShieldSpecialRenderer;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Snowball splats on the shield, drawn right after it. The shield renderer's submit takes an item display context
 * before 26.1 and not after, so each form has its own hook and only the one that exists is applied.
 */
@Mixin(ShieldSpecialRenderer.class)
abstract class ShieldSpecialRendererMixin {
    @Inject(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V", at = @At("TAIL"), require = 0)
    private void residuum$marks(DataComponentMap components, PoseStack poseStack, SubmitNodeCollector collector,
                                   int light, int overlay, boolean foil, int outline, CallbackInfo ci) {
        ShieldMarks.draw(poseStack, collector, light, true);
    }

    @Inject(method = "submit(Lnet/minecraft/core/component/DataComponentMap;Lnet/minecraft/world/item/ItemDisplayContext;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V",
            at = @At("TAIL"), require = 0)
    private void residuum$marksBefore26(DataComponentMap components, ItemDisplayContext context, PoseStack poseStack,
                                           SubmitNodeCollector collector, int light, int overlay, boolean foil,
                                           int outline, CallbackInfo ci) {
        ShieldMarks.draw(poseStack, collector, light, false);
    }
}

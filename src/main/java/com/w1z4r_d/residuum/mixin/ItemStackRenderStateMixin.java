package com.w1z4r_d.residuum.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.w1z4r_d.residuum.render.ShieldMarks;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes the holder of the item known to the shield renderer for as long as the item is being submitted. */
@Mixin(ItemStackRenderState.class)
abstract class ItemStackRenderStateMixin implements ShieldMarks.Owned {
    @Unique
    private int residuum$owner = -1;

    @Override
    public void residuum$setOwner(int entityId) {
        residuum$owner = entityId;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void residuum$forgetHolder(CallbackInfo ci) {
        residuum$owner = -1;
    }

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
            at = @At("HEAD"))
    private void residuum$startSubmit(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
                                         int outline, CallbackInfo ci) {
        ShieldMarks.owner = residuum$owner;
    }

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V",
            at = @At("RETURN"))
    private void residuum$endSubmit(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
                                       int outline, CallbackInfo ci) {
        ShieldMarks.owner = -1;
    }
}

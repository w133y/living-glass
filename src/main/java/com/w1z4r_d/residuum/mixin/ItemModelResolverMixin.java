package com.w1z4r_d.residuum.mixin;

import com.w1z4r_d.residuum.render.ShieldMarks;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes whose item a render state is for: the shield renderer does not know who is holding it. */
@Mixin(ItemModelResolver.class)
abstract class ItemModelResolverMixin {
    @Inject(method = "updateForLiving", at = @At("TAIL"))
    private void residuum$noteHolder(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
                                        LivingEntity entity, CallbackInfo ci) {
        ((ShieldMarks.Owned) state).residuum$setOwner(entity.getId());
    }
}

package com.w1z4r_d.residuum.mixin;

import com.w1z4r_d.residuum.render.Tumbleweeds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A swing that breaks a tumbleweed goes at the air, so it does not also start mining the block behind it. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void residuum$hitTumbleweed(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        if (Tumbleweeds.hit(mc)) {
            Vec3 eye = mc.player.getEyePosition();
            mc.hitResult = BlockHitResult.miss(eye, Direction.UP, BlockPos.containing(eye));
        }
    }
}

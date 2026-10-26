package com.w1z4r_d.livingglass.mixin;

import com.w1z4r_d.livingglass.decal.DecalTracker;
import com.w1z4r_d.livingglass.glass.GlassTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every client-side block change passes through here; used to reclassify nearby windows. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void livingglass$onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags,
                                            CallbackInfo ci) {
        if (oldState != newState) {
            GlassTracker.INSTANCE.onBlockChanged(pos, oldState, newState);
            DecalTracker.INSTANCE.onBlockChanged(pos);
        }
    }
}

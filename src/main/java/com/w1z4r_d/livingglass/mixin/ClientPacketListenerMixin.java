package com.w1z4r_d.livingglass.mixin;

import com.w1z4r_d.livingglass.compat.Compat;
import com.w1z4r_d.livingglass.decal.DecalTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The server tells clients when a snowball breaks on something (event 3, the one that throws its snow about). */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    private static final byte SNOWBALL_BREAKS = 3;

    // At return the packet is being handled on the game thread, not the network one that hands it over
    @Inject(method = "handleEntityEvent", at = @At("RETURN"))
    private void livingglass$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (packet.getEventId() != SNOWBALL_BREAKS || minecraft.level == null || !minecraft.isSameThread()) {
            return;
        }
        Entity entity = packet.getEntity(minecraft.level);
        if (entity != null && Compat.isSnowball(entity)) {
            DecalTracker.INSTANCE.snowballHit(entity);
        }
    }
}

package com.w1z4r_d.residuum.mixin;

import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.decal.DecalTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The server tells clients when a snowball breaks on something (event 3, the one that throws its snow about), and when
 * an axe knocks a shield aside, by the sound of it at the shield's holder, when an arrow or trident sticks in a block,
 * by the sound of that, and when something explodes.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    private static final byte SNOWBALL_BREAKS = 3;

    // At return the packet is being handled on the game thread, not the network one that hands it over
    @Inject(method = "handleEntityEvent", at = @At("RETURN"))
    private void residuum$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (packet.getEventId() != SNOWBALL_BREAKS || minecraft.level == null || !minecraft.isSameThread()) {
            return;
        }
        Entity entity = packet.getEntity(minecraft.level);
        if (entity != null && Compat.isSnowball(entity)) {
            DecalTracker.INSTANCE.snowballHit(entity);
        }
    }

    @Inject(method = "handleExplosion", at = @At("RETURN"))
    private void residuum$onExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            DecalTracker.INSTANCE.explosion(packet.center(), packet.radius(), packet.explosionParticle());
        }
    }

    @Inject(method = "handleSoundEvent", at = @At("RETURN"))
    private void residuum$onSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.isSameThread()) {
            return;
        }
        var sound = packet.getSound().value().location();
        if (sound.equals(SoundEvents.SHIELD_BREAK.value().location())) {
            DecalTracker.INSTANCE.shieldDisabled(packet.getX(), packet.getY(), packet.getZ());
        } else if (sound.equals(SoundEvents.ARROW_HIT.location()) || sound.equals(SoundEvents.CROSSBOW_HIT.location())
                || sound.equals(SoundEvents.TRIDENT_HIT_GROUND.location())) {
            DecalTracker.INSTANCE.projectileHit(packet.getX(), packet.getY(), packet.getZ());
        }
    }
}

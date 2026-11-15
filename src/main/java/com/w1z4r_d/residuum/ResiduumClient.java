package com.w1z4r_d.residuum;

import com.mojang.blaze3d.platform.InputConstants;
import com.w1z4r_d.residuum.compat.Compat;
import com.w1z4r_d.residuum.decal.Coats;
import com.w1z4r_d.residuum.decal.DecalTracker;
import com.w1z4r_d.residuum.glass.GlassState;
import com.w1z4r_d.residuum.glass.GlassTracker;
import com.w1z4r_d.residuum.render.Fireflies;
import com.w1z4r_d.residuum.render.OverlayRenderer;
import com.w1z4r_d.residuum.render.Steam;
import com.w1z4r_d.residuum.render.Trampling;
import com.w1z4r_d.residuum.render.Tumbleweeds;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class ResiduumClient implements ClientModInitializer {
    public static final String MOD_ID = "residuum";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** The mod's version without the Minecraft version after the plus sign. */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString().split("[+]")[0]).orElse("");
    }

    private static KeyMapping toggleKey;
    private static KeyMapping debugKey;

    @Override
    public void onInitializeClient() {
        ResiduumConfig.get();
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Settings.checkCode();
        }

        // Unbound by default
        toggleKey = Compat.registerKey("key.residuum.toggle", InputConstants.UNKNOWN.getValue(), MOD_ID, "main");
        debugKey = Compat.registerKey("key.residuum.debug", InputConstants.UNKNOWN.getValue(), MOD_ID, "main");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ResiduumConfig config = ResiduumConfig.get();
            while (toggleKey.consumeClick()) {
                config.enabled = !config.enabled;
                config.save();
            }
            while (debugKey.consumeClick()) {
                config.debug = !config.debug;
                config.save();
            }
            GlassTracker.INSTANCE.tick(client);
            DecalTracker.INSTANCE.tick(client);
            Tumbleweeds.tick(client);
            Trampling.tick(client);
            Fireflies.tick(client);
            Coats.INSTANCE.tick(client);
            Steam.tick(client);
            if (config.enabled && config.debug && client.level != null && client.level.getGameTime() % 5 == 0) {
                Compat.overlayMessage(client, debugLine(client));
            }
        });

        Compat.onLevelRender(OverlayRenderer::render);
        Compat.registerTrampling();
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> GlassTracker.INSTANCE.onChunkLoad(chunk));

        // Visual only: the click still goes through to vanilla when nothing was wiped
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ResiduumConfig config = ResiduumConfig.get();
            if (level.isClientSide() && config.glassOn() && config.wipe.enabled && hand == InteractionHand.MAIN_HAND
                    && player.getItemInHand(hand).isEmpty() && GlassTracker.INSTANCE.wipe(hit)) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    private static Component debugLine(Minecraft mc) {
        String line = String.format(Locale.ROOT, "Residuum: tracked %d, visible %d, tick %.3f ms, render %.3f ms",
                GlassTracker.INSTANCE.states().size(), OverlayRenderer.visible,
                GlassTracker.INSTANCE.tickNanos / 1e6, OverlayRenderer.renderNanos / 1e6);
        if (mc.hitResult instanceof BlockHitResult hit) {
            GlassState gs = GlassTracker.INSTANCE.states().get(hit.getBlockPos().asLong());
            if (gs != null) {
                line += String.format(Locale.ROOT, " | %s out %.2f warm %.2f fog %.2f/%.2f frost %.2f/%.2f drops %.2f/%.2f",
                        gs.isWindow() ? "window" : "exposed", gs.outsideTemp, gs.warmth,
                        gs.fog, gs.fogTarget, gs.frost, gs.frostTarget, gs.drops, gs.dropTarget);
            }
        }
        return Component.literal(line);
    }
}

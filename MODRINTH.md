# Living Glass

**Vanilla glass reacts to the weather.** Windows fog up and frost over when it's cold outside and warm inside, and raindrops run down the glass in the rain.

Client-side only. No new blocks, no crafting, works on any server.

## What you'll see

❄️ **Frost:** below freezing, crystals creep in from the frame of the window.

![Frost creeping in from the frame](https://raw.githubusercontent.com/w133y/living-glass/main/media/frost.gif)

🧊 **Outside ice:** below freezing, bluish ice grows on the outside of the glass, mostly from the bottom.

🔥 **Ash:** fire leaves a black scorch on the block it burns on and soot on the blocks next to it. It fades after the fire is out.

⛄ **Snowball marks:** a thrown snowball leaves a splat on any block it hits for about 20 seconds.

⚡ **Lightning scorch:** a strike leaves a burn mark with glowing embers on the ground around it, fading after a few minutes.

🌫️ **Condensation:** a cosy cabin with a lit furnace in a snowy biome slowly fogs up. The fog is densest along the frame and at the bottom.

✋ **Wipe:** right-click the glass with an empty hand to clear a spot. It slowly fogs up again.

![Wiping the glass](https://raw.githubusercontent.com/w133y/living-glass/main/media/wipe.gif)

🧱 **Living windows:** build or break glass next to a frosted window and the frost slowly melts into the new shape. Panes and glass blocks in one wall act as one window.

![Breaking and placing glass](https://raw.githubusercontent.com/w133y/living-glass/main/media/melt.gif)

🌧️ **Raindrops:** drops run down glass exposed to rain and land on its top. Glass under a roof stays dry.

![Raindrops on the outside](https://raw.githubusercontent.com/w133y/living-glass/main/media/raindrops.gif)

😮‍💨 **Breath:** stand right at a cold window and your breath leaves a patch that fades away.

![Breath on a cold window](https://raw.githubusercontent.com/w133y/living-glass/main/media/breath.gif)

Everything fades in and out smoothly. It depends on the biome, altitude, time of day, weather and the heat sources in the room: furnaces, campfires, fire, lava, torches and players.

## Details

- Works with glass, panes, stained glass and tinted glass. Large windows act as one surface.
- Effects are drawn as a separate overlay pass, so chunk meshes and models are untouched. Works with **Sodium** and with connected-glass resource packs.
- Settings via **Mod Menu**: switch each effect on and off, and use sliders for timing, climate and performance.
- Optional keybinds to toggle the mod and a debug view.

Pairs nicely with rain and ambient sound mods.

Source: [github.com/w133y/living-glass](https://github.com/w133y/living-glass)

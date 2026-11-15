# Changelog

## 1.2.0

Living Glass is now **Residuum**: it has grown well past glass. Its settings move over by themselves from `config/livingglass.json` to `config/residuum.json` the first time it starts, and settings copied as text from Living Glass paste in too. The toggle and debug keys, unbound unless you bound them, need binding again under the new name.

**New**
- **Running condensation:** thick fog gathers into drops a pixel wide that run down the window, wiping a trail through the mist that slowly fogs over again.
- **Snowflakes:** in a snowfall, flakes stick to glass out in it, twice as many in a storm; each melts away gradually, and so do the last of them once the snowfall is over.
- **Frosty nights:** outside ice also grows on freezing nights without snow, at half the pace.
- **Lightning rods:** a rod struck by lightning crackles with little bolts and sparks for 10 to 15 seconds.
- **Trampled grass:** grass, ferns, tall grass, dry grass and bushes that players walk through give way over a few ticks, pushed aside off their way to either side, and once left alone spring back up in fine steps half a second after, quickly at first and slower as they straighten.
- **Explosion soot:** TNT, creepers, end crystals, beds and other explosions blacken the crater they leave and the blocks round it that the blast could see, black near it and thinning out raggedly. The soot stays 4 minutes (a new setting), then fades like ash.
- **Tumbleweeds** cast a shadow and kick up the ground's dust where they land, hop or knock into a wall.
- **Cracked glass:** an arrow or trident that sticks in glass cracks it round where it went in, a jagged web that shows from both sides and runs on into the glass next to it. It stays 3 minutes (a new setting), and goes at once if the glass is broken.
- **Footprints:** players and mobs leave footprints in snow and sand as they walk, blocky as the feet that made them: square boot prints from people and paw prints from animals, each foot in turn, laid on the block's own pixels. They fade after a minute (a new setting), and mobs' can be switched off.
- **Fireflies:** on dry nights that are not cold, specks of light glow, pulse gently and drift over grass, ferns and flowers under the open sky, green-blue in cool biomes, yellow-green in mild ones and amber in hot ones, coming out little by little as night falls and thinning out toward dawn. They keep away from torches and lamps. How many is a setting.
- **Hoarfrost:** on clear nights cold enough to freeze, the tops of grass, leaves, moss and podzol out under the sky frost over white in patches of crystals, and thaw in the morning.
- **Snow on walls:** in a blizzard (a thunderstorm in the snow, by default; a setting lets any snowfall do), snow builds up a flake at a time on the walls of what is built out in the open, in the game's own snow texture: thick on the walls facing the wind, a little on those it blows along. It lies on what is seen of a block (the rails of a fence, only the solid pixels of a door), runs on a few pixels onto the faces beside it and round corners, and drifts up from the snow at a wall's foot. The ground and rock of the land, glass, and anything that gives light stay bare. It falls away over a quarter of an hour once the snow stops. The wind turns every ten minutes or so. How much, how much on the side walls, and how long it takes to come and go are settings.
- **Snow on ledges:** in any snowfall, snow settles in clumps on the tops of stairs, slabs, fences, walls, doors and trapdoors out under the sky, which the game's snow leaves bare, and drapes a little over their edges. It stays after the snowfall, as the snow on the ground does (a setting).
- **Arrow holes:** an arrow or trident that sticks in a block other than glass leaves a small chipped hole there, which fades after a while.
- **Steam:** water that lava or magma heats from a block or two away gives off wisps of steam.
- **Other mods:** with Weather Glass installed, Residuum leaves the effects on windows to it; with Interactive Foliage, the grass; with Soft Imprints, the footprints. Each can be switched back to Residuum's own in the settings.

**Improved**
- Fireflies leave within a couple of seconds once it is day or raining, instead of glowing on until their time is up.
- Soot and ash go with their block when another is put in its place.
- Ash and soot run on round the outside corners of blocks instead of thinning out at each edge, and a lot of it, after many explosions, costs a fraction of the time to draw.
- Settings: Sodium's video settings show a short page of the main switches and an **All settings** page that opens a new full settings screen (also what Mod Menu opens). It groups the settings by effect, with a search box; each row says what the setting does, with a switch or slider and a reset arrow once changed, and settings of an effect that is off are dimmed. Beside them a live preview, with the full description, shows what the setting under the mouse or picked with the keyboard does as the settings stand: every effect has its own picture drawn with the mod's own textures, timings play ten times faster or more, so each build-up takes a few seconds, and climate and performance settings get diagrams worked out from their values (which biomes fog by day, at night and in rain; how far windows are found or drawn). Click the preview to pause it, hold Shift to see the setting at its default. Reset all asks for a second click. Ready-made presets (Subtle, Standard, Maximum, Low-end PC) are a click away, with the one in use marked; a Changed tab lists the settings changed from the defaults; Undo (or Ctrl+Z) takes changes back one by one; and your changes can be copied as one line of text for someone else to paste in.
- A window runs on round a corner of glass as one window: no band of fog or frost down the corner, and the corner block takes the outside ice and dust too. Where a window ends at a corner of the building, its outside ice and dust wrap round onto the open side of its last block.
- The effects on the other side of the glass show only faintly, about a third, from either side, desert dust too.
- New fog and frost textures: fine beads and round drops in the mist; feathery frost fronds with curling barbs in place of the checkered pattern. Nothing is drawn in straight lines.
- Desert dust in the clean colour of the sand, as bright as the sand round it, with no grey blotches or dark specks; its drifts deepen in shade toward the bottom of the window under a crest that catches the light.
- Fog, frost, ice, dust and drops are lit by the game's own light and shaded by their face's direction like the blocks round them: nothing on the glass glows in the dark, and frost is as white as the snow beside it rather than grey like a mob's side.
- Breath fogs the glass where you look, from no further than 0.8 blocks away.
- Tumbleweeds of curving twigs, lit from above.
- Outside ice that covers a window is cheaper to draw.
- Outside ice lies thicker along the frame of the window, thinning out raggedly a few pixels in.
- New pixels come in smoothly: outside ice and desert dust grow on steadily instead of a pixel at a time, ash and torch soot darken without steps, pixels melting into a new frame or ice's next stage take 3 seconds instead of 1.5, and drops of condensation gather and their trails fade without steps. Without a shader pack, faint patches of fog and frost fade in from nothing too.

**Fixes**
- Glass with a block standing against it inside, such as a furnace, counts as part of the window it is in, so it ices over outside with the rest.
- Clouds and falling snow no longer show on top of frost or outside ice in front of them.
- With shader packs, fog and frost are lit like the light coming through the glass rather than dark grey, their layers no longer flicker against each other, and outside ice shows through fogged glass.
- Frost no longer thins out suddenly each time it grows into its next stage, and the last pixels of a melt into a new frame or ice's next stage no longer jump at its end.

## 1.1.5

**New**
- **Desert dust:** in deserts, a barely visible film of sandy dust covers the outside of windows, with a deposit along the bottom whose edge rises in sharp drifts, a little more at night. Red in badlands.
- **Torch soot:** a torch smudges the underside of the block over it, half as much one block higher. It builds up over a torch put in and fades slowly once the torch is gone.
- **Tumbleweeds:** now and then one rolls across a desert or badlands (of twigs or of pale dry grass, a third slower by day), never turning up closer than 16 blocks, 0.6 to 1.3 blocks across. It hops as it rolls, falls off edges and bounces where it lands, knocks back off walls, and fades out once something holds it still. A hit breaks it up.
- **Roof windows:** glass in a roof or ceiling, with the sky above and a room below, fogs and frosts underneath and gathers ice and dust on top.
- **Axe marks:** an axe that knocks a shield aside, yours or another player's, leaves a gash across its front that fades as the shield comes back.
- New settings: roof windows, torch soot, axe marks, tumbleweeds and how often they come, and how much desert dust settles and how fast.
- Effects are drawn as far as the game draws the world (or a limit of your own), and nothing is drawn that is off screen. Far windows are drawn with less detail.
- Windows are found as soon as their chunk loads, so effects are there before you see them rather than turning up seconds later.
- Fog and frost changing shape, outside ice and torch soot fade in pixel by pixel instead of popping in.

**Fixes**
- A room under a glass roof counts as indoors, so the windows of a greenhouse fog up too.
- Outside ice always climbs from the bottom of the whole window, also on windows that come into view a block at a time.

## 1.1.4

**New**
- Snowballs that hit a raised shield leave a splat on its front, seen on other players' shields and on yours in third person.
- Settings redesigned: four pages (General, Timing, Climate, Performance) with a tooltip on each option. With Sodium 0.8+ (Minecraft 1.21.11 and up) they are a section of Sodium's video settings next to Sodium and Iris; otherwise Mod Menu opens a screen with the same pages.

## 1.1.2

- New logo.

## 1.1.1

**Fixes**
- Outside ice stays where it is when glass below or above it is broken or built, instead of jumping up or down.
- Glass broken and put back starts clear outside; the ice grows on it again only while it snows.
- Ice wraps round the side of a glass block next to a pane, so it runs on into the pane's ice without a gap.

## 1.1.0

**New**
- **Outside ice:** during snowfall, feathery frost grows on the outside of windows from the bottom up.
- **Ash:** fire chars the block it burns on and leaves soot on the blocks around it. It builds up and fades smoothly and runs across neighbouring blocks without seams.
- **Snowball marks:** round splats in six shapes on any block, wrapping round edges and corners, gone after about 20 seconds.
- **Lightning scorch:** a burn mark with glowing embers in six styles that follows the ground over steps, slabs, snow, fences and posts. On sand it melts into glass that shines under shader packs.
- Fog and frost show faintly from the other side of the glass.
- Windows keep their fog, frost and ice for five minutes after you walk away.

**Fixes**
- Marks vanish with the block they are on and never hang in the air.
- Snowball marks work point-blank and ignore grass and flowers.
- Every new effect has a toggle and timing sliders in Mod Menu.

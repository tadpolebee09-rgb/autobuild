# AutoBuild (Fabric, Minecraft 26.3)

A client-side mod that does two things:
1. **Saves a build to a file.** Select a build with a stick and it's saved as a `.abuild` file.
2. **Rebuilds it for you.** Load the file, right-click the ground with a stick to see a hologram, then it places the blocks from your inventory.

It places blocks with normal right-click packets, so it works in singleplayer and on servers **without** the server needing the mod. Only use it on servers that allow automation (your own servers are fine).

## Using it (hold a stick)

| Action | What it does |
|---|---|
| Left-click block | Set corner 1 |
| Right-click block | Set corner 2 (when no build is loaded) |
| `/ab save <name>` | Save the selection to `autobuilds/<name>.abuild` |
| `/ab load <name>` | Load a build (tab-complete works) |
| Right-click ground | Put the hologram there (centered on the spot) |
| `/ab rotate` | Turn it 90° |
| Sneak + right-click | Start building (again to pause or resume) |
| `/ab materials` | What you need vs. what you have |
| `/ab status` | Progress + any blocks it couldn't place and why |
| `/ab cancel`, `/ab unload`, `/ab list`, `/ab here`, `/ab speed 1-4`, `/ab folder` | Other controls |

**Sharing builds:** the files are in `.minecraft/autobuilds/`. Copy a `.abuild` file into a friend's `autobuilds` folder and they can `/ab load` it.

**Liquids:** water and lava source blocks in the build are placed automatically, as long as you're
holding a water or lava bucket. It empties the bucket onto each spot. It can't refill an empty
bucket (that needs aiming at a source), so bring a few — `/ab materials` tells you how many bucket-loads
the build needs. Only full source blocks are placed; flowing liquid isn't a real placeable block.
Waterlogged blocks (like waterlogged stairs) come out wet on their own — they don't need a bucket.

**While building:**
- It only places blocks within reach (4.5 blocks), so walk around the build as it goes. The action bar tells you what's missing or too far away.
- It builds the bottom layer first. Blocks that need support, like torches, wait until the support is there.
- It turns your camera to place stairs, logs, and similar blocks the right way, then puts it back when done.
- It pulls items from your main inventory into your hotbar automatically. In creative, it gives itself the blocks.
- It never breaks blocks. If something is in the way, `/ab status` lists it.

## Building the jar

This needs **Java 25** and internet access.

**Easiest (no setup):** push this folder to a GitHub repo. The included workflow builds it, and the jar appears under **Actions → latest run → Artifacts**.

**On your PC:** open the folder in IntelliJ IDEA and let it import the Gradle project. Then run the `build` task. You can also run `gradle wrapper --gradle-version 9.6.0` once and then `./gradlew build`. The jar ends up in `build/libs/`.

Install: put the jar and **Fabric API** into `.minecraft/mods` with Fabric Loader 0.19.5+ for 26.3.

## .abuild format

A gzipped JSON file:
```json
{ "format": 1, "name": "house", "author": "Misha", "size": [x, y, z],
  "palette": ["minecraft:oak_planks", "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"],
  "blocks": [x, y, z, paletteIndex,  x, y, z, paletteIndex, ...] }
```

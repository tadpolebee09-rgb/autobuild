package dev.autobuild;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A saved build. On disk it's a ".abuild" file: gzipped JSON with a block palette
 * and a flat list of [x, y, z, paletteIndex] numbers.
 */
public final class Blueprint {
	public static final String EXT = ".abuild";
	public static final int FORMAT = 1;
	public static final int MAX_BLOCKS = 500_000;

	public record Entry(int x, int y, int z, BlockState state) {}

	public final String name;
	public final int sizeX, sizeY, sizeZ;
	public final List<Entry> blocks;
	/** Blocks in the file that don't exist in this game version. */
	public final int unknownBlocks;

	public Blueprint(String name, int sizeX, int sizeY, int sizeZ, List<Entry> blocks, int unknownBlocks) {
		this.name = name;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.blocks = blocks;
		this.unknownBlocks = unknownBlocks;
	}

	/** Copies every non-air block between the two corners. */
	public static Blueprint capture(Level level, BlockPos a, BlockPos b, String name) {
		int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
		int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
		int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());
		List<Entry> list = new ArrayList<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int y = minY; y <= maxY; y++) {
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					p.set(x, y, z);
					BlockState s = level.getBlockState(p);
					if (s.isAir()) continue;
					list.add(new Entry(x - minX, y - minY, z - minZ, s));
					if (list.size() > MAX_BLOCKS) {
						throw new IllegalArgumentException("Selection has more than " + MAX_BLOCKS + " blocks");
					}
				}
			}
		}
		return new Blueprint(name, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, list, 0);
	}

	/** Returns a copy turned clockwise by 90 degrees {@code turns} times. */
	public Blueprint rotated(int turns) {
		turns = ((turns % 4) + 4) % 4;
		Blueprint bp = this;
		for (int i = 0; i < turns; i++) bp = bp.rotateOnce();
		return bp;
	}

	private Blueprint rotateOnce() {
		List<Entry> list = new ArrayList<>(blocks.size());
		for (Entry e : blocks) {
			// Same maths vanilla structures use for CLOCKWISE_90: (x, z) -> (-z, x), shifted back to >= 0.
			int nx = (sizeZ - 1) - e.z();
			int nz = e.x();
			list.add(new Entry(nx, e.y(), nz, e.state().rotate(Rotation.CLOCKWISE_90)));
		}
		return new Blueprint(name, sizeZ, sizeY, sizeX, list, unknownBlocks);
	}

	/** Items needed to build the whole thing. */
	public Map<Item, Integer> materials() {
		Map<Item, Integer> map = new HashMap<>();
		for (Entry e : blocks) {
			if (BlockStates.isAutoPart(e.state())) continue;
			Item item = e.state().getBlock().asItem();
			if (item == Items.AIR) continue;
			map.merge(item, BlockStates.itemCost(e.state()), Integer::sum);
		}
		return map;
	}

	public void save(Path file, String author) throws IOException {
		Map<String, Integer> palette = new LinkedHashMap<>();
		JsonArray data = new JsonArray();
		for (Entry e : blocks) {
			String key = BlockStates.write(e.state());
			Integer idx = palette.get(key);
			if (idx == null) {
				idx = palette.size();
				palette.put(key, idx);
			}
			data.add(e.x());
			data.add(e.y());
			data.add(e.z());
			data.add(idx);
		}
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("name", name);
		root.addProperty("author", author);
		root.addProperty("created", System.currentTimeMillis());
		JsonArray size = new JsonArray();
		size.add(sizeX);
		size.add(sizeY);
		size.add(sizeZ);
		root.add("size", size);
		JsonArray pal = new JsonArray();
		palette.keySet().forEach(pal::add);
		root.add("palette", pal);
		root.add("blocks", data);

		Files.createDirectories(file.getParent());
		try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
			w.write(root.toString());
		}
	}

	public static Blueprint load(Path file) throws IOException {
		JsonObject root;
		try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
			root = JsonParser.parseReader(r).getAsJsonObject();
		}
		int format = root.has("format") ? root.get("format").getAsInt() : 1;
		if (format > FORMAT) throw new IOException("File was made by a newer AutoBuild (format " + format + ")");

		String name = root.has("name") ? root.get("name").getAsString() : file.getFileName().toString();
		JsonArray size = root.getAsJsonArray("size");
		JsonArray pal = root.getAsJsonArray("palette");
		JsonArray data = root.getAsJsonArray("blocks");

		List<BlockState> palette = new ArrayList<>(pal.size());
		for (JsonElement el : pal) palette.add(BlockStates.read(el.getAsString()));

		List<Entry> list = new ArrayList<>(data.size() / 4);
		int unknown = 0;
		for (int i = 0; i + 3 < data.size(); i += 4) {
			BlockState s = palette.get(data.get(i + 3).getAsInt());
			if (s == null) {
				unknown++;
				continue;
			}
			list.add(new Entry(data.get(i).getAsInt(), data.get(i + 1).getAsInt(), data.get(i + 2).getAsInt(), s));
		}
		return new Blueprint(name, size.get(0).getAsInt(), size.get(1).getAsInt(), size.get(2).getAsInt(), list, unknown);
	}
}

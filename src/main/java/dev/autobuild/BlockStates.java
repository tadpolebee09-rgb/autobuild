package dev.autobuild;

import java.util.Collection;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Converts block states to/from text like "minecraft:oak_stairs[facing=east,half=bottom]". */
public final class BlockStates {
	/**
	 * Properties that are decided by HOW you place a block (look direction, clicked face).
	 * Everything else (fence connections, stair shape, waterlogged, powered...) is worked out
	 * by the game from neighbours, so we don't try to match it.
	 */
	private static final Set<String> PLACEMENT_PROPS = Set.of(
			"facing", "axis", "half", "rotation", "face", "hinge", "orientation",
			"attachment", "hanging", "vertical_direction");

	private BlockStates() {}

	public static String write(BlockState state) {
		StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		Collection<Property<?>> props = state.getProperties();
		if (!props.isEmpty()) {
			sb.append('[');
			boolean first = true;
			for (Property<?> p : props) {
				if (!first) sb.append(',');
				first = false;
				sb.append(p.getName()).append('=').append(valueName(state, p));
			}
			sb.append(']');
		}
		return sb.toString();
	}

	/** Returns null if the block id doesn't exist in this Minecraft version. */
	public static BlockState read(String text) {
		String id = text;
		String propPart = null;
		int bracket = text.indexOf('[');
		if (bracket >= 0) {
			id = text.substring(0, bracket);
			int end = text.endsWith("]") ? text.length() - 1 : text.length();
			propPart = text.substring(bracket + 1, end);
		}
		Identifier key = Identifier.tryParse(id.trim());
		if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return null;
		Block block = BuiltInRegistries.BLOCK.getValue(key);
		BlockState state = block.defaultBlockState();
		if (propPart != null && !propPart.isEmpty()) {
			for (String kv : propPart.split(",")) {
				int eq = kv.indexOf('=');
				if (eq < 0) continue;
				Property<?> p = block.getStateDefinition().getProperty(kv.substring(0, eq).trim());
				if (p != null) state = with(state, p, kv.substring(eq + 1).trim());
			}
		}
		return state;
	}

	/** True if the world block already counts as "built correctly". */
	public static boolean matches(BlockState current, BlockState target) {
		if (current.getBlock() != target.getBlock()) return false;
		for (Property<?> p : target.getProperties()) {
			String name = p.getName();
			boolean relevant = PLACEMENT_PROPS.contains(name)
					|| (name.equals("type") && target.getBlock() instanceof SlabBlock);
			if (relevant && !current.getValue(p).equals(target.getValue(p))) return false;
		}
		return true;
	}

	/** Top half of doors/tall flowers, head of beds: these appear on their own when the other half is placed. */
	public static boolean isAutoPart(BlockState s) {
		if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
				&& s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		return s.hasProperty(BlockStateProperties.BED_PART)
				&& s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
	}

	/** Double slabs are placed as a bottom slab first, then topped up. */
	public static BlockState stage(BlockState current, BlockState target) {
		if (target.getBlock() instanceof SlabBlock
				&& target.getValue(SlabBlock.TYPE) == SlabType.DOUBLE
				&& current.getBlock() != target.getBlock()) {
			return target.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
		}
		return target;
	}

	/** How many items this block costs (double slab = 2). */
	public static int itemCost(BlockState s) {
		if (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) return 2;
		return 1;
	}

	private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
		return p.getName(s.getValue(p));
	}

	private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
		return p.getValue(value).map(v -> s.setValue(p, v)).orElse(s);
	}
}

package dev.autobuild;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Maps liquid source blocks to the bucket that places them.
 * Only full source blocks count — flowing liquid can't be placed and is left out.
 */
public final class Liquids {
	private Liquids() {}

	/**
	 * The bucket item that would create this block, or null if it isn't a placeable liquid source.
	 * Handles both standalone water/lava blocks and waterlogged blocks are NOT included here
	 * (those place via the block itself, carrying their waterlogged state).
	 */
	public static Item bucketFor(BlockState state) {
		// Only treat it as a liquid if the block itself is the liquid (not a waterlogged solid).
		if (state.getBlock().asItem() != Items.AIR) return null;
		FluidState fluid = state.getFluidState();
		if (fluid.isEmpty() || !fluid.isSource()) return null;
		if (fluid.getType() == Fluids.WATER) return Items.WATER_BUCKET;
		if (fluid.getType() == Fluids.LAVA) return Items.LAVA_BUCKET;
		return null;
	}

	public static boolean isLiquid(BlockState state) {
		return bucketFor(state) != null;
	}
}

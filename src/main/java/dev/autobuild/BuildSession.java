package dev.autobuild;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One build placed in the world: shows the hologram, and when started, places blocks
 * from the player's inventory the same way a player would (normal right-click packets).
 */
public final class BuildSession {
	public enum State { PREVIEW, BUILDING, PAUSED, DONE }

	private static final int MAX_ATTEMPTS = 4;
	private static final int RETRY_DELAY_TICKS = 40;
	private static final int HOLOGRAM_RANGE = 48;
	private static final int HOLOGRAM_MAX = 3000;
	private static final Direction[] FACE_ORDER = {
			Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private static final class Target {
		final BlockPos pos;
		final BlockState state;
		int attempts;
		long retryAt;
		boolean done;
		boolean gaveUp;

		Target(BlockPos pos, BlockState state) {
			this.pos = pos;
			this.state = state;
		}
	}

	/** How to place one block: which way to look and where to click. {@code liquid} means empty a bucket. */
	private record Plan(Target target, BlockState desired, Item item, float yaw, float pitch, BlockHitResult hit, boolean liquid) {}

	public final Blueprint blueprint;
	public final BlockPos origin;
	private final List<Target> targets = new ArrayList<>();
	private State state = State.PREVIEW;
	private int speed = 1;

	private long tick;
	private int swapCooldown;
	private Plan pending;
	private boolean placingNow;
	private float savedYaw, savedPitch;
	private int placed;
	private final Map<Item, Integer> missingNow = new HashMap<>();
	private int blockedNow, outOfReachNow;

	public BuildSession(Blueprint blueprint, BlockPos origin) {
		this.blueprint = blueprint;
		this.origin = origin;
		List<Blueprint.Entry> sorted = new ArrayList<>(blueprint.blocks);
		sorted.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
				: a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));
		for (Blueprint.Entry e : sorted) {
			if (BlockStates.isAutoPart(e.state())) continue;
			Item bucket = Liquids.bucketFor(e.state()); // water/lava source -> the bucket that places it
			if (e.state().getBlock().asItem() == Items.AIR && bucket == null) continue; // fire, portals, flowing liquid...
			targets.add(new Target(origin.offset(e.x(), e.y(), e.z()), e.state()));
		}
	}

	public State state() { return state; }
	public boolean isPlacingNow() { return placingNow; }
	public void setSpeed(int speed) { this.speed = Math.max(1, Math.min(4, speed)); }

	public void start(LocalPlayer player) {
		if (state == State.PREVIEW || state == State.PAUSED) {
			savedYaw = player.getYRot();
			savedPitch = player.getXRot();
			state = State.BUILDING;
			for (Target t : targets) if (t.gaveUp) { t.gaveUp = false; t.attempts = 0; }
		}
	}

	public void pause(LocalPlayer player) {
		if (state == State.BUILDING) {
			state = State.PAUSED;
			pending = null;
			restoreView(player);
		}
	}

	private void restoreView(LocalPlayer player) {
		if (player == null) return;
		player.setYRot(savedYaw);
		player.setXRot(savedPitch);
	}

	// ------------------------------------------------------------------ ticking

	/** Start of client tick: pick the next block, get its item into the hotbar, turn to face the right way. */
	public void onStartTick(Minecraft mc) {
		tick++;
		pending = null;
		if (state != State.BUILDING) return;
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || mc.gameMode == null) return;
		if (player.containerMenu != player.inventoryMenu) return; // don't click while a chest/menu is open
		if (swapCooldown > 0) { swapCooldown--; return; }

		Plan plan = chooseNext(mc, player, level, true);
		if (plan == null) return;

		Inventory inv = player.getInventory();
		int slot = findSlot(inv, plan.item());
		if (slot < 0) {
			if (!player.getAbilities().instabuild) return;
			// Creative: spawn the item straight into a hotbar slot.
			int work = workSlot(inv);
			ItemStack stack = new ItemStack(plan.item(), 64);
			inv.setItem(work, stack.copy());
			mc.gameMode.handleCreativeModeItemAdd(stack, 36 + work);
			swapCooldown = 1;
			return;
		}
		if (slot >= 9) {
			// Item is in the main inventory: swap it into a hotbar slot, place next tick.
			if (player.containerMenu != player.inventoryMenu) return;
			mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, workSlot(inv),
					ContainerInput.SWAP, player);
			swapCooldown = 1;
			return;
		}
		inv.setSelectedSlot(slot);
		player.setYRot(plan.yaw());
		player.setXRot(plan.pitch());
		pending = plan;
	}

	/** End of client tick: the rotation has been sent to the server by now, so place. */
	public void onEndTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null) return;

		if (state == State.BUILDING && pending != null && mc.gameMode != null) {
			boolean wasLiquid = pending.liquid();
			place(mc, player, pending);
			Item item = pending.item();
			// Extra blocks this tick if they use the same item and the same look direction.
			// Never batch liquids: each bucket empties and has to be refilled/re-aimed.
			for (int i = 1; i < speed && !wasLiquid; i++) {
				Plan extra = chooseNext(mc, player, level, false);
				if (extra == null || extra.item() != item
						|| player.getMainHandItem().getItem() != item) break;
				place(mc, player, extra);
			}
			pending = null;
		}

		if (state == State.BUILDING && tick % 10 == 0) {
			refreshDone(level);
			if (remaining() == 0) {
				state = State.DONE;
				restoreView(player);
				player.sendSystemMessage(Component.literal("[AutoBuild] Finished! Placed " + placed + " blocks.")
						.withStyle(ChatFormatting.GREEN));
				int gaveUp = (int) targets.stream().filter(t -> t.gaveUp && !t.done).count();
				if (gaveUp > 0) {
					player.sendSystemMessage(Component.literal("[AutoBuild] " + gaveUp
							+ " blocks couldn't be placed correctly (see /ab status).").withStyle(ChatFormatting.YELLOW));
				}
			} else {
				player.sendOverlayMessage(statusLine());
			}
		}

		if (state != State.DONE) drawHologram(level, player);
	}

	private void place(Minecraft mc, LocalPlayer player, Plan plan) {
		placingNow = true;
		try {
			if (plan.liquid()) {
				// A bucket empties with a plain right-click while aimed at the spot.
				mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
			} else {
				mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, plan.hit());
			}
		} finally {
			placingNow = false;
		}
		Target t = plan.target();
		t.attempts++;
		BlockState now = mc.level.getBlockState(t.pos);
		if (BlockStates.matches(now, t.state)) {
			t.done = true;
			placed++;
		} else if (!plan.liquid() && BlockStates.matches(now, plan.desired())) {
			placed++; // first half of a double slab
		} else {
			t.retryAt = tick + (plan.liquid() ? 3 : 5);
			if (t.attempts >= MAX_ATTEMPTS) t.gaveUp = true;
		}
	}

	// ------------------------------------------------------------------ choosing blocks

	private Plan chooseNext(Minecraft mc, LocalPlayer player, ClientLevel level, boolean countStats) {
		if (countStats) {
			missingNow.clear();
			blockedNow = 0;
			outOfReachNow = 0;
		}
		Map<Item, Integer> have = inventoryCounts(player.getInventory());
		boolean creative = player.getAbilities().instabuild;
		Vec3 eye = player.getEyePosition();
		double reach = player.blockInteractionRange();
		AABB playerBox = player.getBoundingBox();

		List<Target> candidates = new ArrayList<>();
		for (Target t : targets) {
			if (t.done || t.gaveUp) continue;
			if (!level.isLoaded(t.pos)) continue;
			BlockState current = level.getBlockState(t.pos);
			if (BlockStates.matches(current, t.state)) { t.done = true; continue; }
			Item bucket = Liquids.bucketFor(t.state);
			BlockState desired = BlockStates.stage(current, t.state);
			// A liquid source needs an empty/replaceable spot; a solid block same as before.
			boolean fillingSlab = current.getBlock() == t.state.getBlock() && desired == t.state;
			if (!current.canBeReplaced() && !fillingSlab) {
				if (countStats) blockedNow++;
				continue;
			}
			Item item = bucket != null ? bucket : t.state.getBlock().asItem();
			// Buckets are never free, even in creative, so we always need to actually hold one.
			if ((bucket != null || !creative) && have.getOrDefault(item, 0) <= 0) {
				if (countStats) missingNow.merge(item, 1, Integer::sum);
				continue;
			}
			if (distanceToBlock(eye, t.pos) > reach) {
				if (countStats) outOfReachNow++;
				continue;
			}
			if (playerBox.intersects(new AABB(t.pos))) continue;
			if (t.retryAt > tick) continue;
			candidates.add(t);
		}
		// Lowest layer first, then closest.
		candidates.sort((a, b) -> a.pos.getY() != b.pos.getY() ? Integer.compare(a.pos.getY(), b.pos.getY())
				: Double.compare(distanceToBlock(eye, a.pos), distanceToBlock(eye, b.pos)));

		int tried = 0;
		for (Target t : candidates) {
			if (++tried > 24) break;
			BlockState current = level.getBlockState(t.pos);
			Item bucket = Liquids.bucketFor(t.state);
			if (bucket != null) {
				Plan plan = planLiquid(player, level, t, bucket);
				if (plan != null) return plan;
				if (countStats) t.retryAt = tick + RETRY_DELAY_TICKS;
				continue;
			}
			BlockState desired = BlockStates.stage(current, t.state);
			Item item = t.state.getBlock().asItem();
			ItemStack stack = new ItemStack(item);
			// On the extra same-tick placements we can't turn any more, so only accept the current view.
			Plan plan = countStats ? planPlacement(player, level, t, desired, item, stack)
					: planAtCurrentView(player, level, t, desired, item, stack);
			if (plan != null) return plan;
			if (countStats) t.retryAt = tick + RETRY_DELAY_TICKS; // can't place yet (needs support, etc.)
		}
		return null;
	}

	/**
	 * A bucket empties its liquid onto whatever block your crosshair is on. We aim at the target
	 * position from wherever the player is, using a hit whose block is the target itself, so the
	 * source lands exactly there.
	 */
	private Plan planLiquid(LocalPlayer player, ClientLevel level, Target t, Item bucket) {
		Vec3 eye = player.getEyePosition();
		Vec3 centre = new Vec3(t.pos.getX() + 0.5, t.pos.getY() + 0.5, t.pos.getZ() + 0.5);
		// Look straight at the block centre.
		double dx = centre.x - eye.x, dy = centre.y - eye.y, dz = centre.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
		// Face pointing back toward the player, so the fluid is placed into this block, not the next one.
		double ax = eye.x - centre.x, ay = eye.y - centre.y, az = eye.z - centre.z;
		Direction face;
		if (Math.abs(ax) >= Math.abs(ay) && Math.abs(ax) >= Math.abs(az)) { face = ax >= 0 ? Direction.EAST : Direction.WEST; }
		else if (Math.abs(ay) >= Math.abs(az)) { face = ay >= 0 ? Direction.UP : Direction.DOWN; }
		else { face = az >= 0 ? Direction.SOUTH : Direction.NORTH; }
		BlockHitResult hit = new BlockHitResult(centre, face, t.pos, false);
		return new Plan(t, t.state, bucket, yaw, pitch, hit, true);
	}

	/**
	 * Tries look directions and click spots in a simulation (same code the game runs when you
	 * right-click) until one would produce the block we want.
	 */
	private Plan planPlacement(LocalPlayer player, ClientLevel level, Target t, BlockState desired, Item item, ItemStack stack) {
		float origYaw = player.getYRot(), origPitch = player.getXRot();
		try {
			if (t.attempts == 0) {
				Plan p = tryView(player, level, t, desired, item, stack, origYaw, origPitch);
				if (p != null) return p;
			}
			boolean wallVariant = stack.getItem() instanceof BlockItem bi && bi.getBlock() != desired.getBlock();
			float[] pitches = wallVariant ? new float[] {0f, -90f, 90f} : new float[] {90f, 0f, -90f};
			int shift = t.attempts; // after a failed try, change which way we look first
			float baseYaw = Math.round(origYaw / 90f) * 90f;
			for (int pi = 0; pi < 3; pi++) {
				float pitch = pitches[(pi + shift) % 3];
				for (int yi = 0; yi < 4; yi++) {
					float yaw = baseYaw + 90f * yi;
					Plan p = tryView(player, level, t, desired, item, stack, yaw, pitch);
					if (p != null) return p;
				}
			}
			return null;
		} finally {
			player.setYRot(origYaw);
			player.setXRot(origPitch);
		}
	}

	private Plan planAtCurrentView(LocalPlayer player, ClientLevel level, Target t, BlockState desired, Item item, ItemStack stack) {
		float yaw = player.getYRot(), pitch = player.getXRot();
		try {
			return tryView(player, level, t, desired, item, stack, yaw, pitch);
		} finally {
			player.setYRot(yaw);
			player.setXRot(pitch);
		}
	}

	private Plan tryView(LocalPlayer player, ClientLevel level, Target t, BlockState desired, Item item,
			ItemStack stack, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setXRot(pitch);
		for (Direction face : FACE_ORDER) {
			for (Vec3 hitVec : hitPoints(t.pos, face)) {
				BlockHitResult hit = new BlockHitResult(hitVec, face, t.pos, false);
				BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
				if (!ctx.canPlace()) continue;
				BlockState result = desired.getBlock().getStateForPlacement(ctx);
				if (result == null || !result.canSurvive(level, t.pos)) continue;
				if (BlockStates.matches(result, desired)) {
					return new Plan(t, desired, item, yaw, pitch, hit, false);
				}
			}
		}
		return null;
	}

	/** Centre of the face plus four off-centre spots (these decide top/bottom halves and door hinges). */
	private static Vec3[] hitPoints(BlockPos pos, Direction face) {
		double cx = pos.getX() + 0.5 + face.getStepX() * 0.5;
		double cy = pos.getY() + 0.5 + face.getStepY() * 0.5;
		double cz = pos.getZ() + 0.5 + face.getStepZ() * 0.5;
		Vec3[] out = new Vec3[5];
		out[0] = new Vec3(cx, cy, cz);
		int i = 1;
		for (double a : new double[] {-0.25, 0.25}) {
			for (double b : new double[] {-0.25, 0.25}) {
				switch (face.getAxis()) {
					case Y -> out[i++] = new Vec3(cx + a, cy, cz + b);
					case X -> out[i++] = new Vec3(cx, cy + a, cz + b);
					case Z -> out[i++] = new Vec3(cx + b, cy + a, cz);
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ inventory

	private static Map<Item, Integer> inventoryCounts(Inventory inv) {
		Map<Item, Integer> map = new HashMap<>();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) map.merge(s.getItem(), s.getCount(), Integer::sum);
		}
		return map;
	}

	/** Hotbar slot first (0-8), then main inventory (9-35). -1 if none. */
	private static int findSlot(Inventory inv, Item item) {
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && s.getItem() == item) return i;
		}
		return -1;
	}

	/** Hotbar slot we're allowed to swap items into: an empty one, otherwise the last slot. */
	private static int workSlot(Inventory inv) {
		for (int i = 0; i < 9; i++) if (inv.getItem(i).isEmpty()) return i;
		return 8;
	}

	private static double distanceToBlock(Vec3 eye, BlockPos pos) {
		double dx = Math.max(pos.getX() - eye.x, Math.max(0, eye.x - (pos.getX() + 1)));
		double dy = Math.max(pos.getY() - eye.y, Math.max(0, eye.y - (pos.getY() + 1)));
		double dz = Math.max(pos.getZ() - eye.z, Math.max(0, eye.z - (pos.getZ() + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ------------------------------------------------------------------ status

	private void refreshDone(ClientLevel level) {
		for (Target t : targets) {
			if (!t.done && level.isLoaded(t.pos) && BlockStates.matches(level.getBlockState(t.pos), t.state)) t.done = true;
		}
	}

	public int remaining() {
		int n = 0;
		for (Target t : targets) if (!t.done && !t.gaveUp) n++;
		return n;
	}

	public int total() { return targets.size(); }

	public Component statusLine() {
		int done = 0;
		for (Target t : targets) if (t.done) done++;
		StringBuilder sb = new StringBuilder("AutoBuild ").append(done).append('/').append(targets.size());
		if (!missingNow.isEmpty()) {
			int missing = missingNow.values().stream().mapToInt(Integer::intValue).sum();
			sb.append(" · missing items for ").append(missing);
		}
		if (outOfReachNow > 0) sb.append(" · walk closer (").append(outOfReachNow).append(" out of reach)");
		if (blockedNow > 0) sb.append(" · ").append(blockedNow).append(" blocked");
		return Component.literal(sb.toString()).withStyle(ChatFormatting.AQUA);
	}

	/** Items still needed for the unfinished part of the build. */
	public Map<Item, Integer> stillNeeded() {
		Map<Item, Integer> map = new HashMap<>();
		for (Target t : targets) {
			if (t.done) continue;
			Item bucket = Liquids.bucketFor(t.state);
			if (bucket != null) map.merge(bucket, 1, Integer::sum);
			else map.merge(t.state.getBlock().asItem(), BlockStates.itemCost(t.state), Integer::sum);
		}
		return map;
	}

	public List<String> problemReport(ClientLevel level, int max) {
		List<String> out = new ArrayList<>();
		for (Target t : targets) {
			if (t.done) continue;
			BlockState cur = level.getBlockState(t.pos);
			String why = null;
			if (t.gaveUp) why = "gave up after " + t.attempts + " tries";
			else if (!cur.canBeReplaced() && cur.getBlock() != t.state.getBlock()) why = "something is in the way";
			else if (cur.getBlock() == t.state.getBlock()) why = "wrong direction (break it and it will be re-placed)";
			if (why != null) {
				out.add(t.pos.toShortString() + " " + BlockStates.write(t.state) + ": " + why);
				if (out.size() >= max) break;
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ hologram

	private void drawHologram(ClientLevel level, LocalPlayer player) {
		// Block markers live ~80 ticks, so refresh them a bit before they fade.
		if (tick % 70 == 1) {
			int shown = 0;
			double rangeSq = HOLOGRAM_RANGE * HOLOGRAM_RANGE;
			for (Target t : targets) {
				if (t.done) continue;
				if (t.pos.distToCenterSqr(player.position()) > rangeSq) continue;
				if (!level.isLoaded(t.pos) || BlockStates.matches(level.getBlockState(t.pos), t.state)) continue;
				level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK_MARKER, t.state),
						t.pos.getX() + 0.5, t.pos.getY() + 0.5, t.pos.getZ() + 0.5, 0, 0, 0);
				if (++shown >= HOLOGRAM_MAX) break;
			}
		}
		if (tick % 10 == 0) drawOutline(level);
	}

	private void drawOutline(ClientLevel level) {
		int color = state == State.PREVIEW ? 0x33CCFF : state == State.PAUSED ? 0xFFAA00 : 0x55FF55;
		DustParticleOptions dust = new DustParticleOptions(color, 1.0f);
		double x0 = origin.getX(), y0 = origin.getY(), z0 = origin.getZ();
		double x1 = x0 + blueprint.sizeX, y1 = y0 + blueprint.sizeY, z1 = z0 + blueprint.sizeZ;
		double[][] corners = {{x0, y0, z0}, {x1, y0, z0}, {x0, y1, z0}, {x0, y0, z1},
				{x1, y1, z0}, {x1, y0, z1}, {x0, y1, z1}, {x1, y1, z1}};
		int[][] edges = {{0, 1}, {0, 2}, {0, 3}, {1, 4}, {1, 5}, {2, 4}, {2, 6}, {3, 5}, {3, 6}, {4, 7}, {5, 7}, {6, 7}};
		for (int[] e : edges) {
			double[] a = corners[e[0]], b = corners[e[1]];
			double len = Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1]) + Math.abs(b[2] - a[2]);
			int steps = (int) Math.min(200, Math.max(1, len * 2));
			for (int s = 0; s <= steps; s++) {
				double f = (double) s / steps;
				level.addParticle(dust, a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, 0, 0, 0);
			}
		}
	}
}

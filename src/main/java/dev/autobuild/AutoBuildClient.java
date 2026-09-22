package dev.autobuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class AutoBuildClient implements ClientModInitializer {
	private static Path buildsDir;

	// Selection (for saving)
	private static BlockPos pos1, pos2;

	// Loaded build (for placing)
	private static Blueprint loaded;
	private static int turns;
	private static BlockPos anchor;
	private static BuildSession session;
	private static int speed = 1;
	private static long tick;

	@Override
	public void onInitializeClient() {
		buildsDir = FabricLoader.getInstance().getGameDir().resolve("autobuilds");
		try {
			Files.createDirectories(buildsDir);
		} catch (IOException ignored) {
		}

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> registerCommands(dispatcher));

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
			if (session != null && session.isPlacingNow()) return InteractionResult.PASS;
			if (!player.getMainHandItem().is(Items.STICK)) return InteractionResult.PASS;
			onStickRightClick(hit, player.isShiftKeyDown());
			return InteractionResult.FAIL; // don't send the click to the server
		});

		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!level.isClientSide() || !player.getMainHandItem().is(Items.STICK)) return InteractionResult.PASS;
			if (!pos.equals(pos1)) {
				pos1 = pos.immutable();
				msg("Corner 1 set to " + pos1.toShortString() + sizeHint(), ChatFormatting.AQUA);
			}
			return InteractionResult.FAIL; // don't break the block
		});

		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (session != null) session.onStartTick(mc);
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			tick++;
			if (mc.level == null) {
				session = null; // left the world
				return;
			}
			if (session != null) session.onEndTick(mc);
			if (session == null && pos1 != null && pos2 != null && tick % 10 == 0) drawSelection(mc.level);
		});
	}

	// ------------------------------------------------------------------ stick

	private static void onStickRightClick(BlockHitResult hit, boolean sneaking) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) return;

		if (loaded == null) {
			pos2 = hit.getBlockPos().immutable();
			msg("Corner 2 set to " + pos2.toShortString() + sizeHint(), ChatFormatting.AQUA);
			return;
		}

		if (session != null && (session.state() == BuildSession.State.BUILDING || session.state() == BuildSession.State.PAUSED)) {
			if (sneaking) togglePause(player);
			else msg("Already building. Sneak + right-click to pause/resume, /ab cancel to stop.", ChatFormatting.GRAY);
			return;
		}

		if (sneaking && session != null && session.state() == BuildSession.State.PREVIEW) {
			startBuild(player);
			return;
		}

		anchor = hit.getBlockPos().relative(hit.getDirection()).immutable();
		rebuildPreview();
		msg("Hologram placed. Sneak + right-click (or /ab start) to build, /ab rotate to turn it.", ChatFormatting.AQUA);
	}

	private static void rebuildPreview() {
		if (loaded == null || anchor == null) return;
		Blueprint bp = loaded.rotated(turns);
		// Centre the footprint on the spot you clicked.
		BlockPos origin = anchor.offset(-bp.sizeX / 2, 0, -bp.sizeZ / 2);
		session = new BuildSession(bp, origin);
		session.setSpeed(speed);
	}

	private static void startBuild(LocalPlayer player) {
		if (session == null) {
			msg("Place the hologram first: hold a stick and right-click the ground.", ChatFormatting.RED);
			return;
		}
		session.start(player);
		msg("Building " + session.blueprint.name + " (" + session.total() + " blocks). Stay close; it only places what you can reach.",
				ChatFormatting.GREEN);
	}

	private static void togglePause(LocalPlayer player) {
		if (session == null) return;
		if (session.state() == BuildSession.State.BUILDING) {
			session.pause(player);
			msg("Paused.", ChatFormatting.GOLD);
		} else {
			session.start(player);
			msg("Resumed.", ChatFormatting.GREEN);
		}
	}

	// ------------------------------------------------------------------ commands

	private static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(ClientCommands.literal("ab")
				.executes(c -> help())
				.then(ClientCommands.literal("help").executes(c -> help()))
				.then(ClientCommands.literal("pos1").executes(c -> setCorner(c, 1)))
				.then(ClientCommands.literal("pos2").executes(c -> setCorner(c, 2)))
				.then(ClientCommands.literal("save")
						.then(ClientCommands.argument("name", StringArgumentType.word()).executes(AutoBuildClient::save)))
				.then(ClientCommands.literal("list").executes(c -> list()))
				.then(ClientCommands.literal("load")
						.then(ClientCommands.argument("name", StringArgumentType.word())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(buildNames(), b))
								.executes(AutoBuildClient::load)))
				.then(ClientCommands.literal("unload").executes(c -> unload()))
				.then(ClientCommands.literal("rotate").executes(c -> rotate()))
				.then(ClientCommands.literal("here").executes(AutoBuildClient::here))
				.then(ClientCommands.literal("start").executes(c -> {
					startBuild(c.getSource().getPlayer());
					return 1;
				}))
				.then(ClientCommands.literal("pause").executes(c -> {
					if (session != null) togglePause(c.getSource().getPlayer());
					return 1;
				}))
				.then(ClientCommands.literal("cancel").executes(c -> {
					if (session != null) session.pause(c.getSource().getPlayer());
					session = null;
					anchor = null;
					msg("Build cancelled.", ChatFormatting.GOLD);
					return 1;
				}))
				.then(ClientCommands.literal("materials").executes(AutoBuildClient::materials))
				.then(ClientCommands.literal("status").executes(AutoBuildClient::status))
				.then(ClientCommands.literal("speed")
						.then(ClientCommands.argument("blocksPerTick", IntegerArgumentType.integer(1, 4)).executes(c -> {
							speed = IntegerArgumentType.getInteger(c, "blocksPerTick");
							if (session != null) session.setSpeed(speed);
							msg("Speed set to " + speed + " block(s) per tick.", ChatFormatting.AQUA);
							return 1;
						})))
				.then(ClientCommands.literal("folder").executes(c -> {
					msg("Build files go in: " + buildsDir.toAbsolutePath(), ChatFormatting.AQUA);
					return 1;
				})));
	}

	private static int help() {
		String[] lines = {
				"AutoBuild — hold a STICK:",
				" Save a build: left-click corner 1, right-click corner 2, then /ab save <name>",
				" Build one: /ab load <name>, right-click ground = hologram, sneak+right-click = start",
				" /ab rotate · /ab here · /ab pause · /ab cancel · /ab materials · /ab status",
				" /ab list · /ab unload · /ab speed <1-4> · /ab folder",
				" Share builds by copying the .abuild files in your autobuilds folder."};
		for (String l : lines) msg(l, ChatFormatting.AQUA);
		return 1;
	}

	private static int setCorner(CommandContext<FabricClientCommandSource> c, int which) {
		Minecraft mc = Minecraft.getInstance();
		BlockPos p = mc.hitResult instanceof BlockHitResult bh && mc.hitResult.getType() == HitResult.Type.BLOCK
				? bh.getBlockPos().immutable()
				: c.getSource().getPlayer().blockPosition();
		if (which == 1) pos1 = p;
		else pos2 = p;
		msg("Corner " + which + " set to " + p.toShortString() + sizeHint(), ChatFormatting.AQUA);
		return 1;
	}

	private static int save(CommandContext<FabricClientCommandSource> c) {
		if (pos1 == null || pos2 == null) {
			msg("Set both corners first (stick left-click / right-click, or /ab pos1 and /ab pos2).", ChatFormatting.RED);
			return 0;
		}
		String name = cleanName(StringArgumentType.getString(c, "name"));
		ClientLevel level = c.getSource().getLevel();
		try {
			Blueprint bp = Blueprint.capture(level, pos1, pos2, name);
			if (bp.blocks.isEmpty()) {
				msg("That area is empty.", ChatFormatting.RED);
				return 0;
			}
			Path file = buildsDir.resolve(name + Blueprint.EXT);
			bp.save(file, c.getSource().getPlayer().getName().getString());
			msg("Saved " + bp.blocks.size() + " blocks (" + bp.sizeX + "x" + bp.sizeY + "x" + bp.sizeZ + ") to "
					+ file.getFileName(), ChatFormatting.GREEN);
			return 1;
		} catch (IllegalArgumentException | IOException e) {
			msg("Couldn't save: " + e.getMessage(), ChatFormatting.RED);
			return 0;
		}
	}

	private static int list() {
		List<String> names = buildNames();
		if (names.isEmpty()) msg("No builds yet. Files go in " + buildsDir.toAbsolutePath(), ChatFormatting.GRAY);
		else msg("Builds: " + String.join(", ", names), ChatFormatting.AQUA);
		return 1;
	}

	private static int load(CommandContext<FabricClientCommandSource> c) {
		String name = StringArgumentType.getString(c, "name");
		Path file = buildsDir.resolve(name.endsWith(Blueprint.EXT) ? name : name + Blueprint.EXT);
		if (!Files.exists(file)) {
			msg("No file called " + file.getFileName() + " in " + buildsDir.toAbsolutePath(), ChatFormatting.RED);
			return 0;
		}
		try {
			loaded = Blueprint.load(file);
		} catch (Exception e) {
			msg("Couldn't read " + file.getFileName() + ": " + e.getMessage(), ChatFormatting.RED);
			return 0;
		}
		turns = 0;
		anchor = null;
		session = null;
		msg("Loaded " + loaded.name + ": " + loaded.blocks.size() + " blocks, " + loaded.sizeX + "x" + loaded.sizeY + "x"
				+ loaded.sizeZ + ". Hold a stick and right-click the ground to place the hologram.", ChatFormatting.GREEN);
		if (loaded.unknownBlocks > 0) {
			msg(loaded.unknownBlocks + " blocks don't exist in this version and will be skipped.", ChatFormatting.YELLOW);
		}
		return 1;
	}

	private static int unload() {
		loaded = null;
		session = null;
		anchor = null;
		msg("Unloaded. Stick right-click now sets corner 2 again.", ChatFormatting.GRAY);
		return 1;
	}

	private static int rotate() {
		if (loaded == null) {
			msg("Load a build first.", ChatFormatting.RED);
			return 0;
		}
		if (session != null && session.state() != BuildSession.State.PREVIEW) {
			msg("Can't rotate after building has started. /ab cancel first.", ChatFormatting.RED);
			return 0;
		}
		turns = (turns + 1) % 4;
		rebuildPreview();
		msg("Rotated to " + (turns * 90) + "°", ChatFormatting.AQUA);
		return 1;
	}

	private static int here(CommandContext<FabricClientCommandSource> c) {
		if (loaded == null) {
			msg("Load a build first.", ChatFormatting.RED);
			return 0;
		}
		if (session != null && session.state() != BuildSession.State.PREVIEW && session.state() != BuildSession.State.DONE) {
			msg("Already building. /ab cancel first.", ChatFormatting.RED);
			return 0;
		}
		anchor = c.getSource().getPlayer().blockPosition();
		rebuildPreview();
		msg("Hologram placed at your feet.", ChatFormatting.AQUA);
		return 1;
	}

	private static int materials(CommandContext<FabricClientCommandSource> c) {
		if (loaded == null) {
			msg("Load a build first.", ChatFormatting.RED);
			return 0;
		}
		Map<Item, Integer> need = session != null ? session.stillNeeded() : loaded.materials();
		LocalPlayer player = c.getSource().getPlayer();
		List<Map.Entry<Item, Integer>> rows = new ArrayList<>(need.entrySet());
		rows.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
		msg("Materials (need / have):", ChatFormatting.AQUA);
		for (Map.Entry<Item, Integer> e : rows) {
			int have = 0;
			for (int i = 0; i < 36; i++) {
				ItemStack s = player.getInventory().getItem(i);
				if (s.getItem() == e.getKey()) have += s.getCount();
			}
			String id = BuiltInRegistries.ITEM.getKey(e.getKey()).getPath();
			msg(" " + id + ": " + e.getValue() + " / " + have, have >= e.getValue() ? ChatFormatting.GREEN : ChatFormatting.RED);
		}
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> c) {
		if (session == null) {
			msg(loaded == null ? "Nothing loaded." : "Loaded " + loaded.name + ", no hologram placed yet.", ChatFormatting.GRAY);
			return 1;
		}
		c.getSource().sendFeedback(session.statusLine());
		for (String line : session.problemReport(c.getSource().getLevel(), 10)) msg(" " + line, ChatFormatting.YELLOW);
		return 1;
	}

	// ------------------------------------------------------------------ helpers

	private static List<String> buildNames() {
		List<String> out = new ArrayList<>();
		try (Stream<Path> files = Files.list(buildsDir)) {
			files.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(Blueprint.EXT))
					.map(n -> n.substring(0, n.length() - Blueprint.EXT.length()))
					.sorted(Comparator.naturalOrder())
					.forEach(out::add);
		} catch (IOException ignored) {
		}
		return out;
	}

	private static String cleanName(String s) {
		return s.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	private static String sizeHint() {
		if (pos1 == null || pos2 == null) return "";
		int x = Math.abs(pos1.getX() - pos2.getX()) + 1;
		int y = Math.abs(pos1.getY() - pos2.getY()) + 1;
		int z = Math.abs(pos1.getZ() - pos2.getZ()) + 1;
		return " (selection " + x + "x" + y + "x" + z + ")";
	}

	private static void msg(String text, ChatFormatting color) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) p.sendSystemMessage(Component.literal(text).withStyle(color));
	}

	private static void drawSelection(ClientLevel level) {
		DustParticleOptions dust = new DustParticleOptions(0xFFFF55, 0.8f);
		double x0 = Math.min(pos1.getX(), pos2.getX()), x1 = Math.max(pos1.getX(), pos2.getX()) + 1;
		double y0 = Math.min(pos1.getY(), pos2.getY()), y1 = Math.max(pos1.getY(), pos2.getY()) + 1;
		double z0 = Math.min(pos1.getZ(), pos2.getZ()), z1 = Math.max(pos1.getZ(), pos2.getZ()) + 1;
		double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
		for (double y : ys) for (double z : zs) line(level, dust, x0, y, z, x1, y, z);
		for (double x : xs) for (double z : zs) line(level, dust, x, y0, z, x, y1, z);
		for (double x : xs) for (double y : ys) line(level, dust, x, y, z0, x, y, z1);
	}

	private static void line(ClientLevel level, DustParticleOptions dust, double ax, double ay, double az, double bx, double by, double bz) {
		double len = Math.abs(bx - ax) + Math.abs(by - ay) + Math.abs(bz - az);
		int steps = (int) Math.min(200, Math.max(1, len * 2));
		for (int s = 0; s <= steps; s++) {
			double f = (double) s / steps;
			level.addParticle(dust, ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f, 0, 0, 0);
		}
	}
}

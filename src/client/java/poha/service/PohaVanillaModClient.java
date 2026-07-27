package poha.service;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only entrypoint. This file MUST live under src/client/java, not
 * src/main/java — that's what gives it access to client-only classes like
 * KeyMapping, InputConstants, and Minecraft (via ClientTickEvents).
 */
public class PohaVanillaModClient implements ClientModInitializer {

	// 26.2 requires a KeyMapping.Category object rather than a raw string.
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(PohaVanillaMod.id("movement"));

	// Bound to G: steps through the hardcoded sequence of build primitives
	// you write yourself in buildSequence() below. One press announces the
	// next step, the next press runs it.
	private KeyMapping sequenceKey;

	// New: bound to H. Prints your current position, then shift-places
	// prismarine 1 block in front of you and 2 blocks to your left,
	// at your current Y-level.
	private KeyMapping placeKey;

	private java.util.List<BuildAction> sequence = null;
	private int sequenceIndex = -1;
	private boolean sequenceRunning = false;

	// Pending shift-place state. A single tick of pre-sneaking isn't reliably
	// enough on a real server: this is a known vanilla quirk where sneak-place
	// on a container can race the server's own reconciliation of your sneak
	// state, even for human players manually shift-right-clicking. Holding
	// sneak for a longer window before clicking (mirroring what a person does
	// by hand — sneak, pause, then click) makes it reliable. ~10 ticks (half
	// a second) is a comfortable margin.
	private static final int SNEAK_WARMUP_TICKS = 10;

	private int placeCountdown = -1;
	private BlockPos pendingClickPos;
	private Direction pendingClickFace;
	private int pendingHotbarSlot;
	private int pendingPreviousSlot;
	private boolean pendingWasSneaking;

	// Auto-mine-after-place state. Note this drives the same
	// start/continue/stopDestroyBlock loop vanilla runs while you hold left
	// click — it does not skip or shorten the real mining time for the
	// block/tool combination, it just automates holding the button down.
	private BlockPos miningPos;
	private int miningPreviousSlot;
	private boolean mining = false;

	// H toggles this on/off. While true, a new place-then-break cycle is
	// kicked off automatically as soon as the previous one finishes.
	private boolean looping = false;

	@Override
	public void onInitializeClient() {
		sequenceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.poha.run_sequence",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				CATEGORY
		));

		placeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.poha.place_offset",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) return;

			while (sequenceKey.consumeClick()) {
				if (sequence == null) {
					sequence = buildSequence();
					sequenceIndex = 0;
					sequenceRunning = false;
					if (sequence.isEmpty()) {
						client.player.sendSystemMessage(Component.literal("buildSequence() is empty — nothing to run."));
						sequence = null;
					} else {
						client.player.sendSystemMessage(Component.literal("Next: " + sequence.get(0).describe()));
					}
				} else if (!sequenceRunning) {
					BuildAction action = sequence.get(sequenceIndex);
					action.begin(client.player, client.player.level(), client.options);
					sequenceRunning = true;
				}
			}
			if (sequence != null && sequenceRunning) {
				tickSequence(client.player, client.player.level(), client.options);
			}

			if (placeCountdown > 0) {
				placeCountdown--;
			} else if (placeCountdown == 0) {
				performPendingPlacement(client.player, client.options.keyShift);
				placeCountdown = -1;
			}

			if (mining) {
				tickMining(client.player);
			}

			while (placeKey.consumeClick()) {
				looping = !looping;
				if (looping) {
					client.player.sendSystemMessage(Component.literal("Loop started. Press H again to stop."));
					beginOffsetPlacement(client.player, client.options.keyShift);
				} else {
					client.player.sendSystemMessage(Component.literal("Loop stopping after this cycle."));
				}
			}
		});
	}

	// Hotbar is 0-indexed internally; slot 6 as a player counts it (1-9) is
	// index 5.
	private static final int SOURCE_HOTBAR_SLOT = 5;

	// EDIT THIS to build your own structure. One press of G announces the
	// next step; the next press runs it. See the primitive helpers below —
	// place()/breakBlock() act on the block directly in front of you, using
	// your *current* real facing at the moment each step actually runs
	// (so turns you queue earlier really do affect later moves/placements).
	private java.util.List<BuildAction> buildSequence() {
		java.util.List<BuildAction> steps = new java.util.ArrayList<>();

		// Example: a 3-block straight line, then turn right and add one more
		// block off to the side — just to show the primitives composing.
		// lookForPlace() here demonstrates real-raycast targeting for the
		// first block; the rest fall back to "in front of me" since there's
		// no lookForPlace() call right before them.
		steps.add(lookForPlace());
		steps.add(place());
		steps.add(moveForward(1));
		steps.add(place());
		steps.add(moveForward(1));
		steps.add(place());
		steps.add(turnRight());
		steps.add(moveForward(1));
		steps.add(place());

		return steps;
	}

	// --- Primitive helpers — build your sequence out of these ---
	private BuildAction moveForward(int blocks) { return new MoveAction(MoveDir.FORWARD, blocks); }
	private BuildAction moveBack(int blocks)    { return new MoveAction(MoveDir.BACK, blocks); }
	private BuildAction moveLeft(int blocks)    { return new MoveAction(MoveDir.LEFT, blocks); }
	private BuildAction moveRight(int blocks)   { return new MoveAction(MoveDir.RIGHT, blocks); }
	private BuildAction turnLeft()              { return new TurnAction(false); }
	private BuildAction turnRight()             { return new TurnAction(true); }
	private BuildAction place()                 { return new PlaceAction(); }
	private BuildAction breakBlock()             { return new BreakAction(); }
	private BuildAction lookForPlace()          { return new LookForPlaceAction(); }

	// Set by lookForPlace(), consumed by the very next place()/breakBlock().
	// If null when place()/breakBlock() runs, they fall back to the plain
	// "1 block directly in front of me" behavior instead.
	private BlockHitResult lookedAtHit = null;

	private void tickSequence(LocalPlayer player, Level level, net.minecraft.client.Options options) {
		BuildAction action = sequence.get(sequenceIndex);
		boolean done = action.tick(player, level, options);
		if (!done) return;

		sequenceRunning = false;
		sequenceIndex++;
		if (sequenceIndex >= sequence.size()) {
			player.sendSystemMessage(Component.literal("Sequence complete!"));
			sequence = null;
			sequenceIndex = -1;
		} else {
			player.sendSystemMessage(Component.literal("Next: " + sequence.get(sequenceIndex).describe()));
		}
	}

	// One step in a sequence. Each is driven by real, measured player
	// state — actual distance traveled, actual yaw, actual block state —
	// checked every tick, rather than a fixed timer alone.
	private abstract static class BuildAction {
		void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {}
		abstract boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options);
		abstract String describe();
	}

	private enum MoveDir { FORWARD, BACK, LEFT, RIGHT }

	// Real movement: holds the actual Forward/Back/Strafe-Left/Strafe-Right
	// keybinding until the player has genuinely moved the requested
	// distance, measured from their real position — not a guessed tick count.
	private class MoveAction extends BuildAction {
		final MoveDir dir;
		final double blocks;
		Vec3 startPos;
		int ticks = 0;
		static final int TIMEOUT_TICKS_PER_BLOCK = 30;

		MoveAction(MoveDir dir, int blocks) {
			this.dir = dir;
			this.blocks = blocks;
		}

		@Override
		void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			startPos = player.position();
			keyFor(options).setDown(true);
		}

		@Override
		boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			ticks++;
			double traveled = player.position().distanceTo(startPos);
			boolean arrived = traveled >= blocks - 0.15;
			boolean timedOut = ticks >= TIMEOUT_TICKS_PER_BLOCK * Math.max(1, (int) blocks);
			if (arrived || timedOut) {
				keyFor(options).setDown(false);
				if (timedOut && !arrived) {
					player.sendSystemMessage(Component.literal(
							"Didn't confirm moving the full distance — continuing anyway."));
				}
				return true;
			}
			return false;
		}

		private KeyMapping keyFor(net.minecraft.client.Options options) {
			switch (dir) {
				case FORWARD: return options.keyUp;
				case BACK: return options.keyDown;
				case LEFT: return options.keyLeft;
				case RIGHT: return options.keyRight;
				default: throw new IllegalStateException("unreachable");
			}
		}

		@Override
		String describe() {
			return "move " + dir.name().toLowerCase() + " " + (int) blocks + " block(s)";
		}
	}

	// Real camera rotation: turns exactly 90 degrees to the next cardinal
	// direction, smoothly over a few ticks, using the player's actual current
	// facing (player.getDirection(), derived from real yaw) so it's always
	// correct even if earlier turns already happened.
	private class TurnAction extends BuildAction {
		final boolean turnRight;
		float startYaw;
		float targetYaw;
		int ticks = 0;
		static final int DURATION_TICKS = 5;

		TurnAction(boolean turnRight) {
			this.turnRight = turnRight;
		}

		@Override
		void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			Direction current = player.getDirection();
			Direction target = turnRight ? current.getClockWise() : current.getCounterClockWise();
			startYaw = player.getYRot();
			targetYaw = startYaw + Mth.wrapDegrees(target.toYRot() - startYaw);
		}

		@Override
		boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			ticks++;
			float t = Math.min(1f, ticks / (float) DURATION_TICKS);
			player.setYRot(startYaw + (targetYaw - startYaw) * t);
			if (t >= 1f) {
				player.setYRot(targetYaw);
				return true;
			}
			return false;
		}

		@Override
		String describe() {
			return "turn " + (turnRight ? "right" : "left");
		}
	}

	// Finds a real spot to place/break at (currently: an existing solid
	// neighbor near the block directly in front of you, at foot level — same
	// default the fallback path uses), then actually turns the camera —
	// yaw AND pitch — to genuinely look at it, smoothly over a few ticks.
	// Only once the camera has really moved there does it confirm with a
	// raycast, the same way the game itself decides what your crosshair is
	// on. This is what makes it "simulate my view" rather than just reading
	// whatever direction happened to already be pointed.
	private class LookForPlaceAction extends BuildAction {
		private static final double REACH = 4.5; // typical survival block reach
		private static final int DURATION_TICKS = 8;

		BlockHitResult candidate;
		float startYaw, startPitch, targetYaw, targetPitch;
		int ticks = 0;

		@Override
		void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			BlockPos front = player.blockPosition().relative(player.getDirection(), 1);
			candidate = findClickableFace(level, front);
			if (candidate == null) {
				player.sendSystemMessage(Component.literal("Nothing nearby to look at."));
				return;
			}

			Vec3 eyePos = player.getEyePosition(1.0f);
			Vec3 lookAt = candidate.getLocation(); // exact point on the face we found
			Vec3 diff = lookAt.subtract(eyePos);
			double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
			float rawYaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
			float rawPitch = (float) -Math.toDegrees(Math.atan2(diff.y, horizontalDist));

			startYaw = player.getYRot();
			startPitch = player.getXRot();
			targetYaw = startYaw + Mth.wrapDegrees(rawYaw - startYaw);
			targetPitch = rawPitch;
		}

		@Override
		boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			if (candidate == null) {
				return true; // begin() already messaged why
			}

			ticks++;
			float t = Math.min(1f, ticks / (float) DURATION_TICKS);
			player.setYRot(startYaw + (targetYaw - startYaw) * t);
			player.setXRot(startPitch + (targetPitch - startPitch) * t);
			if (t < 1f) {
				return false;
			}
			player.setYRot(targetYaw);
			player.setXRot(targetPitch);

			// Camera has genuinely moved there now — confirm with a real
			// raycast, same as the game deciding what your crosshair is on.
			Vec3 eyePos = player.getEyePosition(1.0f);
			Vec3 look = player.getViewVector(1.0f);
			Vec3 endPos = eyePos.add(look.scale(REACH));
			net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(
					eyePos, endPos,
					net.minecraft.world.level.ClipContext.Block.OUTLINE,
					net.minecraft.world.level.ClipContext.Fluid.NONE,
					player);
			BlockHitResult hit = level.clip(ctx);

			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
				player.sendSystemMessage(Component.literal("Turned to look, but nothing in range to target."));
				lookedAtHit = null;
			} else {
				lookedAtHit = hit;
				player.sendSystemMessage(Component.literal("Targeting " + hit.getBlockPos()));
			}
			return true;
		}

		@Override
		String describe() {
			return "look for a block to target";
		}
	}

	// Places one block. If lookForPlace() was called just before this step,
	// uses that real raycast result (placing exactly where it targeted).
	// Otherwise falls back to the block directly in front of the player's
	// current facing.
	private class PlaceAction extends BuildAction {
		@Override
		boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			BlockHitResult hitResult;
			if (lookedAtHit != null) {
				hitResult = lookedAtHit;
				lookedAtHit = null; // one-shot: consumed here
			} else {
				BlockPos target = player.blockPosition().relative(player.getDirection(), 1);
				hitResult = findClickableFace(level, target);
				if (hitResult == null) {
					player.sendSystemMessage(Component.literal("No adjacent block to place " + target + " against."));
					return true;
				}
			}

			BlockPos placeTarget = hitResult.getBlockPos().relative(hitResult.getDirection());
			if (!level.getBlockState(placeTarget).canBeReplaced()) {
				return true; // already occupied — nothing to do
			}

			ItemStack stack = player.getInventory().getItem(SOURCE_HOTBAR_SLOT);
			if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
				player.sendSystemMessage(Component.literal("Hotbar slot 6 doesn't have a placeable block in it."));
				return true;
			}

			int previousSlot = player.getInventory().getSelectedSlot();
			player.getInventory().setSelectedSlot(SOURCE_HOTBAR_SLOT);
			net.minecraft.client.Minecraft.getInstance().gameMode
					.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
			player.getInventory().setSelectedSlot(previousSlot);
			return true;
		}

		@Override
		String describe() {
			return "place a block" + (lookedAtHit != null ? " where I'm looking" : " in front of me");
		}
	}

	// Breaks a block. If lookForPlace() was called just before this step,
	// breaks whatever that raycast actually targeted. Otherwise falls back
	// to the block directly in front of the player's current facing.
	private class BreakAction extends BuildAction {
		BlockPos target;

		@Override
		void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			if (lookedAtHit != null) {
				target = lookedAtHit.getBlockPos();
				lookedAtHit = null; // one-shot: consumed here
			} else {
				target = player.blockPosition().relative(player.getDirection(), 1);
			}
			net.minecraft.client.Minecraft.getInstance().gameMode.startDestroyBlock(target, Direction.UP);
		}

		@Override
		boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
			if (level.getBlockState(target).isAir()) {
				net.minecraft.client.Minecraft.getInstance().gameMode.stopDestroyBlock();
				return true;
			}

			int toolSlot = -1;
			for (int i = 0; i < 9; i++) {
				ItemStack stack = player.getInventory().getItem(i);
				if (!stack.isEmpty() && stack.isCorrectToolForDrops(level.getBlockState(target))) {
					toolSlot = i;
					break;
				}
			}
			int previousSlot = player.getInventory().getSelectedSlot();
			if (toolSlot != -1) {
				player.getInventory().setSelectedSlot(toolSlot);
			}
			net.minecraft.client.Minecraft.getInstance().gameMode.continueDestroyBlock(target, Direction.UP);
			player.getInventory().setSelectedSlot(previousSlot);
			return false;
		}

		@Override
		String describe() {
			return "break the block in front of me";
		}
	}

	// Finds any existing, non-replaceable neighbor of pos to click against —
	// checking straight down first (the common "place on the floor/on top of
	// what I just placed" case), then the four side faces.
	private BlockHitResult findClickableFace(Level level, BlockPos pos) {
		BlockPos below = pos.below();
		if (!level.getBlockState(below).canBeReplaced()) {
			return buildHitResult(below, Direction.UP);
		}
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.relative(d);
			if (!level.getBlockState(neighbor).canBeReplaced()) {
				return buildHitResult(neighbor, d.getOpposite());
			}
		}
		return null;
	}

	private BlockHitResult buildHitResult(BlockPos clickedPos, Direction clickedFace) {
		Vec3 hitVec = Vec3.atCenterOf(clickedPos).add(
				clickedFace.getStepX() * 0.5, clickedFace.getStepY() * 0.5, clickedFace.getStepZ() * 0.5);
		return new BlockHitResult(hitVec, clickedFace, clickedPos, false);
	}

	private void beginOffsetPlacement(LocalPlayer player, KeyMapping sneakKey) {
		if (placeCountdown >= 0 || mining) return; // already mid-sequence

		Level level = player.level();
		BlockPos origin = player.blockPosition();

		// Facing direction, and "left" relative to that facing.
		Direction facing = player.getDirection();
		Direction left = facing.getCounterClockWise();

		BlockPos target = origin.relative(facing, 1).relative(left, 2);

		// Whatever's in hotbar slot 6 — no auto-give here, if it's empty or
		// not a placeable block, say so and stop.
		int hotbarSlot = SOURCE_HOTBAR_SLOT;
		ItemStack slotStack = player.getInventory().getItem(hotbarSlot);
		if (slotStack.isEmpty() || !(slotStack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
			player.sendSystemMessage(Component.literal("Hotbar slot 6 doesn't have a placeable block in it."));
			looping = false;
			return;
		}

		if (!level.getBlockState(target).canBeReplaced()) {
			player.sendSystemMessage(Component.literal("Target position " + target + " is already occupied."));
			looping = false;
			return;
		}

		// The whole point of shift-placing here is that we're clicking on a
		// hopper: a normal right-click would open its inventory screen instead
		// of placing a block, so we specifically require a hopper below the
		// target and rely on sneaking to suppress that screen.
		BlockPos below = target.below();
		if (!level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.HOPPER)) {
			player.sendSystemMessage(
					Component.literal("No hopper at " + below + " (below the target position) to place on."));
			looping = false;
			return;
		}

		// Stage everything, drive the actual Sneak keybinding now, and place
		// once the countdown runs out. Driving the keybinding (rather than
		// player.setShiftKeyDown directly) is what actually makes the client
		// crouch, animate, and sync sneaking to the server — the same reason
		// client.options.keyUp.setDown() is what drives auto-walk above.
		pendingClickPos = below;
		pendingClickFace = Direction.UP;
		pendingHotbarSlot = hotbarSlot;
		pendingPreviousSlot = player.getInventory().getSelectedSlot();
		pendingWasSneaking = sneakKey.isDown();

		player.getInventory().setSelectedSlot(hotbarSlot);
		sneakKey.setDown(true);
		placeCountdown = SNEAK_WARMUP_TICKS;
	}

	private void performPendingPlacement(LocalPlayer player, KeyMapping sneakKey) {
		Vec3 hitVec = Vec3.atCenterOf(pendingClickPos).add(
				pendingClickFace.getStepX() * 0.5, pendingClickFace.getStepY() * 0.5, pendingClickFace.getStepZ() * 0.5);
		BlockHitResult hitResult = new BlockHitResult(hitVec, pendingClickFace, pendingClickPos, false);

		net.minecraft.client.Minecraft.getInstance().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);

		sneakKey.setDown(pendingWasSneaking);

		// Immediately start mining the block we just placed, with a pickaxe
		// from the hotbar if we have one.
		BlockPos placedPos = pendingClickPos.relative(pendingClickFace);
		// PickaxeItem no longer exists as of the tool-component rewrite
		// (1.21.5+): tools are now data-driven, so check directly whether the
		// stack is a correct tool for the specific block we just placed.
		Level level = player.level();
		int pickaxeSlot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && stack.isCorrectToolForDrops(level.getBlockState(placedPos))) {
				pickaxeSlot = i;
				break;
			}
		}

		if (pickaxeSlot == -1) {
			player.sendSystemMessage(Component.literal("Placed it, but you don't have a pickaxe to break it with."));
			player.getInventory().setSelectedSlot(pendingPreviousSlot);
			looping = false;
			return;
		}

		// Stop before grinding the tool down further if it's already below
		// 80% durability remaining.
		ItemStack toolStack = player.getInventory().getItem(pickaxeSlot);
		if (toolStack.isDamageableItem()) {
			int maxDamage = toolStack.getMaxDamage();
			int damage = toolStack.getDamageValue();
			double remainingFraction = maxDamage > 0 ? 1.0 - (damage / (double) maxDamage) : 1.0;
			if (remainingFraction < 0.8) {
				player.sendSystemMessage(Component.literal(
						"Placed it, but your tool is below 80% durability (" +
								Math.round(remainingFraction * 100) + "%) — stopping the loop."));
				player.getInventory().setSelectedSlot(pendingPreviousSlot);
				looping = false;
				return;
			}
		}

		miningPos = placedPos;
		miningPreviousSlot = pendingPreviousSlot;
		player.getInventory().setSelectedSlot(pickaxeSlot);
		net.minecraft.client.Minecraft.getInstance().gameMode.startDestroyBlock(miningPos, Direction.UP);
		mining = true;
	}

	private void tickMining(LocalPlayer player) {
		Level level = player.level();

		if (level.getBlockState(miningPos).isAir()) {
			// Done — the block broke since the last tick.
			net.minecraft.client.Minecraft.getInstance().gameMode.stopDestroyBlock();
			player.getInventory().setSelectedSlot(miningPreviousSlot);
			mining = false;

			if (looping) {
				beginOffsetPlacement(player, net.minecraft.client.Minecraft.getInstance().options.keyShift);
			}
			return;
		}

		boolean stillOnTarget = net.minecraft.client.Minecraft.getInstance().gameMode
				.continueDestroyBlock(miningPos, Direction.UP);
		if (!stillOnTarget) {
			// Target/tool changed underneath us for some reason; restart the
			// break rather than leaving it stuck.
			net.minecraft.client.Minecraft.getInstance().gameMode.startDestroyBlock(miningPos, Direction.UP);
		}
	}
}
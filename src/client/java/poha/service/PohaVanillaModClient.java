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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

	private KeyMapping moveKey;
	private int ticksRemaining = -1;

	// New: bound to H. Prints your current position, then shift-places
	// prismarine 1 block in front of you and 2 blocks to your left,
	// at your current Y-level.
	private KeyMapping placeKey;

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
		moveKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.poha.move_forward",
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

			// consumeClick() is the current name for the old wasPressed()/wasKeyPressed().
			while (moveKey.consumeClick()) {
				ticksRemaining = 25;
			}

			// Mojang mappings name the forward-movement binding "keyUp",
			// and forcing its state is done with setDown(), not setPressed().
			if (ticksRemaining > 0) {
				client.options.keyUp.setDown(true);
				ticksRemaining--;
			} else if (ticksRemaining == 0) {
				client.options.keyUp.setDown(false);
				ticksRemaining = -1;
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

	private void beginOffsetPlacement(LocalPlayer player, KeyMapping sneakKey) {
		if (placeCountdown >= 0 || mining) return; // already mid-sequence

		Level level = player.level();
		BlockPos origin = player.blockPosition();

		// Print current coordinates.
		player.sendSystemMessage(
				Component.literal("Current pos: " + origin.getX() + ", " + origin.getY() + ", " + origin.getZ())
		);

		// Facing direction, and "left" relative to that facing.
		Direction facing = player.getDirection();
		Direction left = facing.getCounterClockWise();

		BlockPos target = origin.relative(facing, 1).relative(left, 2);

		// Do we actually have redstone ore? No auto-give here — if it's
		// missing, say so and stop.
		int hotbarSlot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.is(Items.REDSTONE_ORE)) {
				hotbarSlot = i;
				break;
			}
		}
		if (hotbarSlot == -1) {
			player.sendSystemMessage(Component.literal("You don't have any redstone ore."));
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
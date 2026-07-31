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

    // Bound to H. Prints your current position, then shift-places
    // prismarine 1 block in front of you and 2 blocks to your left,
    // at your current Y-level.
    private KeyMapping placeKey;

    // Bound to J: toggles infinite place-and-break loop in front of you.
    private KeyMapping placeBreakKey;

    private java.util.List<BuildAction> sequence = null;
    private int sequenceIndex = -1;
    private boolean sequenceRunning = false;

    // Tracks if the J-key place-and-break loop is running.
    private boolean jLooping = false;

    // Pending shift-place state.
    private static final int SNEAK_WARMUP_TICKS = 10;

    private int placeCountdown = -1;
    private BlockPos pendingClickPos;
    private Direction pendingClickFace;
    private int pendingHotbarSlot;
    private int pendingPreviousSlot;
    private boolean pendingWasSneaking;

    // Auto-mine-after-place state.
    private BlockPos miningPos;
    private int miningPreviousSlot;
    private boolean mining = false;

    // H toggles this on/off.
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

        placeBreakKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.poha.place_break_toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
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

            while (placeBreakKey.consumeClick()) {
                if (jLooping) {
                    // Stop toggle requested
                    jLooping = false;
                    client.player.sendSystemMessage(Component.literal("Place-and-break loop stopping after current block..."));
                } else if (sequenceRunning) {
                    client.player.sendSystemMessage(Component.literal("A sequence is already running!"));
                } else {
                    // Start toggle
                    jLooping = true;
                    runSingleAction(placeAndBreakLoop(-1), client.player, client.player.level(), client.options);
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

    // Hotbar is 0-indexed internally; slot 6 is index 5.
    private static final int SOURCE_HOTBAR_SLOT = 5;

    private java.util.List<BuildAction> buildSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();

        steps.add(lookForPlace());
        // Runs automatically as part of this sequence — no J press needed,
        // stops on its own after 5 cycles (or sooner if out of material).
        steps.add(placeAndBreakLoop(2));
        steps.add(moveForward(1));
        steps.add(moveBack(1));

        return steps;
    }

    // --- Primitive helpers ---
    private BuildAction moveForward(int blocks) { return new MoveAction(MoveDir.FORWARD, blocks); }
    private BuildAction moveBack(int blocks)    { return new MoveAction(MoveDir.BACK, blocks); }
    private BuildAction moveLeft(int blocks)    { return new MoveAction(MoveDir.LEFT, blocks); }
    private BuildAction moveRight(int blocks)   { return new MoveAction(MoveDir.RIGHT, blocks); }
    private BuildAction turnLeft()              { return new TurnAction(false); }
    private BuildAction turnRight()             { return new TurnAction(true); }
    private BuildAction place()                 { return new PlaceAction(); }
    private BuildAction breakBlock()            { return new BreakAction(); }
    private BuildAction lookForPlace()         { return new LookForPlaceAction(); }
    private BuildAction jumpForward()          { return new JumpForwardAction(); }
    private BuildAction placeAndBreakLoop(int cycles) { return new PlaceAndBreakLoopAction(cycles); }

    private BlockHitResult lookedAtHit = null;

    // Runs a single BuildAction through the same sequence machinery G uses,
    // as a one-step "sequence". Used by the J-key toggle so it doesn't need
    // its own separate execution path.
    private void runSingleAction(BuildAction action, LocalPlayer player, Level level, net.minecraft.client.Options options) {
        sequence = new java.util.ArrayList<>();
        sequence.add(action);
        sequenceIndex = 0;
        sequenceRunning = true;
        action.begin(player, level, options);
    }

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

    private abstract static class BuildAction {
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {}
        abstract boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options);
        abstract String describe();
    }

    private enum MoveDir { FORWARD, BACK, LEFT, RIGHT }

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

    private class LookForPlaceAction extends BuildAction {
        private static final double REACH = 4.5;
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
            Vec3 lookAt = candidate.getLocation();
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
                return true;
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

    private class JumpForwardAction extends BuildAction {
        private double startY;
        private Vec3 startPos;
        private int ticks = 0;
        private static final int TIMEOUT_TICKS = 40;

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            startY = player.getY();
            startPos = player.position();
            ticks = 0;

            options.keyUp.setDown(true);
            options.keyJump.setDown(true);
        }

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            ticks++;

            options.keyUp.setDown(true);

            if (player.onGround() && ticks < 10) {
                options.keyJump.setDown(true);
            } else {
                options.keyJump.setDown(false);
            }

            boolean gainedHeight = player.getY() >= startY + 0.8;
            boolean movedForward = player.position().subtract(startPos).horizontalDistance() >= 0.8;
            boolean landed = ticks > 5 && player.onGround();
            boolean timedOut = ticks >= TIMEOUT_TICKS;

            if ((landed && gainedHeight && movedForward) || timedOut) {
                options.keyJump.setDown(false);
                options.keyUp.setDown(false);

                if (timedOut && !gainedHeight) {
                    player.sendSystemMessage(Component.literal("Jump forward failed — wall blocked or missed ledge."));
                }
                return true;
            }

            return false;
        }

        @Override
        String describe() {
            return "jump forward up 1 block";
        }
    }

    private class PlaceAction extends BuildAction {
        private boolean backingUp = false;
        private Vec3 startPos = null;
        private static final int BACKUP_TIMEOUT_TICKS = 20;
        private int backupTicks = 0;

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            BlockHitResult hitResult;
            if (lookedAtHit != null) {
                hitResult = lookedAtHit;
                lookedAtHit = null;
            } else {
                BlockPos target = player.blockPosition().relative(player.getDirection(), 1);
                hitResult = findClickableFace(level, target);
                if (hitResult == null) {
                    player.sendSystemMessage(Component.literal("No adjacent block to place " + target + " against."));
                    cleanupBackup(options);
                    return true;
                }
            }

            BlockPos placeTarget = hitResult.getBlockPos().relative(hitResult.getDirection());

            net.minecraft.world.phys.AABB targetBox = new net.minecraft.world.phys.AABB(placeTarget);
            if (player.getBoundingBox().intersects(targetBox)) {
                if (!backingUp) {
                    backingUp = true;
                    backupTicks = 0;
                    startPos = player.position();
                    options.keyDown.setDown(true);
                }

                backupTicks++;
                boolean timedOut = backupTicks >= BACKUP_TIMEOUT_TICKS;
                boolean cleared = !player.getBoundingBox().intersects(targetBox);

                if (!cleared && !timedOut) {
                    return false;
                }

                cleanupBackup(options);

                if (timedOut) {
                    player.sendSystemMessage(Component.literal("Could not step back enough to clear place target."));
                    return true;
                }
            } else if (backingUp) {
                cleanupBackup(options);
            }

            if (!level.getBlockState(placeTarget).canBeReplaced()) {
                return true;
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

        private void cleanupBackup(net.minecraft.client.Options options) {
            if (backingUp) {
                options.keyDown.setDown(false);
                backingUp = false;
                startPos = null;
                backupTicks = 0;
            }
        }

        @Override
        String describe() {
            return "place a block" + (lookedAtHit != null ? " where I'm looking" : " in front of me");
        }
    }

    // Breaks a block. Only requires a matching tool if the block actually
    // needs one to drop anything (e.g. ores). Otherwise, prefers a Silk
    // Touch tool if one's available (glass et al. need Silk Touch — any
    // tier — to drop at all, even though no tier is strictly "required"),
    // and falls back to whatever's currently selected if not.
    private class BreakAction extends BuildAction {
        BlockPos target;

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            if (lookedAtHit != null) {
                target = lookedAtHit.getBlockPos();
                lookedAtHit = null;
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

            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(target);
            int toolSlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                    toolSlot = i;
                    break;
                }
            }
            if (toolSlot == -1 && !state.requiresCorrectToolForDrops()) {
                toolSlot = findSilkTouchSlot(player);
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

    // Toggleable action that continuously places and mines a block directly
    // in front of the player until jLooping is set to false or materials run out.
    // targetCycles < 0 means "run until jLooping goes false" (the J-key
    // toggle case — needs an external stop signal). targetCycles >= 0 means
    // "run exactly that many cycles, then stop on its own" — fully
    // self-contained, safe to drop into buildSequence() with no key press
    // involved at all.
    private class PlaceAndBreakLoopAction extends BuildAction {
        private enum Stage { PLACE, MINE }
        private Stage currentStage = Stage.PLACE;

        private final int targetCycles;
        private BlockPos targetPos;
        private int previousSlot;
        private int totalCycles = 0;

        PlaceAndBreakLoopAction(int targetCycles) {
            this.targetCycles = targetCycles;
        }

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            currentStage = Stage.PLACE;
            totalCycles = 0;
            if (targetCycles < 0) {
                player.sendSystemMessage(Component.literal("Place-and-break loop started. Press J again to stop."));
            } else {
                player.sendSystemMessage(Component.literal(
                        "Placing and breaking " + targetCycles + " block(s) automatically."));
            }
        }

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            switch (currentStage) {
                case PLACE:
                    boolean shouldStop = targetCycles < 0 ? !jLooping : totalCycles >= targetCycles;
                    if (shouldStop) {
                        player.sendSystemMessage(Component.literal("Done. Completed " + totalCycles + " cycle(s)."));
                        if (targetCycles < 0) {
                            jLooping = false; // tidy up the external flag if it drove this
                        }
                        return true;
                    }

                    if (!executePlace(player, level)) {
                        jLooping = false; // Stop loop on failure (out of blocks, etc.)
                        return true;
                    }
                    currentStage = Stage.MINE;
                    net.minecraft.client.Minecraft.getInstance().gameMode
                            .startDestroyBlock(targetPos, Direction.UP);
                    return false;

                case MINE:
                    if (level.getBlockState(targetPos).isAir()) {
                        net.minecraft.client.Minecraft.getInstance().gameMode.stopDestroyBlock();
                        player.getInventory().setSelectedSlot(previousSlot);

                        totalCycles++;
                        currentStage = Stage.PLACE;
                    } else {
                        net.minecraft.client.Minecraft.getInstance().gameMode
                                .continueDestroyBlock(targetPos, Direction.UP);
                    }
                    return false;
            }

            return false;
        }

        private boolean executePlace(LocalPlayer player, Level level) {
            BlockPos target = player.blockPosition().relative(player.getDirection(), 1);
            BlockHitResult hitResult = findClickableFace(level, target);
            if (hitResult == null) {
                player.sendSystemMessage(Component.literal("No valid block face to place against."));
                return false;
            }

            targetPos = hitResult.getBlockPos().relative(hitResult.getDirection());
            if (!level.getBlockState(targetPos).canBeReplaced()) {
                player.sendSystemMessage(Component.literal("Target block position is not clear."));
                return false;
            }

            ItemStack stack = player.getInventory().getItem(SOURCE_HOTBAR_SLOT);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                player.sendSystemMessage(Component.literal("Hotbar slot 6 doesn't have blocks remaining."));
                return false;
            }

            previousSlot = player.getInventory().getSelectedSlot();
            player.getInventory().setSelectedSlot(SOURCE_HOTBAR_SLOT);
            net.minecraft.client.Minecraft.getInstance().gameMode
                    .useItemOn(player, InteractionHand.MAIN_HAND, hitResult);

            int toolSlot = findBestTool(player, level, targetPos);
            if (toolSlot != -1) {
                player.getInventory().setSelectedSlot(toolSlot);
            }

            return true;
        }

        // Same fix as BreakAction: only require a correct-tier tool if the
        // block actually needs one; otherwise prefer Silk Touch if we have
        // it, so this doesn't misreport things like glass as unbreakable.
        private int findBestTool(LocalPlayer player, Level level, BlockPos pos) {
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            for (int i = 0; i < 9; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                    return i;
                }
            }
            if (!state.requiresCorrectToolForDrops()) {
                return findSilkTouchSlot(player);
            }
            return -1;
        }

        @Override
        String describe() {
            return "place and break loop";
        }
    }

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

    // Finds a hotbar item enchanted with Silk Touch — enchantments are
    // registry-driven data as of 1.20.5+, so this needs a Holder looked up
    // through the level's registry access rather than a plain constant.
    private int findSilkTouchSlot(LocalPlayer player) {
        net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> silkTouch;
        try {
            silkTouch = player.level().registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH);
        } catch (Exception e) {
            return -1; // registry lookup failed for some reason — just skip the preference
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getEnchantments().getLevel(silkTouch) > 0) {
                return i;
            }
        }
        return -1;
    }

    private void beginOffsetPlacement(LocalPlayer player, KeyMapping sneakKey) {
        if (placeCountdown >= 0 || mining) return;

        Level level = player.level();
        BlockPos origin = player.blockPosition();

        Direction facing = player.getDirection();
        Direction left = facing.getCounterClockWise();

        BlockPos target = origin.relative(facing, 1).relative(left, 2);

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

        BlockPos below = target.below();
        if (!level.getBlockState(below).is(net.minecraft.world.level.block.Blocks.HOPPER)) {
            player.sendSystemMessage(
                    Component.literal("No hopper at " + below + " (below the target position) to place on."));
            looping = false;
            return;
        }

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

        // Immediately start mining the block we just placed. Only search for
        // (and require) a specific tool if this block actually needs one to
        // drop anything — e.g. ores need the right pickaxe tier. Plenty of
        // blocks (glass, dirt, wood, ...) don't require any particular tool
        // at all: isCorrectToolForDrops() would return false for ALL of them
        // in that case (since none is "the" required tool), which isn't the
        // same as "unbreakable" — so we shouldn't refuse to mine there.
        BlockPos placedPos = pendingClickPos.relative(pendingClickFace);
        Level level = player.level();
        net.minecraft.world.level.block.state.BlockState placedState = level.getBlockState(placedPos);

        int pickaxeSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(placedState)) {
                pickaxeSlot = i;
                break;
            }
        }

        if (pickaxeSlot == -1 && placedState.requiresCorrectToolForDrops()) {
            // Genuinely needs a specific tool (like an ore) and we don't have
            // one — this is a real "can't do this" case.
            player.sendSystemMessage(Component.literal("Placed it, but you don't have a pickaxe to break it with."));
            player.getInventory().setSelectedSlot(pendingPreviousSlot);
            looping = false;
            return;
        }

        if (pickaxeSlot == -1) {
            // Block doesn't require a specific tool tier — but some blocks
            // (glass being the classic case) still drop nothing at all
            // unless mined with Silk Touch. Prefer a Silk Touch tool if we
            // have one, rather than just grabbing whatever's selected.
            int silkTouchSlot = findSilkTouchSlot(player);
            if (silkTouchSlot != -1) {
                pickaxeSlot = silkTouchSlot;
            }
        }

        if (pickaxeSlot != -1) {
            // Stop before grinding the tool down further if it's already
            // below 20% durability remaining.
            ItemStack toolStack = player.getInventory().getItem(pickaxeSlot);
            if (toolStack.isDamageableItem()) {
                int maxDamage = toolStack.getMaxDamage();
                int damage = toolStack.getDamageValue();
                double remainingFraction = maxDamage > 0 ? 1.0 - (damage / (double) maxDamage) : 1.0;
                if (remainingFraction < 0.2) {
                    player.sendSystemMessage(Component.literal(
                            "Placed it, but your tool is below 20% durability (" +
                                    Math.round(remainingFraction * 100) + "%) — stopping the loop."));
                    player.getInventory().setSelectedSlot(pendingPreviousSlot);
                    looping = false;
                    return;
                }
            }
            player.getInventory().setSelectedSlot(pickaxeSlot);
        }
        // else: no specific tool needed and no Silk Touch tool found — mine
        // with whatever's currently selected (or bare hands); it'll break,
        // it just may not drop anything, same as a real player punching it.

        miningPos = placedPos;
        miningPreviousSlot = pendingPreviousSlot;
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
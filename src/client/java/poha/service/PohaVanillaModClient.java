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

    // Bound to G: steps through the hardcoded sequence of build primitives.
    private KeyMapping sequenceKey;

    // Bound to H. Prints current position and places offset prismarine.
    private KeyMapping placeKey;

    // Bound to J: toggles infinite place-and-break loop in front of you.
    private KeyMapping placeBreakKey;

    private java.util.List<BuildAction> sequence = null;
    private int sequenceIndex = -1;
    private boolean sequenceRunning = false;

    // Toggled by K. Continuous auto-run sequence execution.
    private boolean sequenceAutoRun = false;
    private boolean sequenceStopRequested = false;
    private KeyMapping autoRunToggleKey;

    // Set by any action to force an immediate full stop.
    private boolean sequenceAbortRequested = false;

    // 1-2 tick settling delay between auto-run steps to allow physics & keys to clear.
    private int autoRunDelayTicks = 0;

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

        autoRunToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.poha.toggle_auto_run",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (sequenceKey.consumeClick()) {
                if (sequence != null) {
                    if (sequenceAutoRun) {
                        client.player.sendSystemMessage(Component.literal(
                                "Auto-run (K) is already active — press K to stop it first."));
                    } else if (!sequenceRunning && autoRunDelayTicks == 0) {
                        BuildAction action = sequence.get(sequenceIndex);
                        action.begin(client.player, client.player.level(), client.options);
                        sequenceRunning = true;
                    }
                } else {
                    sequence = buildSequence();
                    sequenceIndex = 0;
                    sequenceRunning = false;
                    sequenceStopRequested = false;
                    sequenceAutoRun = false;
                    autoRunDelayTicks = 0;
                    if (sequence.isEmpty()) {
                        client.player.sendSystemMessage(Component.literal("buildSequence() is empty — nothing to run."));
                        sequence = null;
                    } else {
                        client.player.sendSystemMessage(Component.literal("Next: " + sequence.get(0).describe()));
                    }
                }
            }

            while (autoRunToggleKey.consumeClick()) {
                if (sequenceAutoRun) {
                    sequenceStopRequested = true;
                    client.player.sendSystemMessage(Component.literal("Stopping after the current step..."));
                } else if (sequence != null) {
                    client.player.sendSystemMessage(Component.literal(
                            "A sequence is already running via G — finish that first."));
                } else {
                    sequence = buildSequence();
                    sequenceIndex = 0;
                    sequenceStopRequested = false;
                    autoRunDelayTicks = 0;
                    if (sequence.isEmpty()) {
                        client.player.sendSystemMessage(Component.literal("buildSequence() is empty — nothing to run."));
                        sequence = null;
                    } else {
                        sequenceAutoRun = true;
                        client.player.sendSystemMessage(Component.literal(
                                "Auto-run started — looping the sequence continuously. Press K again to stop."));
                        BuildAction first = sequence.get(0);
                        first.begin(client.player, client.player.level(), client.options);
                        sequenceRunning = true;
                    }
                }
            }

            while (placeBreakKey.consumeClick()) {
                if (jLooping) {
                    jLooping = false;
                    client.player.sendSystemMessage(Component.literal("Place-and-break loop stopping after current block..."));
                } else if (sequenceRunning) {
                    client.player.sendSystemMessage(Component.literal("A sequence is already running!"));
                } else {
                    jLooping = true;
                    runSingleAction(placeAndBreakLoop(-1), client.player, client.player.level(), client.options);
                }
            }

            if (sequence != null) {
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

    private static final int SOURCE_HOTBAR_SLOT = 5;

    private java.util.List<BuildAction> buildSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();

        steps.add(lookForPlace());
        steps.add(place());

        steps.add(turnLeft());
        steps.add(turnRight());

        steps.add(moveLeft(1));
        steps.add(lookForPlace());
        steps.add(place());

        steps.add(moveLeft(1));
        steps.add(lookForPlace());
        steps.add(place());

        steps.add(moveRight(3));
        steps.add(lookForPlace());
        steps.add(place());

        steps.add(moveRight(1));
        steps.add(lookForPlace());
        steps.add(place());

        steps.add(moveLeft(2));

        steps.add(lookForPlace());
        steps.add(jumpForward());
        steps.add(centerAndAlign());

        steps.add(turnLeft());
        steps.add(moveForward(1));
        steps.add(lookForPlace());
        steps.add(place());

        steps.add(lookDown());
        steps.add(jumpAndPlace());
        steps.add(lookForPlaceFront());
        steps.add(place());

        steps.add(lookDown());
        steps.add(jumpAndPlace());
        steps.add(lookForPlaceFront());
        steps.add(place());

        steps.add(lookForPlaceUp());
        steps.add(place());
        steps.add(lookDown());
        steps.add(breakBelow());
        steps.add(breakBelow());

        steps.add(centerAndAlign());
        steps.add(moveBack(1));

        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(lookForPlaceUp());
        steps.add(place());
        steps.add(lookDown());
        steps.add(breakBelow());
        steps.add(breakBelow());

        steps.add(centerAndAlign());
        steps.add(moveBack(1));

        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(lookForPlaceUp());
        steps.add(place());
        steps.add(lookDown());
        steps.add(breakBelow());
        steps.add(breakBelow());

        steps.add(centerAndAlign());
        steps.add(moveBack(1));

        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(lookForPlaceUp());
        steps.add(place());

        steps.add(lookDown());
        steps.add(breakBelow());
        steps.add(centerAndAlign());
        steps.add(lookUp());
        steps.add(place());
        steps.add(breakBelow());

        steps.add(turnRight());
        steps.add(turnRight());
        steps.add(moveBack(1));
        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(centerAndAlign());
        steps.add(lookForPlaceUp());
        steps.add(place());

        steps.add(lookDown());
        steps.add(breakBelow());
        steps.add(lookForPlace());
        steps.add(centerAndAlign());
        steps.add(lookForPlaceUp());
        steps.add(place());

        steps.add(lookForPlace());
        steps.add(place());
        steps.add(moveBack(1));
        steps.add(centerAndAlign());
        steps.add(turnLeft());
        steps.add(moveBack(1));
        steps.add(lookDown());
        steps.add(moveBack(1));
        steps.add(lookForPlace());
        steps.add(centerAndAlign());
        return steps;
    }

    private BuildAction moveForward(int blocks) { return new MoveAction(MoveDir.FORWARD, blocks); }
    private BuildAction moveBack(int blocks)    { return new MoveAction(MoveDir.BACK, blocks); }
    private BuildAction moveLeft(int blocks)    { return new MoveAction(MoveDir.LEFT, blocks); }
    private BuildAction moveRight(int blocks)   { return new MoveAction(MoveDir.RIGHT, blocks); }
    private BuildAction turnLeft()              { return new TurnAction(false); }
    private BuildAction turnRight()             { return new TurnAction(true); }
    private BuildAction place()                 { return new PlaceAction(); }
    private BuildAction breakBlock()            { return new BreakAction(false); }
    private BuildAction breakBelow()            { return new BreakAction(true); }

    private BuildAction lookForPlaceFront()    { return new LookForPlaceAction(LookTarget.FRONT); }
    private BuildAction lookForPlaceDown()     { return new LookForPlaceAction(LookTarget.DOWN); }
    private BuildAction lookForPlaceUp()       { return new LookForPlaceAction(LookTarget.UP); }
    private BuildAction lookForPlace()         { return lookForPlaceFront(); }

    private BuildAction lookDown()             { return new LookForPlaceAction(LookTarget.DOWN_SELF); }

    private BuildAction jumpForward()          { return new JumpForwardAction(); }
    private BuildAction jumpAndPlace()         { return new JumpAndPlaceAction(); }
    private BuildAction placeAndBreakLoop(int cycles) { return new PlaceAndBreakLoopAction(cycles); }
    private BuildAction centerAndAlign()       { return new PositionAction(); }
    private BuildAction lookUp()               { return new LookForPlaceAction(LookTarget.UP_SELF); }

    private BlockHitResult lookedAtHit = null;

    private void runSingleAction(BuildAction action, LocalPlayer player, Level level, net.minecraft.client.Options options) {
        sequence = new java.util.ArrayList<>();
        sequence.add(action);
        sequenceIndex = 0;
        sequenceRunning = true;
        action.begin(player, level, options);
    }

    private void tickSequence(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        // Handle inter-step settling delay during auto-run or sequence loops
        if (autoRunDelayTicks > 0) {
            autoRunDelayTicks--;
            if (autoRunDelayTicks == 0 && sequenceIndex < sequence.size()) {
                BuildAction next = sequence.get(sequenceIndex);
                player.sendSystemMessage(Component.literal("Next: " + next.describe()));
                next.begin(player, level, options);
                sequenceRunning = true;
            }
            return;
        }

        if (!sequenceRunning || sequenceIndex < 0 || sequenceIndex >= sequence.size()) {
            return;
        }

        BuildAction action = sequence.get(sequenceIndex);
        boolean done = action.tick(player, level, options);

        if (sequenceAbortRequested) {
            action.end(player, level, options);
            player.sendSystemMessage(Component.literal("Sequence stopped."));
            sequence = null;
            sequenceIndex = -1;
            sequenceRunning = false;
            sequenceStopRequested = false;
            sequenceAutoRun = false;
            sequenceAbortRequested = false;
            jLooping = false;
            return;
        }

        if (!done) return;

        // Clean up the completed action's keys/momentum before moving to next step
        action.end(player, level, options);
        sequenceRunning = false;
        sequenceIndex++;

        if (sequenceIndex >= sequence.size()) {
            if (sequenceAutoRun && !sequenceStopRequested) {
                player.sendSystemMessage(Component.literal("Sequence complete — looping back to the start."));
                sequenceIndex = 0;
                // Give 2 ticks for ground physics to settle before starting from step 0
                autoRunDelayTicks = 2;
                return;
            }
            player.sendSystemMessage(Component.literal(
                    sequenceStopRequested ? "Stopped after completing the sequence." : "Sequence complete!"));
            sequence = null;
            sequenceIndex = -1;
            sequenceStopRequested = false;
            sequenceAutoRun = false;
            return;
        }

        if (sequenceStopRequested) {
            player.sendSystemMessage(Component.literal(
                    "Stopped. " + (sequence.size() - sequenceIndex) + " step(s) remaining."));
            sequence = null;
            sequenceIndex = -1;
            sequenceStopRequested = false;
            sequenceAutoRun = false;
            return;
        }

        if (sequenceAutoRun) {
            // Wait 1 tick for physics and key binds to settle before starting next step
            autoRunDelayTicks = 1;
        } else {
            BuildAction next = sequence.get(sequenceIndex);
            player.sendSystemMessage(Component.literal("Next: " + next.describe()));
        }
    }

    private abstract static class BuildAction {
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {}
        abstract boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options);
        
        // Guarantees input keys are released and residual horizontal velocity is killed upon completion/abort
        void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            options.keyUp.setDown(false);
            options.keyDown.setDown(false);
            options.keyLeft.setDown(false);
            options.keyRight.setDown(false);
            options.keyJump.setDown(false);
            if (player != null) {
                player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
            }
        }
        
        abstract String describe();
    }

    private class PositionAction extends BuildAction {
        private Vec3 startPos;
        private Vec3 targetPos;
        private float startYaw, startPitch;
        private float targetYaw, targetPitch;
        private int ticks = 0;
        private static final int DURATION_TICKS = 6;

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            ticks = 0;

            BlockPos currentBlock = player.blockPosition();
            startPos = player.position();
            targetPos = new Vec3(
                currentBlock.getX() + 0.5,
                startPos.y,
                currentBlock.getZ() + 0.5
            );

            Direction nearestFacing = Direction.orderedByNearest(player)[0];
            if (nearestFacing.getAxis().isVertical()) {
                nearestFacing = player.getDirection();
            }

            startYaw = player.getYRot();
            startPitch = player.getXRot();

            float rawTargetYaw = nearestFacing.toYRot();
            targetYaw = startYaw + Mth.wrapDegrees(rawTargetYaw - startYaw);
            targetPitch = 0.0f;
        }

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            ticks++;
            float t = Math.min(1.0f, ticks / (float) DURATION_TICKS);

            double currentX = startPos.x + (targetPos.x - startPos.x) * t;
            double currentZ = startPos.z + (targetPos.z - startPos.z) * t;
            player.setPos(currentX, player.getY(), currentZ);

            player.setDeltaMovement(0, player.getDeltaMovement().y, 0);

            player.setYRot(startYaw + (targetYaw - startYaw) * t);
            player.setXRot(startPitch + (targetPitch - startPitch) * t);

            if (t >= 1.0f) {
                player.setPos(targetPos.x, player.getY(), targetPos.z);
                player.setYRot(targetYaw);
                player.setXRot(targetPitch);
                return true;
            }

            return false;
        }

        @Override
        String describe() {
            return "center position and align facing direction";
        }
    }

    private enum MoveDir { FORWARD, BACK, LEFT, RIGHT }

    private class MoveAction extends BuildAction {
        final MoveDir dir;
        final double blocks;
        Vec3 startPos;
        int ticks = 0;
        static final int TIMEOUT_TICKS_PER_BLOCK = 45;

        MoveAction(MoveDir dir, int blocks) {
            this.dir = dir;
            this.blocks = blocks;
        }

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            startPos = player.position();
            ticks = 0;
            keyFor(options).setDown(true);
        }

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            ticks++;
            double traveled = player.position().distanceTo(startPos);
            
            boolean arrived = traveled >= (blocks - 0.35); 
            boolean timedOut = ticks >= TIMEOUT_TICKS_PER_BLOCK * Math.max(1, (int) blocks);

            if (arrived || timedOut) {
                return true;
            }
            return false;
        }

        @Override
        void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            keyFor(options).setDown(false);
            super.end(player, level, options);
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
            ticks = 0;
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

    public enum LookTarget {
        FRONT,
        DOWN,
        UP,
        DOWN_SELF,
        UP_SELF
    }

    private class LookForPlaceAction extends BuildAction {
        private static final double REACH = 4.5;
        private static final int DURATION_TICKS = 8;

        private final LookTarget targetDirection;
        BlockHitResult candidate;
        float startYaw, startPitch, targetYaw, targetPitch;
        int ticks = 0;

        LookForPlaceAction(LookTarget targetDirection) {
            this.targetDirection = targetDirection;
        }

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            ticks = 0;
            BlockPos targetPos;

            if (targetDirection == LookTarget.DOWN_SELF) {
                targetPos = player.blockPosition().below();
            } else if (targetDirection == LookTarget.UP_SELF) {
                targetPos = player.blockPosition().above(2);
            } else {
                BlockPos front = player.blockPosition().relative(player.getDirection(), 1);
                switch (targetDirection) {
                    case DOWN:
                        targetPos = front.below();
                        break;
                    case UP:
                        targetPos = front.above();
                        break;
                    case FRONT:
                    default:
                        targetPos = front;
                        break;
                }
            }

            candidate = findClickableFace(level, targetPos);
            if (candidate == null) {
                player.sendSystemMessage(Component.literal("Nothing nearby to look at (" + targetDirection.name().toLowerCase() + ")."));
                return;
            }

            Vec3 eyePos = player.getEyePosition(1.0f);
            Vec3 lookAt = candidate.getLocation();
            Vec3 diff = lookAt.subtract(eyePos);
            double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);

            startYaw = player.getYRot();
            startPitch = player.getXRot();

            if (targetDirection == LookTarget.DOWN_SELF || targetDirection == LookTarget.DOWN || targetDirection == LookTarget.UP_SELF) {
                targetYaw = startYaw;
            } else if (horizontalDist < 0.001) {
                targetYaw = startYaw;
            } else {
                float rawYaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
                targetYaw = startYaw + Mth.wrapDegrees(rawYaw - startYaw);
            }

            float rawPitch = (float) -Math.toDegrees(Math.atan2(diff.y, horizontalDist));
            targetPitch = Mth.clamp(rawPitch, -89.0f, 89.0f);
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
                player.sendSystemMessage(Component.literal("Turned to look " + targetDirection.name().toLowerCase() + ", but nothing in range to target."));
                lookedAtHit = null;
            } else {
                lookedAtHit = hit;
                player.sendSystemMessage(Component.literal("Targeting " + hit.getBlockPos()));
            }
            return true;
        }

        @Override
        String describe() {
            return "look " + targetDirection.name().toLowerCase() + " for a block to target";
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

            if (player.onGround() && ticks < 5) {
                options.keyJump.setDown(true);
            } else {
                options.keyJump.setDown(false);
            }

            boolean gainedHeight = player.getY() >= startY + 0.8;
            boolean movedForward = player.position().subtract(startPos).horizontalDistance() >= 0.8;
            boolean landed = ticks > 5 && player.onGround();
            boolean timedOut = ticks >= TIMEOUT_TICKS;

            if ((landed && gainedHeight && movedForward) || timedOut) {
                if (timedOut && !gainedHeight) {
                    player.sendSystemMessage(Component.literal("Jump forward failed — wall blocked or missed ledge."));
                }
                return true;
            }

            return false;
        }

        @Override
        void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            options.keyJump.setDown(false);
            options.keyUp.setDown(false);
            super.end(player, level, options);
        }

        @Override
        String describe() {
            return "jump forward up 1 block";
        }
    }

    private class JumpAndPlaceAction extends BuildAction {
        private double startY;
        private boolean placed = false;
        private int ticks = 0;
        private static final int TIMEOUT_TICKS = 40;

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            startY = player.getY();
            placed = false;
            ticks = 0;

            options.keyJump.setDown(true);
        }

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            ticks++;

            if (ticks > 5) {
                options.keyJump.setDown(false);
            }

            if (!placed && player.getY() >= startY + 1.0) {
                BlockPos targetBelow = player.blockPosition().below();
                
                ItemStack stack = player.getInventory().getItem(SOURCE_HOTBAR_SLOT);
                if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                    player.sendSystemMessage(Component.literal("Hotbar slot 6 doesn't have a placeable block."));
                    placed = true;
                } else {
                    BlockHitResult hitResult = buildHitResult(targetBelow, Direction.UP);
                    int previousSlot = player.getInventory().getSelectedSlot();
                    player.getInventory().setSelectedSlot(SOURCE_HOTBAR_SLOT);
                    net.minecraft.client.Minecraft.getInstance().gameMode
                            .useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                    player.getInventory().setSelectedSlot(previousSlot);
                    placed = true;
                }
            }

            boolean landed = ticks > 5 && player.onGround();
            boolean timedOut = ticks >= TIMEOUT_TICKS;

            if (landed || timedOut) {
                return true;
            }

            return false;
        }

        @Override
        void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            options.keyJump.setDown(false);
            super.end(player, level, options);
        }

        @Override
        String describe() {
            return "jump and place a block under feet";
        }
    }

    private class PlaceAction extends BuildAction {
        private boolean backingUp = false;
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
                backupTicks = 0;
            }
        }

        @Override
        void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            cleanupBackup(options);
            super.end(player, level, options);
        }

        @Override
        String describe() {
            return "place a block" + (lookedAtHit != null ? " where I'm looking" : " in front of me");
        }
    }

    private class BreakAction extends BuildAction {
        final boolean breakBelow;
        BlockPos target;

        BreakAction(boolean breakBelow) {
            this.breakBelow = breakBelow;
        }

        BreakAction() {
            this(false);
        }

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            if (breakBelow) {
                target = player.blockPosition().below();
            } else if (lookedAtHit != null) {
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

            if (toolSlot != -1) {
                ItemStack toolStack = player.getInventory().getItem(toolSlot);
                if (toolStack.isDamageableItem()) {
                    int maxDamage = toolStack.getMaxDamage();
                    int damage = toolStack.getDamageValue();
                    double remainingFraction = maxDamage > 0 ? 1.0 - (damage / (double) maxDamage) : 1.0;
                    if (remainingFraction < 0.2) {
                        net.minecraft.client.Minecraft.getInstance().gameMode.stopDestroyBlock();
                        player.sendSystemMessage(Component.literal(
                                "Tool below 20% durability (" + Math.round(remainingFraction * 100) +
                                        "%) — stopping the sequence."));
                        sequenceAbortRequested = true;
                        return true;
                    }
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
        void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            net.minecraft.client.Minecraft.getInstance().gameMode.stopDestroyBlock();
            super.end(player, level, options);
        }

        @Override
        String describe() {
            return breakBelow ? "break the block directly below me" : "break the block in front of me";
        }
    }

    private class PlaceAndBreakLoopAction extends BuildAction {
        private enum Stage { PLACE, MINE }
        private Stage currentStage = Stage.PLACE;

        private final int targetCycles;
        private BlockPos targetPos;
        private int previousSlot;
        private int totalCycles = 0;

        private boolean stopAfterThisCycle = false;

        private int airConfirmTicks = 0;
        private static final int AIR_CONFIRM_TICKS_REQUIRED = 2;

        PlaceAndBreakLoopAction(int targetCycles) {
            this.targetCycles = targetCycles;
        }

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            currentStage = Stage.PLACE;
            totalCycles = 0;
            stopAfterThisCycle = false;
            airConfirmTicks = 0;
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
                            jLooping = false;
                        }
                        return true;
                    }

                    if (!executePlace(player, level)) {
                        jLooping = false;
                        return true;
                    }
                    airConfirmTicks = 0;
                    currentStage = Stage.MINE;
                    net.minecraft.client.Minecraft.getInstance().gameMode
                            .startDestroyBlock(targetPos, Direction.UP);
                    return false;

                case MINE:
                    if (level.getBlockState(targetPos).isAir()) {
                        airConfirmTicks++;
                        if (airConfirmTicks < AIR_CONFIRM_TICKS_REQUIRED) {
                            return false;
                        }

                        net.minecraft.client.Minecraft.getInstance().gameMode.stopDestroyBlock();
                        player.getInventory().setSelectedSlot(previousSlot);
                        airConfirmTicks = 0;

                        totalCycles++;

                        if (stopAfterThisCycle) {
                            sequenceAbortRequested = true;
                            return true;
                        }

                        currentStage = Stage.PLACE;
                    } else {
                        airConfirmTicks = 0;
                        net.minecraft.client.Minecraft.getInstance().gameMode
                                .continueDestroyBlock(targetPos, Direction.UP);
                    }
                    return false;
            }

            return false;
        }

        private boolean executePlace(LocalPlayer player, Level level) {
            BlockHitResult hitResult;
            if (lookedAtHit != null) {
                hitResult = lookedAtHit;
                lookedAtHit = null;
            } else {
                BlockPos target = player.blockPosition().relative(player.getDirection(), 1);
                hitResult = findClickableFace(level, target);
                if (hitResult == null) {
                    player.sendSystemMessage(Component.literal("No valid block face to place against."));
                    return false;
                }
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
                ItemStack toolStack = player.getInventory().getItem(toolSlot);
                if (toolStack.isDamageableItem()) {
                    int maxDamage = toolStack.getMaxDamage();
                    int damage = toolStack.getDamageValue();
                    double remainingFraction = maxDamage > 0 ? 1.0 - (damage / (double) maxDamage) : 1.0;
                    if (remainingFraction < 0.2) {
                        player.sendSystemMessage(Component.literal(
                                "Tool below 20% durability (" + Math.round(remainingFraction * 100) +
                                        "%) — will stop after breaking this block."));
                        stopAfterThisCycle = true;
                    }
                }
                player.getInventory().setSelectedSlot(toolSlot);
            }

            return true;
        }

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
        void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            net.minecraft.client.Minecraft.getInstance().gameMode.stopDestroyBlock();
            super.end(player, level, options);
        }

        @Override
        String describe() {
            return "place and break loop";
        }
    }

    private BlockHitResult findClickableFace(Level level, BlockPos pos) {
        if (!level.getBlockState(pos).canBeReplaced()) {
            return buildHitResult(pos, Direction.UP);
        }

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

        BlockPos above = pos.above();
        if (!level.getBlockState(above).canBeReplaced()) {
            return buildHitResult(above, Direction.DOWN);
        }

        return null;
    }

    private BlockHitResult buildHitResult(BlockPos clickedPos, Direction clickedFace) {
        Vec3 hitVec = Vec3.atCenterOf(clickedPos).add(
                clickedFace.getStepX() * 0.5, clickedFace.getStepY() * 0.5, clickedFace.getStepZ() * 0.5);
        return new BlockHitResult(hitVec, clickedFace, clickedPos, false);
    }

    private int findSilkTouchSlot(LocalPlayer player) {
        net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> silkTouch;
        try {
            silkTouch = player.level().registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH);
        } catch (Exception e) {
            return -1;
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
            player.sendSystemMessage(Component.literal("Placed it, but you don't have a pickaxe to break it with."));
            player.getInventory().setSelectedSlot(pendingPreviousSlot);
            looping = false;
            return;
        }

        if (pickaxeSlot == -1) {
            int silkTouchSlot = findSilkTouchSlot(player);
            if (silkTouchSlot != -1) {
                pickaxeSlot = silkTouchSlot;
            }
        }

        if (pickaxeSlot != -1) {
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

        miningPos = placedPos;
        miningPreviousSlot = pendingPreviousSlot;
        net.minecraft.client.Minecraft.getInstance().gameMode.startDestroyBlock(miningPos, Direction.UP);
        mining = true;
    }

    private void tickMining(LocalPlayer player) {
        Level level = player.level();

        if (level.getBlockState(miningPos).isAir()) {
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
            net.minecraft.client.Minecraft.getInstance().gameMode.startDestroyBlock(miningPos, Direction.UP);
        }
    }
}
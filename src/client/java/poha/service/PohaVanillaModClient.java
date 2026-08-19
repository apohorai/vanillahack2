package poha.service;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.minecraft.world.inventory.ContainerInput;

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
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.ItemTags;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;

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

    // Bound to K: toggles continuous auto-run sequence execution.
    private KeyMapping autoRunToggleKey;

    // Bound to L: cycles through available predefined build sequences.
    private KeyMapping cycleSequenceKey;

    private java.util.List<BuildAction> sequence = null;
    private int sequenceIndex = -1;
    private boolean sequenceRunning = false;

    // Toggled by K. Continuous auto-run sequence execution.
    private boolean sequenceAutoRun = false;
    private boolean sequenceStopRequested = false;

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

    // Default hotbar slot (0-indexed; 5 = Slot 6)
    private static final int DEFAULT_HOTBAR_SLOT = 5;
    private int targetHotbarSlot = DEFAULT_HOTBAR_SLOT;

    // Sequence Selection System
    private final List<NamedSequence> registeredSequences = new ArrayList<>();
    private int activeSequenceIndex = 0;

    private record NamedSequence(String name, Supplier<List<BuildAction>> builder) {}

    @Override
    public void onInitializeClient() {
        // Register available predefined sequences here
        registerSequences();

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

        cycleSequenceKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.poha.cycle_sequence",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Cycle sequence with L
            while (cycleSequenceKey.consumeClick()) {
                if (sequenceRunning || sequenceAutoRun || sequence != null) {
                    client.player.sendSystemMessage(Component.literal(
                            "Cannot switch sequence while a sequence is running or paused. Finish or stop it first."));
                } else if (registeredSequences.isEmpty()) {
                    client.player.sendSystemMessage(Component.literal("No sequences registered."));
                } else {
                    activeSequenceIndex = (activeSequenceIndex + 1) % registeredSequences.size();
                    NamedSequence current = registeredSequences.get(activeSequenceIndex);
                    client.player.sendSystemMessage(Component.literal(
                            "Selected sequence: " + current.name() + " (" + (activeSequenceIndex + 1) + "/" + registeredSequences.size() + ")"));
                }
            }

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
                    targetHotbarSlot = DEFAULT_HOTBAR_SLOT; // Reset to slot 6 default on new run
                    sequence = buildActiveSequence();
                    sequenceIndex = 0;
                    sequenceRunning = false;
                    sequenceStopRequested = false;
                    sequenceAutoRun = false;
                    autoRunDelayTicks = 0;
                    if (sequence.isEmpty()) {
                        client.player.sendSystemMessage(Component.literal("Selected sequence is empty — nothing to run."));
                        sequence = null;
                    } else {
                        client.player.sendSystemMessage(Component.literal(
                                "[" + getActiveSequenceName() + "] Next: " + sequence.get(0).describe()));
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
                    targetHotbarSlot = DEFAULT_HOTBAR_SLOT; // Reset to slot 6 default on new run
                    sequence = buildActiveSequence();
                    sequenceIndex = 0;
                    sequenceStopRequested = false;
                    autoRunDelayTicks = 0;
                    if (sequence.isEmpty()) {
                        client.player.sendSystemMessage(Component.literal("Selected sequence is empty — nothing to run."));
                        sequence = null;
                    } else {
                        sequenceAutoRun = true;
                        client.player.sendSystemMessage(Component.literal(
                                "Auto-run started [" + getActiveSequenceName() + "] — looping continuously. Press K again to stop."));
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

    private void registerSequences() {
        registeredSequences.clear();
        registeredSequences.add(new NamedSequence("Tunnel Builder", this::buildTunnelBuilderSequence));
        registeredSequences.add(new NamedSequence("Shovel", this::shovelSequence));
        registeredSequences.add(new NamedSequence("Sword", this::swordSequence));
        registeredSequences.add(new NamedSequence("Drop", this::dropSequence));
        registeredSequences.add(new NamedSequence("Drop64", this::dropSequence64));
        registeredSequences.add(new NamedSequence("Drop64batch", this::dropSequence64Multiple));
        registeredSequences.add(new NamedSequence("plot", this::plotSequence));
        registeredSequences.add(new NamedSequence("refillfromchest", this::fillFromChestSequence));
        registeredSequences.add(new NamedSequence("shoot arrow", this::shootArrowSequence));
        registeredSequences.add(new NamedSequence("Golden boots", this::goldenBootSequence));
        // Add additional sequences here in the future:
        // registeredSequences.add(new NamedSequence("Bridge Builder", this::buildBridgeSequence));
    }

    private List<BuildAction> buildActiveSequence() {
        if (registeredSequences.isEmpty()) return new ArrayList<>();
        return registeredSequences.get(activeSequenceIndex).builder().get();
    }

    private String getActiveSequenceName() {
        if (registeredSequences.isEmpty()) return "None";
        return registeredSequences.get(activeSequenceIndex).name();
    }
    private java.util.List<BuildAction> shovelSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(extractFromChest(6));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(extractFromChest(7));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(craftWoodenShovels(18));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(offloadWoodenShovels());
        steps.add(centerAndAlign());
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        return steps;
    }

    private java.util.List<BuildAction> goldenBootSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(extractFromChest(6));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(extractFromChest(7));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(craftGoldenBoots(18));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(offloadWoodenShovels());
        steps.add(centerAndAlign());
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        return steps;
    }







    private java.util.List<BuildAction> swordSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(extractFromChest(6));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(extractFromChest(7));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(craftWoodenSwords(18));
        steps.add(moveLeft(1));
        steps.add(centerAndAlign());
        steps.add(lookForPlace());
        steps.add(offloadWoodenSwords());
        steps.add(centerAndAlign());
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        steps.add(moveRight(1));
        return steps;
    }

        private java.util.List<BuildAction> plotSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(placePotWithFlower(6, 7));
        steps.add(breakBlock());
        
        return steps;
    }
    private java.util.List<BuildAction> shieldSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();

        steps.add(craftGoldenBoots(placeCountdown));
        
        return steps;
    }
        private java.util.List<BuildAction> shootArrowSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(shootArrow());
        
        return steps;
    }

    private java.util.List<BuildAction> dropSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(dropHotbar(6, 1));
        steps.add(eat(9));
        
        return steps;
    }
        private java.util.List<BuildAction> fillFromChestSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(extractFromChest(6));

        
        return steps;
    }
    private java.util.List<BuildAction> dropSequence64() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(refillHotbarSlot(6));
        steps.add(dropHotbar(6, 40));
        steps.add(eat(9));
        
        return steps;
    }
    private java.util.List<BuildAction> dropSequence64Multiple() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();
        steps.add(refillHotbarSlot(3));
        steps.add(refillHotbarSlot(4));
        steps.add(refillHotbarSlot(5));
        steps.add(refillHotbarSlot(6));
        steps.add(refillHotbarSlot(7));
        steps.add(refillHotbarSlot(8));
 //       steps.add(refillHotbarSlot(9));
        steps.add(dropHotbar(3, 60));
        steps.add(dropHotbar(4, 60));
        steps.add(dropHotbar(5, 60));
        steps.add(dropHotbar(6, 60));
        steps.add(dropHotbar(7, 60));
        steps.add(dropHotbar(8, 60));
 //       steps.add(eat(9));
        steps.add(openCloseInventory());
        steps.add(delaySeconds(3));
        return steps;
    }

    private java.util.List<BuildAction> buildTunnelBuilderSequence() {
        java.util.List<BuildAction> steps = new java.util.ArrayList<>();

        steps.add(refillHotbarSlot(6));
        steps.add(refillHotbarSlot(7));

        steps.add(selectHotbarSlot(7));

        steps.add(lookForPlace());
        steps.add(place());

        steps.add(turnLeft());
        steps.add(turnRight());

        steps.add(moveLeft(1));

        steps.add(lookForPlace());
        steps.add(place());
        
        steps.add(selectHotbarSlot(6));
        steps.add(moveLeft(1));
        steps.add(lookForPlace());
        steps.add(place());
        steps.add(selectHotbarSlot(7));
        steps.add(moveRight(3));
        steps.add(lookForPlace());
        steps.add(place());
        steps.add(selectHotbarSlot(6));

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
        // first col end
        steps.add(centerAndAlign());
        steps.add(moveBack(1));

        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(lookDown());
        steps.add(jumpAndPlace());

        steps.add(centerAndAlign());
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

        steps.add(centerAndAlign());
        steps.add(lookForPlaceUp());
        steps.add(checkAxisAndSelectSlot(Axis.X, 5, 8));
        steps.add(place());
        steps.add(selectHotbarSlot(6));

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

    // Slot selection actions
    private BuildAction selectSlot(int slotZeroIndexed) { return new SelectSlotAction(slotZeroIndexed); }
    private BuildAction selectHotbarSlot(int slotOneIndexed) { return new SelectSlotAction(slotOneIndexed - 1); }

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

    private BuildAction refillSlot() { return new RefillSlotAction(); }
    private BuildAction refillHotbarSlot(int slotOneIndexed) { return new RefillSlotAction(slotOneIndexed - 1); }
    private BuildAction checkAxisAndSelectSlot(Axis axis, int divisor, int slotOneIndexed) {
        return new CheckPosSelectSlotAction(axis, divisor, slotOneIndexed);
    }
    private BuildAction eat(int slotOneIndexed) { return new EatAction(slotOneIndexed); }
    private BuildAction eat()                  { return new EatAction(); }

    // Drop N items from Hotbar in a single tick (instant)
private BuildAction dropHotbar(int slotOneIndexed, int count) {
    return new DropItemsAction(SlotType.HOTBAR, slotOneIndexed, count);
}

// Drop N items from Hotbar at a controlled rate (e.g., 5 items per tick to avoid strict server anti-cheat kicks)
private BuildAction dropHotbar(int slotOneIndexed, int count, int itemsPerTick) {
    return new DropItemsAction(SlotType.HOTBAR, slotOneIndexed, count, itemsPerTick);
}

// Drop entire stack from Hotbar
private BuildAction dropHotbar(int slotOneIndexed) {
    return new DropItemsAction(SlotType.HOTBAR, slotOneIndexed);
}

// Drop N items from Inventory in a single tick (instant)
private BuildAction dropInventory(int slotOneIndexed, int count) {
    return new DropItemsAction(SlotType.INVENTORY, slotOneIndexed, count);
}

// Drop N items from Inventory at a controlled rate
private BuildAction dropInventory(int slotOneIndexed, int count, int itemsPerTick) {
    return new DropItemsAction(SlotType.INVENTORY, slotOneIndexed, count, itemsPerTick);
}

// Drop entire stack from Inventory
private BuildAction dropInventory(int slotOneIndexed) {
    return new DropItemsAction(SlotType.INVENTORY, slotOneIndexed);
}
// Place pot from potSlot and immediately insert flower from flowerSlot (both 1-indexed)
private BuildAction placePotWithFlower(int potSlotOneIndexed, int flowerSlotOneIndexed) {
    return new PlaceFlowerPotAction(potSlotOneIndexed, flowerSlotOneIndexed);
}
// Extract items from chest to fill specified hotbar slot (1-9)
private BuildAction extractFromChest(int slotOneIndexed) {
    return new ExtractFromChestAction(slotOneIndexed);
}

// Extract items from chest to fill current target hotbar slot
private BuildAction extractFromChest() {
    return new ExtractFromChestAction();
}
// Craft X number of wooden shovels at a crafting table in front of you
private BuildAction craftWoodenShovels(int count) {
    return new CraftShovelsAction(count);
}
// Offload all wooden shovels from inventory/hotbar to chest in front
private BuildAction offloadWoodenShovels() {
    return new OffloadWoodenShovelsAction();
}
// Craft X number of wooden swords at a crafting table in front of you
private BuildAction craftWoodenSwords(int count) {
    return new CraftSwordsAction(count);
}
// Offload all wooden swords from inventory/hotbar to chest in front
private BuildAction offloadWoodenSwords() {
    return new OffloadWoodenSwordsAction();
}
// Rapidly shoot an arrow from a specified hotbar slot (1-indexed) at minimum charge time (3 ticks)
private BuildAction shootArrow(int slotOneIndexed) {
    return new ShootArrowAction(slotOneIndexed);
}

// Rapidly shoot an arrow from the active target hotbar slot at minimum charge time (3 ticks)
private BuildAction shootArrow() {
    return new ShootArrowAction();
}

// Shoot an arrow from slot with custom charge ticks (e.g. 20 ticks for full power shot)
private BuildAction shootArrow(int slotOneIndexed, int chargeTicks) {
    return new ShootArrowAction(slotOneIndexed, chargeTicks);
}

private BuildAction openCloseInventory() { 
    return new OpenCloseInventoryAction(); 
}
// Delay by exact tick count
private BuildAction delayTicks(int ticks) { 
    return new DelayAction(ticks); 
}

// Delay by seconds (convenience method)
private BuildAction delaySeconds(double seconds) { 
    return new DelayAction((int) Math.round(seconds * 20.0)); 
}
// Craft X number of golden boots at a crafting table in front of you
private BuildAction craftGoldenBoots(int count) {
    return new CraftGoldenBootsAction(count);
}

    private BlockHitResult lookedAtHit = null;

    private void runSingleAction(BuildAction action, LocalPlayer player, Level level, net.minecraft.client.Options options) {
        sequence = new java.util.ArrayList<>();
        sequence.add(action);
        sequenceIndex = 0;
        sequenceRunning = true;
        action.begin(player, level, options);
    }

    private void tickSequence(LocalPlayer player, Level level, net.minecraft.client.Options options) {
    if (autoRunDelayTicks > 0) {
        autoRunDelayTicks--;
        if (autoRunDelayTicks == 0 && sequence != null && sequenceIndex < sequence.size()) {
            BuildAction next = sequence.get(sequenceIndex);
            player.sendSystemMessage(Component.literal("[" + getActiveSequenceName() + "] Next: " + next.describe()));
            next.begin(player, level, options);
            sequenceRunning = true;
        }
        return;
    }

    if (!sequenceRunning || sequence == null || sequenceIndex < 0 || sequenceIndex >= sequence.size()) {
        return;
    }
    


    BuildAction action = sequence.get(sequenceIndex);
    boolean done = action.tick(player, level, options);

    if (sequenceAbortRequested) {
        action.end(player, level, options);
        player.sendSystemMessage(Component.literal("Sequence stopped due to an abort condition."));
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

    action.end(player, level, options);
    sequenceRunning = false;
    sequenceIndex++;

    // Check if the current sequence iteration has finished
    if (sequenceIndex >= sequence.size()) {
        if (sequenceAutoRun && !sequenceStopRequested) {
            player.sendSystemMessage(Component.literal("Sequence complete — rebuilding and looping back to start..."));
            
            // Rebuild fresh sequence actions for the next run
            sequence = buildActiveSequence();
            sequenceIndex = 0;
            
            if (sequence.isEmpty()) {
                player.sendSystemMessage(Component.literal("Selected sequence is empty — stopping auto-run."));
                sequence = null;
                sequenceIndex = -1;
                sequenceAutoRun = false;
            } else {
                // Short delay to let physics and key states settle before step 1
                autoRunDelayTicks = 2;
            }
            return;
        }

        player.sendSystemMessage(Component.literal(
                sequenceStopRequested ? "Stopped auto-run after completing sequence." : "Sequence complete!"));
        sequence = null;
        sequenceIndex = -1;
        sequenceStopRequested = false;
        sequenceAutoRun = false;
        return;
    }

    if (sequenceStopRequested) {
        player.sendSystemMessage(Component.literal(
                "Stopped auto-run. " + (sequence.size() - sequenceIndex) + " step(s) remaining."));
        sequence = null;
        sequenceIndex = -1;
        sequenceStopRequested = false;
        sequenceAutoRun = false;
        return;
    }

    if (sequenceAutoRun) {
        autoRunDelayTicks = 1;
    } else {
        BuildAction next = sequence.get(sequenceIndex);
        player.sendSystemMessage(Component.literal("[" + getActiveSequenceName() + "] Next: " + next.describe()));
    }
}
private class CraftGoldenBootsAction extends BuildAction {
    private final int targetBootCount;
    private int bootsCraftedSoFar = 0;

    private enum Stage { OPEN_TABLE, CRAFT, CLOSE_TABLE }
    private Stage stage = Stage.OPEN_TABLE;

    private int timeoutTicks = 0;
    private static final int MAX_TIMEOUT_TICKS = 100;

    CraftGoldenBootsAction(int count) {
        this.targetBootCount = Math.max(1, count);
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        stage = Stage.OPEN_TABLE;
        bootsCraftedSoFar = 0;
        timeoutTicks = 0;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        timeoutTicks++;
        if (timeoutTicks >= MAX_TIMEOUT_TICKS) {
            player.sendSystemMessage(Component.literal("Crafting golden boots timed out. Aborting sequence."));
            closeContainerIfOpen(player);
            sequenceAbortRequested = true;
            return true;
        }

        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode == null) return true;

        switch (stage) {
            case OPEN_TABLE: {
                BlockHitResult hitResult;
                if (lookedAtHit != null) {
                    hitResult = lookedAtHit;
                    lookedAtHit = null;
                } else {
                    BlockPos tablePos = player.blockPosition().relative(player.getDirection(), 1);
                    hitResult = findClickableFace(level, tablePos);
                    if (hitResult == null) {
                        player.sendSystemMessage(Component.literal("No Crafting Table in front to open."));
                        sequenceAbortRequested = true;
                        return true;
                    }
                }

                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                stage = Stage.CRAFT;
                return false;
            }

            case CRAFT: {
                if (player.containerMenu == player.inventoryMenu) {
                    return false;
                }

                var menu = player.containerMenu;
                int containerId = menu.containerId;

                while (bootsCraftedSoFar < targetBootCount) {
                    clearGrid(gameMode, containerId, player);

                    if (!prepareGoldenBootsRecipe(gameMode, containerId, menu, player)) {
                        player.sendSystemMessage(Component.literal(
                                "Lacking Gold Ingots to craft golden boots (" + bootsCraftedSoFar + "/" + targetBootCount + " crafted). Aborting sequence."));
                        closeContainerIfOpen(player);
                        sequenceAbortRequested = true;
                        return true;
                    }

                    // Shift-click result slot (0)
                    gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
                    bootsCraftedSoFar++;
                }

                player.sendSystemMessage(Component.literal(
                        "Successfully crafted " + bootsCraftedSoFar + " golden boot(s)."));
                
                closeContainerIfOpen(player);
                stage = Stage.CLOSE_TABLE;
                return true;
            }

            case CLOSE_TABLE:
                return true;
        }

        return true;
    }

    private void clearGrid(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, LocalPlayer player) {
        for (int i = 1; i <= 9; i++) {
            if (!player.containerMenu.getSlot(i).getItem().isEmpty()) {
                gameMode.handleContainerInput(containerId, i, 0, ContainerInput.QUICK_MOVE, player);
            }
        }
    }

    private boolean prepareGoldenBootsRecipe(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, net.minecraft.world.inventory.AbstractContainerMenu menu, LocalPlayer player) {
        // Golden Boots Recipe Layout:
        // Slot 4 (Middle left)  = 1 Gold Ingot
        // Slot 6 (Middle right) = 1 Gold Ingot
        // Slot 7 (Bottom left)  = 1 Gold Ingot
        // Slot 9 (Bottom right) = 1 Gold Ingot

        int goldSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.world.item.Items.GOLD_INGOT));
        if (goldSlot == -1) return false;

        // Place 1 Gold Ingot each into slots 4, 6, 7, and 9
        gameMode.handleContainerInput(containerId, goldSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 4, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 6, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 7, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 9, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, goldSlot, 0, ContainerInput.PICKUP, player);

        return true;
    }

    private int findItemSlotInMenu(net.minecraft.world.inventory.AbstractContainerMenu menu, java.util.function.Predicate<ItemStack> matcher) {
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && matcher.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private void closeContainerIfOpen(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        closeContainerIfOpen(player);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "craft " + targetBootCount + " golden boot(s) using crafting table";
    }
}
    private abstract static class BuildAction {
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {}
        abstract boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options);
        
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


private class DelayAction extends BuildAction {
    private final int targetTicks;
    private int elapsedTicks = 0;

    /**
     * @param ticks Number of Minecraft client ticks to delay (20 ticks = 1 second)
     */
    DelayAction(int ticks) {
        this.targetTicks = Math.max(1, ticks);
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        elapsedTicks = 0;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        elapsedTicks++;
        return elapsedTicks >= targetTicks;
    }

    @Override
    String describe() {
        return "wait " + targetTicks + " tick(s) (~" + String.format("%.1f", targetTicks / 20.0) + "s)";
    }
}

private class OpenCloseInventoryAction extends BuildAction {
    private enum Stage { OPEN, CLOSE }
    private Stage stage = Stage.OPEN;
    private int delayTicks = 0;
    private static final int OPEN_DURATION_TICKS = 1; // Number of ticks to keep inventory open

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        stage = Stage.OPEN;
        delayTicks = 0;
        
        // Send packet to open the player inventory container
        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode != null) {
            player.sendOpenInventory();
        }
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        switch (stage) {
            case OPEN:
                delayTicks++;
                // Wait 1 tick so the container menu opens cleanly before closing
                if (delayTicks >= OPEN_DURATION_TICKS) {
                    closeContainerIfOpen(player);
                    stage = Stage.CLOSE;
                    return true;
                }
                return false;

            case CLOSE:
                return true;
        }
        return true;
    }

    private void closeContainerIfOpen(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        closeContainerIfOpen(player);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "open and close inventory";
    }
}


    private class DropItemsAction extends BuildAction {
    private final int targetSlot;
    private final int countToDrop;
    private final int itemsPerTick; // How many single items to drop per tick
    private int remainingToDrop;

    /**
     * @param slotType HOTBAR (1-9) or INVENTORY (1-27)
     * @param slotOneIndexed Slot number (1-based)
     * @param countToDrop Total number of items to drop
     * @param itemsPerTick How many items to drop per tick (set to Integer.MAX_VALUE or countToDrop for instant drop)
     */
    DropItemsAction(SlotType slotType, int slotOneIndexed, int countToDrop, int itemsPerTick) {
        int index = Math.max(0, slotOneIndexed - 1);
        if (slotType == SlotType.HOTBAR) {
            this.targetSlot = Mth.clamp(index, 0, 8);
        } else {
            this.targetSlot = Mth.clamp(index, 0, 26) + 9;
        }
        this.countToDrop = Math.max(1, countToDrop);
        this.itemsPerTick = Math.max(1, itemsPerTick);
    }

    // Default to dropping all requested items as fast as possible in a single tick
    DropItemsAction(SlotType slotType, int slotOneIndexed, int countToDrop) {
        this(slotType, slotOneIndexed, countToDrop, countToDrop);
    }

    // Drops full stack instantly
    DropItemsAction(SlotType slotType, int slotOneIndexed) {
        this(slotType, slotOneIndexed, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        remainingToDrop = countToDrop;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode == null) return true;

        int containerId = player.inventoryMenu.containerId;
        int containerSlot = (targetSlot < 9) ? (targetSlot + 36) : targetSlot;

        ItemStack stack = player.getInventory().getItem(targetSlot);
        if (stack.isEmpty() || remainingToDrop <= 0) {
            return true;
        }

        // 1. If dropping equal to or more than full stack, send 1 packet for full stack drop
        if (remainingToDrop >= stack.getCount()) {
            gameMode.handleContainerInput(containerId, containerSlot, 1, ContainerInput.THROW, player);
            remainingToDrop = 0;
            return true;
        }

        // 2. Otherwise, drop multiple items in a batch loop within this single tick
        int droppedThisTick = 0;
        while (remainingToDrop > 0 && droppedThisTick < itemsPerTick) {
            ItemStack currentStack = player.getInventory().getItem(targetSlot);
            if (currentStack.isEmpty()) break;

            // Button 0 = THROW single item
            gameMode.handleContainerInput(containerId, containerSlot, 0, ContainerInput.THROW, player);
            remainingToDrop--;
            droppedThisTick++;
        }

        return remainingToDrop <= 0 || player.getInventory().getItem(targetSlot).isEmpty();
    }

    @Override
    String describe() {
        String slotLabel = targetSlot < 9 
                ? "hotbar slot " + (targetSlot + 1) 
                : "inventory slot " + (targetSlot - 8);
        return "drop " + (countToDrop == Integer.MAX_VALUE ? "all" : countToDrop) + " item(s) from " + slotLabel;
    }
}

public enum SlotType {
    HOTBAR,     // Hotbar slots 1–9
    INVENTORY   // Inventory rows 1–3 (slots 1–27)
}


    /**
     * Action that selects a hotbar slot and holds right-click (use key) 
     * until the player finishes eating or cannot eat.
     */
    
    public enum Axis { X, Y, Z }

    private class CheckPosSelectSlotAction extends BuildAction {
        private final Axis axis;
        private final int divisor;
        private final int slotToSelect;

        CheckPosSelectSlotAction(Axis axis, int divisor, int slotOneIndexed) {
            this.axis = axis;
            this.divisor = divisor;
            this.slotToSelect = Mth.clamp(slotOneIndexed - 1, 0, 8);
        }

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            if (divisor == 0) {
                player.sendSystemMessage(Component.literal("Divisor cannot be 0 for position check."));
                return true;
            }

            BlockPos pos = player.blockPosition();
            int coord = switch (axis) {
                case X -> pos.getX();
                case Y -> pos.getY();
                case Z -> pos.getZ();
            };

            if (coord % divisor == 0) {
                targetHotbarSlot = slotToSelect;
                player.getInventory().setSelectedSlot(slotToSelect);
                player.sendSystemMessage(Component.literal(
                        "Position " + axis.name() + "=" + coord + " is divisible by " + divisor +
                        ". Switched hotbar slot to " + (slotToSelect + 1) + "."));
            }

            return true;
        }

        @Override
        String describe() {
            return "check if " + axis.name() + " is divisible by " + divisor + " and set slot to " + (slotToSelect + 1);
        }
    }
private class ShootArrowAction extends BuildAction {
    private final int bowSlot;
    private final int chargeTicks;
    private int ticksHeld = 0;
    private static final int MINIMUM_BOW_CHARGE_TICKS = 3; // Absolute minimum Minecraft draw time

    /**
     * @param slotOneIndexed Hotbar slot containing the bow (1-9)
     * @param chargeTicks How many ticks to hold right-click (default 3 for max speed)
     */
    ShootArrowAction(int slotOneIndexed, int chargeTicks) {
        this.bowSlot = Mth.clamp(slotOneIndexed - 1, 0, 8);
        this.chargeTicks = Math.max(MINIMUM_BOW_CHARGE_TICKS, chargeTicks);
    }

    ShootArrowAction(int slotOneIndexed) {
        this(slotOneIndexed, MINIMUM_BOW_CHARGE_TICKS);
    }

    ShootArrowAction() {
        this(targetHotbarSlot + 1, MINIMUM_BOW_CHARGE_TICKS);
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        ticksHeld = 0;

        ItemStack stack = player.getInventory().getItem(bowSlot);
        if (stack.isEmpty() || !stack.is(net.minecraft.world.item.Items.BOW)) {
            player.sendSystemMessage(Component.literal("Slot " + (bowSlot + 1) + " does not contain a Bow."));
            return;
        }

        // Switch to bow slot and press right-click
        player.getInventory().setSelectedSlot(bowSlot);
        options.keyUse.setDown(true);
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        ticksHeld++;

        ItemStack stack = player.getInventory().getItem(bowSlot);
        if (stack.isEmpty() || !stack.is(net.minecraft.world.item.Items.BOW)) {
            options.keyUse.setDown(false);
            return true;
        }

        // Hold right click until charge threshold is reached
        if (ticksHeld < chargeTicks) {
            options.keyUse.setDown(true);
            return false;
        }

        // Release right click to release the arrow
        options.keyUse.setDown(false);
        return true;
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        options.keyUse.setDown(false);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "rapid-shoot arrow from slot " + (bowSlot + 1) + " (" + chargeTicks + " ticks charge)";
    }
}

private class CraftSwordsAction extends BuildAction {
    private final int targetSwordCount;
    private int swordsCraftedSoFar = 0;

    private enum Stage { OPEN_TABLE, CRAFT, CLOSE_TABLE }
    private Stage stage = Stage.OPEN_TABLE;

    private int timeoutTicks = 0;
    private static final int MAX_TIMEOUT_TICKS = 100;

    CraftSwordsAction(int count) {
        this.targetSwordCount = Math.max(1, count);
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        stage = Stage.OPEN_TABLE;
        swordsCraftedSoFar = 0;
        timeoutTicks = 0;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        timeoutTicks++;
        if (timeoutTicks >= MAX_TIMEOUT_TICKS) {
            player.sendSystemMessage(Component.literal("Crafting wooden swords timed out. Aborting sequence."));
            closeContainerIfOpen(player);
            sequenceAbortRequested = true;
            return true;
        }

        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode == null) return true;

        switch (stage) {
            case OPEN_TABLE: {
                BlockHitResult hitResult;
                if (lookedAtHit != null) {
                    hitResult = lookedAtHit;
                    lookedAtHit = null;
                } else {
                    BlockPos tablePos = player.blockPosition().relative(player.getDirection(), 1);
                    hitResult = findClickableFace(level, tablePos);
                    if (hitResult == null) {
                        player.sendSystemMessage(Component.literal("No Crafting Table in front to open."));
                        sequenceAbortRequested = true;
                        return true;
                    }
                }

                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                stage = Stage.CRAFT;
                return false;
            }

            case CRAFT: {
                if (player.containerMenu == player.inventoryMenu) {
                    return false;
                }

                var menu = player.containerMenu;
                int containerId = menu.containerId;

                while (swordsCraftedSoFar < targetSwordCount) {
                    clearGrid(gameMode, containerId, player);

                    if (!prepareSwordRecipe(gameMode, containerId, menu, player)) {
                        player.sendSystemMessage(Component.literal(
                                "Lacking materials to craft wooden sword (" + swordsCraftedSoFar + "/" + targetSwordCount + " crafted). Aborting sequence."));
                        closeContainerIfOpen(player);
                        sequenceAbortRequested = true;
                        return true;
                    }

                    // Shift-click result slot (0)
                    gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
                    swordsCraftedSoFar++;
                }

                player.sendSystemMessage(Component.literal(
                        "Successfully crafted " + swordsCraftedSoFar + " wooden sword(s)."));
                
                closeContainerIfOpen(player);
                stage = Stage.CLOSE_TABLE;
                return true;
            }

            case CLOSE_TABLE:
                return true;
        }

        return true;
    }

    private void clearGrid(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, LocalPlayer player) {
        for (int i = 1; i <= 9; i++) {
            if (!player.containerMenu.getSlot(i).getItem().isEmpty()) {
                gameMode.handleContainerInput(containerId, i, 0, ContainerInput.QUICK_MOVE, player);
            }
        }
    }

    private boolean prepareSwordRecipe(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, net.minecraft.world.inventory.AbstractContainerMenu menu, LocalPlayer player) {
        // Wooden Sword Recipe Layout:
        // Slot 2 (Top center) = 1 Plank
        // Slot 5 (Middle center) = 1 Plank
        // Slot 8 (Bottom center) = 1 Stick

        // 1. Check/Craft Planks for Slots 2 & 5
        int plankSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.tags.ItemTags.PLANKS));
        if (plankSlot == -1) {
            if (!craftPlanksFromLogs(gameMode, containerId, menu, player)) return false;
            plankSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.tags.ItemTags.PLANKS));
            if (plankSlot == -1) return false;
        }

        // Place 1 Plank in Slot 2 and 1 Plank in Slot 5
        gameMode.handleContainerInput(containerId, plankSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 2, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 5, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, plankSlot, 0, ContainerInput.PICKUP, player);

        // 2. Check/Craft Stick for Slot 8
        int stickSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.world.item.Items.STICK));
        if (stickSlot == -1) {
            if (!craftSticksFromPlanks(gameMode, containerId, menu, player)) return false;
            stickSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.world.item.Items.STICK));
            if (stickSlot == -1) return false;
        }

        // Place 1 Stick in Slot 8
        gameMode.handleContainerInput(containerId, stickSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 8, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, stickSlot, 0, ContainerInput.PICKUP, player);

        return true;
    }

    private boolean craftPlanksFromLogs(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, net.minecraft.world.inventory.AbstractContainerMenu menu, LocalPlayer player) {
        int logSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.tags.ItemTags.LOGS));
        if (logSlot == -1) return false;

        gameMode.handleContainerInput(containerId, logSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 5, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, logSlot, 0, ContainerInput.PICKUP, player);

        gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
        clearGrid(gameMode, containerId, player);
        return true;
    }

    private boolean craftSticksFromPlanks(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, net.minecraft.world.inventory.AbstractContainerMenu menu, LocalPlayer player) {
        int plankSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.tags.ItemTags.PLANKS));
        if (plankSlot == -1) return false;

        gameMode.handleContainerInput(containerId, plankSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 2, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 5, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, plankSlot, 0, ContainerInput.PICKUP, player);

        gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
        clearGrid(gameMode, containerId, player);
        return true;
    }

    private int findItemSlotInMenu(net.minecraft.world.inventory.AbstractContainerMenu menu, java.util.function.Predicate<ItemStack> matcher) {
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && matcher.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private void closeContainerIfOpen(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        closeContainerIfOpen(player);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "craft " + targetSwordCount + " wooden sword(s) using crafting table";
    }
}

private class OffloadWoodenSwordsAction extends BuildAction {
    private enum Stage { OPEN_CHEST, OFFLOAD_SWORDS, CLOSE_CHEST }
    private Stage stage = Stage.OPEN_CHEST;

    private int timeoutTicks = 0;
    private static final int MAX_TIMEOUT_TICKS = 100;

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        stage = Stage.OPEN_CHEST;
        timeoutTicks = 0;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        timeoutTicks++;
        if (timeoutTicks >= MAX_TIMEOUT_TICKS) {
            player.sendSystemMessage(Component.literal("Offloading wooden swords timed out. Aborting sequence."));
            closeContainerIfOpen(player);
            sequenceAbortRequested = true;
            return true;
        }

        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode == null) return true;

        switch (stage) {
            case OPEN_CHEST: {
                BlockHitResult hitResult;
                if (lookedAtHit != null) {
                    hitResult = lookedAtHit;
                    lookedAtHit = null;
                } else {
                    BlockPos chestPos = player.blockPosition().relative(player.getDirection(), 1);
                    hitResult = findClickableFace(level, chestPos);
                    if (hitResult == null) {
                        player.sendSystemMessage(Component.literal("No chest in front to open."));
                        sequenceAbortRequested = true;
                        return true;
                    }
                }

                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                stage = Stage.OFFLOAD_SWORDS;
                return false;
            }

            case OFFLOAD_SWORDS: {
                if (player.containerMenu == player.inventoryMenu) {
                    return false; // Wait until chest UI opens
                }

                var containerMenu = player.containerMenu;
                int containerId = containerMenu.containerId;

                int playerSlotsStart = containerMenu.slots.size() - 36;
                int offloadedCount = 0;

                for (int containerSlot = playerSlotsStart; containerSlot < containerMenu.slots.size(); containerSlot++) {
                    ItemStack stack = containerMenu.getSlot(containerSlot).getItem();
                    
                    if (!stack.isEmpty() && stack.is(net.minecraft.world.item.Items.WOODEN_SWORD)) {
                        gameMode.handleContainerInput(containerId, containerSlot, 0, ContainerInput.QUICK_MOVE, player);
                        offloadedCount++;
                    }
                }

                player.sendSystemMessage(Component.literal(
                        "Offloaded " + offloadedCount + " wooden sword stack(s) into chest."));

                closeContainerIfOpen(player);
                stage = Stage.CLOSE_CHEST;
                return true;
            }

            case CLOSE_CHEST:
                return true;
        }

        return true;
    }

    private void closeContainerIfOpen(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        closeContainerIfOpen(player);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "offload all wooden swords into chest";
    }
}



private class CraftShovelsAction extends BuildAction {
    private final int targetShovelCount;
    private int shovelsCraftedSoFar = 0;

    private enum Stage { OPEN_TABLE, CRAFT, CLOSE_TABLE }
    private Stage stage = Stage.OPEN_TABLE;

    private int timeoutTicks = 0;
    private static final int MAX_TIMEOUT_TICKS = 100;

    CraftShovelsAction(int count) {
        this.targetShovelCount = Math.max(1, count);
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        stage = Stage.OPEN_TABLE;
        shovelsCraftedSoFar = 0;
        timeoutTicks = 0;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        timeoutTicks++;
        if (timeoutTicks >= MAX_TIMEOUT_TICKS) {
            player.sendSystemMessage(Component.literal("Crafting wooden shovels timed out. Aborting sequence."));
            closeContainerIfOpen(player);
            sequenceAbortRequested = true;
            return true;
        }

        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode == null) return true;

        switch (stage) {
            case OPEN_TABLE: {
                BlockHitResult hitResult;
                if (lookedAtHit != null) {
                    hitResult = lookedAtHit;
                    lookedAtHit = null;
                } else {
                    BlockPos tablePos = player.blockPosition().relative(player.getDirection(), 1);
                    hitResult = findClickableFace(level, tablePos);
                    if (hitResult == null) {
                        player.sendSystemMessage(Component.literal("No Crafting Table in front to open."));
                        sequenceAbortRequested = true;
                        return true;
                    }
                }

                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                stage = Stage.CRAFT;
                return false;
            }

            case CRAFT: {
                if (player.containerMenu == player.inventoryMenu) {
                    return false;
                }

                var menu = player.containerMenu;
                int containerId = menu.containerId;

                while (shovelsCraftedSoFar < targetShovelCount) {
                    clearGrid(gameMode, containerId, player);

                    if (!prepareShovelRecipe(gameMode, containerId, menu, player)) {
                        player.sendSystemMessage(Component.literal(
                                "Lacking materials to craft wooden shovel (" + shovelsCraftedSoFar + "/" + targetShovelCount + " crafted). Aborting sequence."));
                        closeContainerIfOpen(player);
                        sequenceAbortRequested = true;
                        return true;
                    }

                    // Shift-click result slot (0)
                    gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
                    shovelsCraftedSoFar++;
                }

                player.sendSystemMessage(Component.literal(
                        "Successfully crafted " + shovelsCraftedSoFar + " wooden shovel(s)."));
                
                closeContainerIfOpen(player);
                stage = Stage.CLOSE_TABLE;
                return true;
            }

            case CLOSE_TABLE:
                return true;
        }

        return true;
    }

    private void clearGrid(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, LocalPlayer player) {
        for (int i = 1; i <= 9; i++) {
            if (!player.containerMenu.getSlot(i).getItem().isEmpty()) {
                gameMode.handleContainerInput(containerId, i, 0, ContainerInput.QUICK_MOVE, player);
            }
        }
    }

    private boolean prepareShovelRecipe(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, net.minecraft.world.inventory.AbstractContainerMenu menu, LocalPlayer player) {
        int plankSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.tags.ItemTags.PLANKS));
        if (plankSlot == -1) {
            if (!craftPlanksFromLogs(gameMode, containerId, menu, player)) return false;
            plankSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.tags.ItemTags.PLANKS));
            if (plankSlot == -1) return false;
        }

        // Place 1 Plank in Slot 2
        gameMode.handleContainerInput(containerId, plankSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 2, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, plankSlot, 0, ContainerInput.PICKUP, player);

        int stickSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.world.item.Items.STICK));
        if (stickSlot == -1) {
            if (!craftSticksFromPlanks(gameMode, containerId, menu, player)) return false;
            stickSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.world.item.Items.STICK));
            if (stickSlot == -1) return false;
        }

        // Place 1 Stick in Slot 5 & 1 Stick in Slot 8
        gameMode.handleContainerInput(containerId, stickSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 5, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 8, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, stickSlot, 0, ContainerInput.PICKUP, player);

        return true;
    }

    private boolean craftPlanksFromLogs(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, net.minecraft.world.inventory.AbstractContainerMenu menu, LocalPlayer player) {
        int logSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.tags.ItemTags.LOGS));
        if (logSlot == -1) return false;

        gameMode.handleContainerInput(containerId, logSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 5, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, logSlot, 0, ContainerInput.PICKUP, player);

        gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
        clearGrid(gameMode, containerId, player);
        return true;
    }

    private boolean craftSticksFromPlanks(net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode, int containerId, net.minecraft.world.inventory.AbstractContainerMenu menu, LocalPlayer player) {
        int plankSlot = findItemSlotInMenu(menu, item -> item.is(net.minecraft.tags.ItemTags.PLANKS));
        if (plankSlot == -1) return false;

        gameMode.handleContainerInput(containerId, plankSlot, 0, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 2, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, 5, 1, ContainerInput.PICKUP, player);
        gameMode.handleContainerInput(containerId, plankSlot, 0, ContainerInput.PICKUP, player);

        gameMode.handleContainerInput(containerId, 0, 0, ContainerInput.QUICK_MOVE, player);
        clearGrid(gameMode, containerId, player);
        return true;
    }

    private int findItemSlotInMenu(net.minecraft.world.inventory.AbstractContainerMenu menu, java.util.function.Predicate<ItemStack> matcher) {
        for (int i = 10; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && matcher.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private void closeContainerIfOpen(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        closeContainerIfOpen(player);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "craft " + targetShovelCount + " wooden shovel(s) using crafting table";
    }
}

private class OffloadWoodenShovelsAction extends BuildAction {
    private enum Stage { OPEN_CHEST, OFFLOAD_SHOVELS, CLOSE_CHEST }
    private Stage stage = Stage.OPEN_CHEST;

    private int timeoutTicks = 0;
    private static final int MAX_TIMEOUT_TICKS = 100;

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        stage = Stage.OPEN_CHEST;
        timeoutTicks = 0;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        timeoutTicks++;
        if (timeoutTicks >= MAX_TIMEOUT_TICKS) {
            player.sendSystemMessage(Component.literal("Offloading wooden shovels timed out. Aborting sequence."));
            closeContainerIfOpen(player);
            sequenceAbortRequested = true;
            return true;
        }

        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode == null) return true;

        switch (stage) {
            case OPEN_CHEST: {
                BlockHitResult hitResult;
                if (lookedAtHit != null) {
                    hitResult = lookedAtHit;
                    lookedAtHit = null;
                } else {
                    BlockPos chestPos = player.blockPosition().relative(player.getDirection(), 1);
                    hitResult = findClickableFace(level, chestPos);
                    if (hitResult == null) {
                        player.sendSystemMessage(Component.literal("No chest in front to open."));
                        sequenceAbortRequested = true;
                        return true;
                    }
                }

                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                stage = Stage.OFFLOAD_SHOVELS;
                return false;
            }

            case OFFLOAD_SHOVELS: {
                // Wait until chest container menu opens
                if (player.containerMenu == player.inventoryMenu) {
                    return false;
                }

                var containerMenu = player.containerMenu;
                int containerId = containerMenu.containerId;

                // Single Chest = 27 slots (0..26), Double Chest = 54 slots (0..53)
                int playerSlotsStart = containerMenu.slots.size() - 36;
                int offloadedCount = 0;

                // Iterate through player inventory & hotbar slots within the container menu
                for (int containerSlot = playerSlotsStart; containerSlot < containerMenu.slots.size(); containerSlot++) {
                    ItemStack stack = containerMenu.getSlot(containerSlot).getItem();
                    
                    if (!stack.isEmpty() && stack.is(net.minecraft.world.item.Items.WOODEN_SHOVEL)) {
                        // Quick-move (Shift-click) shovel into chest
                        gameMode.handleContainerInput(containerId, containerSlot, 0, ContainerInput.QUICK_MOVE, player);
                        offloadedCount++;
                    }
                }

                player.sendSystemMessage(Component.literal(
                        "Offloaded " + offloadedCount + " wooden shovel stack(s) into chest."));

                closeContainerIfOpen(player);
                stage = Stage.CLOSE_CHEST;
                return true;
            }

            case CLOSE_CHEST:
                return true;
        }

        return true;
    }

    private void closeContainerIfOpen(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        closeContainerIfOpen(player);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "offload all wooden shovels into chest";
    }
}



private class ExtractFromChestAction extends BuildAction {
    private final int targetSlot;
    private static final int FULL_STACK = 64;

    private enum Stage { OPEN_CHEST, EXTRACT_ITEMS, CLOSE_CHEST }
    private Stage stage = Stage.OPEN_CHEST;
    
    private int timeoutTicks = 0;
    private static final int MAX_TIMEOUT_TICKS = 100; // ~5 seconds safety fallback

    ExtractFromChestAction(int slotOneIndexed) {
        this.targetSlot = Mth.clamp(slotOneIndexed - 1, 0, 8);
    }

    ExtractFromChestAction() {
        this.targetSlot = targetHotbarSlot;
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        stage = Stage.OPEN_CHEST;
        timeoutTicks = 0;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        timeoutTicks++;
        if (timeoutTicks >= MAX_TIMEOUT_TICKS) {
            player.sendSystemMessage(Component.literal("Chest extraction timed out. Aborting sequence."));
            closeChestIfOpen(player);
            sequenceAbortRequested = true;
            return true;
        }

        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode == null) return true;

        switch (stage) {
            case OPEN_CHEST: {
                ItemStack targetStack = player.getInventory().getItem(targetSlot);
                if (targetStack.isEmpty()) {
                    player.sendSystemMessage(Component.literal(
                            "Hotbar slot " + (targetSlot + 1) + " is empty — cannot infer item to extract. Aborting sequence."));
                    sequenceAbortRequested = true;
                    return true;
                }

                if (targetStack.getCount() >= FULL_STACK) {
                    player.sendSystemMessage(Component.literal(
                            "Hotbar slot " + (targetSlot + 1) + " is already full."));
                    return true;
                }

                BlockHitResult hitResult;
                if (lookedAtHit != null) {
                    hitResult = lookedAtHit;
                    lookedAtHit = null;
                } else {
                    BlockPos chestPos = player.blockPosition().relative(player.getDirection(), 1);
                    hitResult = findClickableFace(level, chestPos);
                    if (hitResult == null) {
                        player.sendSystemMessage(Component.literal("No chest in front to open."));
                        sequenceAbortRequested = true;
                        return true;
                    }
                }

                // Interact with chest
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                stage = Stage.EXTRACT_ITEMS;
                return false;
            }

            case EXTRACT_ITEMS: {
    // Wait until chest container menu opens
    if (player.containerMenu == player.inventoryMenu) {
        return false; // Still waiting for container to open
    }

    var containerMenu = player.containerMenu;
    int containerId = containerMenu.containerId;
    ItemStack targetStack = player.getInventory().getItem(targetSlot);
    net.minecraft.world.item.Item targetItem = targetStack.getItem();

    // Player hotbar slots start after chest slots in container layout
    // (Single Chest = 27 slots [0..26], Double Chest = 54 slots [0..53])
    int chestSize = containerMenu.slots.size() - 36; 
    int hotbarContainerSlotStart = containerMenu.slots.size() - 9;
    int targetContainerSlot = hotbarContainerSlotStart + targetSlot;

    while (player.getInventory().getItem(targetSlot).getCount() < FULL_STACK) {
        int matchingChestSlot = -1;

        for (int i = 0; i < chestSize; i++) {
            ItemStack chestStack = containerMenu.getSlot(i).getItem();
            if (!chestStack.isEmpty() && chestStack.getItem() == targetItem) {
                matchingChestSlot = i;
                break;
            }
        }

        if (matchingChestSlot == -1) {
            // FIX: Use targetStack.getHoverName().getString() here
            player.sendSystemMessage(Component.literal(
                    "Could not find enough " + targetStack.getHoverName().getString() + 
                    " in chest to reach 64 (currently " + 
                    player.getInventory().getItem(targetSlot).getCount() + "). Aborting sequence."));
            closeChestIfOpen(player);
            sequenceAbortRequested = true;
            return true;
        }

        // Pick up matching item stack from chest slot
        gameMode.handleContainerInput(containerId, matchingChestSlot, 0, ContainerInput.PICKUP, player);
        // Place items into hotbar slot
        gameMode.handleContainerInput(containerId, targetContainerSlot, 0, ContainerInput.PICKUP, player);

        // If leftover items in cursor, deposit back into chest slot
        if (!containerMenu.getCarried().isEmpty()) {
            gameMode.handleContainerInput(containerId, matchingChestSlot, 0, ContainerInput.PICKUP, player);
        }
    }

    player.sendSystemMessage(Component.literal(
            "Hotbar slot " + (targetSlot + 1) + " refilled to 64 from chest."));
    
    closeChestIfOpen(player);
    stage = Stage.CLOSE_CHEST;
    return true;
}

            case CLOSE_CHEST:
                return true;
        }

        return true;
    }

    private void closeChestIfOpen(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        closeChestIfOpen(player);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "refill hotbar slot " + (targetSlot + 1) + " to 64 from chest";
    }
}

private class PlaceFlowerPotAction extends BuildAction {
    private final int potSlot;
    private final int flowerSlot;
    private enum Stage { PLACE_POT, PLANT_FLOWER }
    private Stage stage = Stage.PLACE_POT;
    private BlockPos targetPotPos;
    private int ticks = 0;

    /**
     * @param potSlotOneIndexed Hotbar slot with Flower Pot (1-9)
     * @param flowerSlotOneIndexed Hotbar slot with Flower/Plant (1-9)
     */
    PlaceFlowerPotAction(int potSlotOneIndexed, int flowerSlotOneIndexed) {
        this.potSlot = Mth.clamp(potSlotOneIndexed - 1, 0, 8);
        this.flowerSlot = Mth.clamp(flowerSlotOneIndexed - 1, 0, 8);
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        stage = Stage.PLACE_POT;
        ticks = 0;
        targetPotPos = null;
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        ticks++;
        net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = 
                net.minecraft.client.Minecraft.getInstance().gameMode;
        if (gameMode == null) return true;

        switch (stage) {
            case PLACE_POT: {
                BlockHitResult hitResult;
                if (lookedAtHit != null) {
                    hitResult = lookedAtHit;
                    lookedAtHit = null;
                } else {
                    BlockPos target = player.blockPosition().relative(player.getDirection(), 1);
                    hitResult = findClickableFace(level, target);
                    if (hitResult == null) {
                        player.sendSystemMessage(Component.literal("No adjacent block to place pot against."));
                        return true;
                    }
                }

                targetPotPos = hitResult.getBlockPos().relative(hitResult.getDirection());

                if (!level.getBlockState(targetPotPos).canBeReplaced()) {
                    player.sendSystemMessage(Component.literal("Target position for flower pot is occupied."));
                    return true;
                }

                ItemStack potStack = player.getInventory().getItem(potSlot);
                if (potStack.isEmpty() || !potStack.is(net.minecraft.world.item.Items.FLOWER_POT)) {
                    player.sendSystemMessage(Component.literal("Hotbar slot " + (potSlot + 1) + " does not have a Flower Pot."));
                    return true;
                }

                // Place Flower Pot
                int previousSlot = player.getInventory().getSelectedSlot();
                player.getInventory().setSelectedSlot(potSlot);
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                player.getInventory().setSelectedSlot(previousSlot);

                stage = Stage.PLANT_FLOWER;
                return false;
            }

            case PLANT_FLOWER: {
                if (targetPotPos == null) return true;

                // Wait 1 tick for server/world block state update to reflect the pot
                if (ticks < 2) return false;

                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(targetPotPos);
                if (!state.is(net.minecraft.world.level.block.Blocks.FLOWER_POT)) {
                    player.sendSystemMessage(Component.literal("Flower pot placement not recognized yet."));
                    return true;
                }

                ItemStack flowerStack = player.getInventory().getItem(flowerSlot);
                if (flowerStack.isEmpty()) {
                    player.sendSystemMessage(Component.literal("Hotbar slot " + (flowerSlot + 1) + " is empty; pot placed empty."));
                    return true;
                }

                // Right click flower pot with plant item
                BlockHitResult potHit = buildHitResult(targetPotPos, Direction.UP);
                int previousSlot = player.getInventory().getSelectedSlot();
                player.getInventory().setSelectedSlot(flowerSlot);
                gameMode.useItemOn(player, InteractionHand.MAIN_HAND, potHit);
                player.getInventory().setSelectedSlot(previousSlot);

                return true;
            }
        }

        return true;
    }

    @Override
    String describe() {
        return "place pot from slot " + (potSlot + 1) + " and plant flower from slot " + (flowerSlot + 1);
    }
}









    private class EatAction extends BuildAction {
    private final int slotToUse;
    private int startUseDuration = -1;
    private int ticks = 0;
    private static final int TIMEOUT_TICKS = 80; // Safety fallback (~4 seconds max)

    EatAction(int slotOneIndexed) {
        this.slotToUse = Mth.clamp(slotOneIndexed - 1, 0, 8);
    }

    EatAction() {
        this.slotToUse = targetHotbarSlot;
    }

    @Override
    void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        ticks = 0;
        startUseDuration = -1;

        ItemStack stack = player.getInventory().getItem(slotToUse);

        // Check if the slot actually contains a food/edible item
        if (stack.isEmpty() || !stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
            player.sendSystemMessage(Component.literal("Slot " + (slotToUse + 1) + " does not contain edible food. Skipping eat action."));
            return;
        }

        // Switch to the target hotbar slot
        player.getInventory().setSelectedSlot(slotToUse);

        // Press and hold the use (right-click) key
        options.keyUse.setDown(true);
    }

    @Override
    boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        ticks++;

        ItemStack stack = player.getInventory().getItem(slotToUse);

        // Abort if item ran out or isn't food anymore
        if (stack.isEmpty() || !stack.has(net.minecraft.core.component.DataComponents.FOOD)) {
            return true;
        }

        // Track when eating actually starts
        if (player.isUsingItem()) {
            if (startUseDuration == -1) {
                startUseDuration = player.getUseItemRemainingTicks();
            }

            // Player finished eating when remaining ticks reach 0 or item usage ends
            if (player.getUseItemRemainingTicks() <= 0) {
                return true;
            }
        } else if (ticks > 5 && startUseDuration == -1) {
            // If after 5 ticks player isn't using item, player's food bar might be full or item can't be eaten
            player.sendSystemMessage(Component.literal("Cannot eat from slot " + (slotToUse + 1) + " (hunger might be full)."));
            return true;
        }

        // Timeout guard to prevent getting stuck
        return ticks >= TIMEOUT_TICKS;
    }

    @Override
    void end(LocalPlayer player, Level level, net.minecraft.client.Options options) {
        // Release the right-click key when finished or interrupted
        options.keyUse.setDown(false);
        super.end(player, level, options);
    }

    @Override
    String describe() {
        return "eat food from hotbar slot " + (slotToUse + 1);
    }
}

    private class RefillSlotAction extends BuildAction {
        private final int targetSlot;
        private static final int FULL_STACK_SIZE = 64;

        RefillSlotAction(int targetSlot) {
            this.targetSlot = Mth.clamp(targetSlot, 0, 8);
        }

        RefillSlotAction() {
            this.targetSlot = targetHotbarSlot;
        }

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            ItemStack targetStack = player.getInventory().getItem(targetSlot);

            if (targetStack.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        "Hotbar slot " + (targetSlot + 1) + " is empty — cannot infer item type to refill. Aborting sequence."));
                sequenceAbortRequested = true;
                return true;
            }

            if (targetStack.getCount() >= FULL_STACK_SIZE) {
                return true;
            }

            net.minecraft.world.item.Item targetItem = targetStack.getItem();
            net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode = net.minecraft.client.Minecraft.getInstance().gameMode;
            if (gameMode == null) return true;

            int containerId = player.inventoryMenu.containerId;
            int targetContainerSlot = targetSlot + 36;

            while (player.getInventory().getItem(targetSlot).getCount() < FULL_STACK_SIZE) {
                int matchingInventorySlot = -1;

                for (int i = 9; i < 36; i++) {
                    ItemStack invStack = player.getInventory().getItem(i);
                    if (!invStack.isEmpty() && invStack.getItem() == targetItem) {
                        matchingInventorySlot = i;
                        break;
                    }
                }

                if (matchingInventorySlot == -1) {
                    int currentCount = player.getInventory().getItem(targetSlot).getCount();
                    player.sendSystemMessage(Component.literal(
                            "Could not reach 64 items in hotbar slot " + (targetSlot + 1) + 
                            " (currently " + currentCount + "/" + FULL_STACK_SIZE + "). Aborting sequence."));
                    sequenceAbortRequested = true;
                    return true;
                }

                int sourceContainerSlot = matchingInventorySlot;

                gameMode.handleContainerInput(containerId, sourceContainerSlot, 0, ContainerInput.PICKUP, player);
                gameMode.handleContainerInput(containerId, targetContainerSlot, 0, ContainerInput.PICKUP, player);

                if (!player.inventoryMenu.getCarried().isEmpty()) {
                    gameMode.handleContainerInput(containerId, sourceContainerSlot, 0, ContainerInput.PICKUP, player);
                }
            }

            player.sendSystemMessage(Component.literal(
                    "Hotbar slot " + (targetSlot + 1) + " successfully refilled to 64 items."));
            return true;
        }

        @Override
        String describe() {
            return "refill hotbar slot " + (targetSlot + 1) + " to 64 items";
        }
    }

    private class SelectSlotAction extends BuildAction {
        private final int slotIndex;

        SelectSlotAction(int slotIndex) {
            this.slotIndex = Mth.clamp(slotIndex, 0, 8);
        }

        @Override
        void begin(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            targetHotbarSlot = slotIndex;
            player.sendSystemMessage(Component.literal("Active placement slot set to hotbar " + (targetHotbarSlot + 1)));
        }

        @Override
        boolean tick(LocalPlayer player, Level level, net.minecraft.client.Options options) {
            return true;
        }

        @Override
        String describe() {
            return "select hotbar slot " + (slotIndex + 1);
        }
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
                
                ItemStack stack = player.getInventory().getItem(targetHotbarSlot);
                if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                    player.sendSystemMessage(Component.literal("Hotbar slot " + (targetHotbarSlot + 1) + " doesn't have a placeable block."));
                    placed = true;
                } else {
                    BlockHitResult hitResult = buildHitResult(targetBelow, Direction.UP);
                    int previousSlot = player.getInventory().getSelectedSlot();
                    player.getInventory().setSelectedSlot(targetHotbarSlot);
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

            ItemStack stack = player.getInventory().getItem(targetHotbarSlot);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                player.sendSystemMessage(Component.literal("Hotbar slot " + (targetHotbarSlot + 1) + " doesn't have a placeable block in it."));
                return true;
            }

            int previousSlot = player.getInventory().getSelectedSlot();
            player.getInventory().setSelectedSlot(targetHotbarSlot);
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

            ItemStack stack = player.getInventory().getItem(targetHotbarSlot);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                player.sendSystemMessage(Component.literal("Hotbar slot " + (targetHotbarSlot + 1) + " doesn't have blocks remaining."));
                return false;
            }

            previousSlot = player.getInventory().getSelectedSlot();
            player.getInventory().setSelectedSlot(targetHotbarSlot);
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

        int hotbarSlot = targetHotbarSlot;
        ItemStack slotStack = player.getInventory().getItem(hotbarSlot);
        if (slotStack.isEmpty() || !(slotStack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
            player.sendSystemMessage(Component.literal("Hotbar slot " + (hotbarSlot + 1) + " doesn't have a placeable block in it."));
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
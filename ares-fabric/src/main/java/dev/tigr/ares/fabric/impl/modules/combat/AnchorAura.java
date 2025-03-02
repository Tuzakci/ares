package dev.tigr.ares.fabric.impl.modules.combat;

import com.google.common.collect.Streams;
import dev.tigr.ares.core.feature.module.Category;
import dev.tigr.ares.core.feature.module.Module;
import dev.tigr.ares.core.setting.Setting;
import dev.tigr.ares.core.setting.settings.BooleanSetting;
import dev.tigr.ares.core.setting.settings.EnumSetting;
import dev.tigr.ares.core.setting.settings.numerical.DoubleSetting;
import dev.tigr.ares.core.setting.settings.numerical.FloatSetting;
import dev.tigr.ares.core.setting.settings.numerical.IntegerSetting;
import dev.tigr.ares.core.util.global.ReflectionHelper;
import dev.tigr.ares.core.util.global.Utils;
import dev.tigr.ares.core.util.render.Color;
import dev.tigr.ares.fabric.event.client.PacketEvent;
import dev.tigr.ares.fabric.event.player.DestroyBlockEvent;
import dev.tigr.ares.fabric.utils.*;
import dev.tigr.simpleevents.listener.EventHandler;
import dev.tigr.simpleevents.listener.EventListener;
import dev.tigr.simpleevents.listener.Priority;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.EnchantedGoldenAppleItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

import static dev.tigr.ares.fabric.impl.modules.combat.CrystalAura.getDamage;
import static dev.tigr.ares.fabric.impl.modules.combat.CrystalAura.rayTrace;

/**
 * @author Tigermouthbear 2/6/20
 */
@Module.Info(name = "AnchorAura", description = "Automatically places and explodes respawn anchors in the overworld", category = Category.COMBAT)
public class AnchorAura extends Module {
    //TODO: Chain delays so that break delay starts after placing has finished and vice versa? Give every anchor placed a seperate timer so they're guaranteed to break with correct timing?
    // As it stands the way break delay is handled right now makes it basically useless because either the anchor is broken right after it is placed, or it just spams anchors everywhere.
    private final Setting<Target> targetSetting = register(new EnumSetting<>("Target", Target.CLOSEST));
    private final Setting<Mode> placeMode = register(new EnumSetting<>("Place Mode", Mode.DAMAGE));
    private final Setting<Boolean> preventSuicide = register(new BooleanSetting("Prevent Suicide", true));
    private final Setting<Boolean> noGappleSwitch = register(new BooleanSetting("No Gapple Switch", false));
    private final Setting<Integer> placeDelay = register(new IntegerSetting("Place Delay", 7, 0, 15));
    private final Setting<Integer> breakDelay = register(new IntegerSetting("Break Delay", 5, 0, 15));
    private final Setting<Float> minDamage = register(new FloatSetting("Minimum Damage", 7.5f, 0, 15));
    private final Setting<Double> placeRange = register(new DoubleSetting("Place Range", 5, 0, 10));
    private final Setting<Double> breakRange = register(new DoubleSetting("Break Range", 5, 0, 10));
    private final Setting<Boolean> sync = register(new BooleanSetting("Sync", true));
    private final Setting<Boolean> antiSurround = register(new BooleanSetting("AntiSurround", true));
    private final Setting<Rotations> rotateMode = register(new EnumSetting<>("Rotations", Rotations.PACKET));

    private final Setting<Boolean> showRenderOptions = register(new BooleanSetting("Show Render Options", false));
    private final Setting<Float> colorRed = register(new FloatSetting("Red", 1, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> colorGreen = register(new FloatSetting("Green", 1, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> colorBlue = register(new FloatSetting("Blue", 0.45f, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> fillAlpha = register(new FloatSetting("Fill Alpha", 0.24f, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> boxAlpha = register(new FloatSetting("Line Alpha", 1f, 0, 1)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> lineThickness = register(new FloatSetting("Line Weight", 2f, 0f, 10f)).setVisibility(showRenderOptions::getValue);
    private final Setting<Float> expandRender = register(new FloatSetting("Box Scale", 0f, -0.12f, 0.06f)).setVisibility(showRenderOptions::getValue);

    enum Mode { DAMAGE, DISTANCE }
    enum Target { CLOSEST, MOST_DAMAGE }
    enum Rotations { PACKET, REAL, NONE }

    private long renderTimer = -1;
    private final Timer placeTimer = new Timer();
    private final Timer breakTimer = new Timer();
    private double[] rotations = null;
    public BlockPos target = null;
    private Stack<BlockPos> placed = new Stack<>();

    @Override
    public void onTick() {
        if(!MC.world.getDimension().isRespawnAnchorWorking()) run();
    }

    private void run() {
        // reset rotations
        if(rotations != null) rotations = null;

        // cleanup render
        if((System.nanoTime() / 1000000) - renderTimer >= 3000) {
            target = null;
            renderTimer = System.nanoTime() / 1000000;
        }

        // do logic
        place();
        explode();

        // rotate for actual mode
        if(rotations != null && rotateMode.getValue() == Rotations.REAL) {
            MC.player.pitch = (float) rotations[1];
            MC.player.yaw = (float) rotations[0];
        }
    }

    private void place() {
        if(placeTimer.passedTicks(placeDelay.getValue())) {
            // if no gapple switch and player is holding apple
            if(noGappleSwitch.getValue() && MC.player.inventory.getMainHandStack().getItem() instanceof EnchantedGoldenAppleItem) {
                if(target != null) target = null;
                return;
            }

            // find best crystal spot
            BlockPos target = getBestPlacement();
            if(target == null) return;

            placeAnchor(target);
            placeTimer.reset();
        }
    }

    private void placeAnchor(BlockPos pos) {
        // switch to crystals if not holding
        if(MC.player.inventory.getMainHandStack().getItem() != Items.RESPAWN_ANCHOR) {
            int slot = InventoryUtils.findItemInHotbar(Items.RESPAWN_ANCHOR);
            if(slot != -1) {
                MC.player.inventory.selectedSlot = slot;
                if(sync.getValue()) MC.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket());
            }
        }

        // place
        WorldUtils.placeBlockMainHand(pos, shouldRotate());
        placed.add(pos);

        // set render pos
        target = pos;
    }

    private void explode() {
        if(!breakTimer.passedTicks(breakDelay.getValue()) || placed.isEmpty()) return;
        BlockPos pos = placed.pop();
        Vec3d vec = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        // click glowstone
        if(MC.player.inventory.getMainHandStack().getItem() != Items.GLOWSTONE) {
            int slot = InventoryUtils.findItemInHotbar(Items.GLOWSTONE);
            if(slot != -1) {
                MC.player.inventory.selectedSlot = slot;
                if(sync.getValue()) MC.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket());
            }
        }
        MC.interactionManager.interactBlock(MC.player, MC.world, Hand.MAIN_HAND, new BlockHitResult(vec, Direction.UP, pos, true));

        // click anchor without glowstone
        if(MC.player.inventory.getMainHandStack().getItem() == Items.GLOWSTONE) {
            int slot = InventoryUtils.findItemInHotbar(Items.RESPAWN_ANCHOR);
            if(slot != -1) {
                MC.player.inventory.selectedSlot = slot;
                if(sync.getValue()) MC.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket());
            }
        }
        MC.interactionManager.interactBlock(MC.player, MC.world, Hand.MAIN_HAND, new BlockHitResult(vec, Direction.UP, pos, true));

        // spoof rotations
        rotations = WorldUtils.calculateLookAt(vec.x, vec.y, vec.z, MC.player);

        // reset timer
        breakTimer.reset();
    }

    @EventHandler
    public EventListener<DestroyBlockEvent> destroyBlockEvent = new EventListener<>(Priority.HIGHEST, event -> {
        // place crystal at broken block place
        if(antiSurround.getValue()) {
            BlockPos pos = event.getPos().down();
            if(isPartOfHole(pos) && canAnchorBePlacedHere(pos)) placeAnchor(pos);
        }
    });

    @EventHandler
    public EventListener<PacketEvent.Sent> packetSentEvent = new EventListener<>(event -> {
        // rotation spoofing
        if(event.getPacket() instanceof PlayerMoveC2SPacket && rotations != null && rotateMode.getValue() == Rotations.PACKET) {
            ReflectionHelper.setPrivateValue(PlayerMoveC2SPacket.class, event.getPacket(), (float) rotations[1], "pitch", "field_12885");
            ReflectionHelper.setPrivateValue(PlayerMoveC2SPacket.class, event.getPacket(), (float) rotations[0], "yaw", "field_12887");
        }
    });

    // draw target
    @Override
    public void onRender3d() {
        if(target != null) {
            Color fillColor = new Color(
                    colorRed.getValue(),
                    colorGreen.getValue(),
                    colorBlue.getValue(),
                    fillAlpha.getValue()
            );
            Color outlineColor = new Color(
                    colorRed.getValue(),
                    colorGreen.getValue(),
                    colorBlue.getValue(),
                    boxAlpha.getValue()
            );
            RenderUtils.renderBlock(
                    target,
                    fillColor,
                    outlineColor,
                    lineThickness.getValue(),
                    expandRender.getValue()
            );
        }
    }

    private boolean isPartOfHole(BlockPos pos) {
        List<Entity> entities = new ArrayList<>();
        entities.addAll(MC.world.getOtherEntities(MC.player, new Box(pos.add(1, 0, 0))));
        entities.addAll(MC.world.getOtherEntities(MC.player, new Box(pos.add(-1, 0, 0))));
        entities.addAll(MC.world.getOtherEntities(MC.player, new Box(pos.add(0, 0, 1))));
        entities.addAll(MC.world.getOtherEntities(MC.player, new Box(pos.add(0, 0, -1))));
        return entities.stream().anyMatch(entity -> entity instanceof PlayerEntity
                || entity instanceof OtherClientPlayerEntity);
    }

    private boolean canBreakAnchor(BlockPos pos) {
        return MC.player.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ()) <= breakRange.getValue() * breakRange.getValue() // check range
        && !(MC.player.getHealth() - getDamage(new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), MC.player) <= 1 && preventSuicide.getValue()); // check suicide
    }

    private BlockPos getBestPlacement() {
        double bestScore = 69420;
        BlockPos target = null;
        for(Entity targetedPlayer: getTargets()) {
            // find best location to place
            List<BlockPos> targetsBlocks = getPlaceableBlocks(targetedPlayer);
            List<BlockPos> blocks = getPlaceableBlocks(MC.player);

            for(BlockPos pos: blocks) {
                if(!targetsBlocks.contains(pos) || (double) getDamage(new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), targetedPlayer) < minDamage.getValue())
                    continue;

                if(!MC.world.canPlace(Blocks.OBSIDIAN.getDefaultState(), pos, ShapeContext.absent())) continue;

                double score = getScore(pos, targetedPlayer);

                if(target == null || (score < bestScore && score != -1)) {
                    target = pos;
                    bestScore = score;
                }
            }
        }
        return target;
    }

    // utils
    private double getScore(BlockPos pos, Entity player) {
        double score;
        if(placeMode.getValue() == Mode.DISTANCE) {
            score = Math.abs(player.getY() - pos.up().getY())
                    + Math.abs(player.getX() - pos.getX())
                    + Math.abs(player.getZ() - pos.getZ());

            if(rayTrace(
                    new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5),
                    new Vec3d(player.getPos().x,
                            player.getPos().y,
                            player.getPos().z))

                    == HitResult.Type.BLOCK) score = -1;
        } else {
            score = 200 - getDamage(new Vec3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), player);
        }

        return score;
    }

    private List<Entity> getTargets() {
        List<Entity> targets = new ArrayList<>();

        if(targetSetting.getValue() == Target.CLOSEST) {
            targets.addAll(Streams.stream(MC.world.getEntities()).filter(this::isValidTarget).collect(Collectors.toList()));
            targets.sort(Comparators.entityDistance);
        } else if(targetSetting.getValue() == Target.MOST_DAMAGE) {
            for(Entity entity: MC.world.getEntities()) {
                if(!isValidTarget(entity))
                    continue;
                targets.add(entity);
            }
        }

        return targets;
    }

    private boolean isValidTarget(Entity entity) {
        return WorldUtils.isValidTarget(entity, Math.max(placeRange.getValue(), breakRange.getValue()) + 8);
    }

    private List<BlockPos> getPlaceableBlocks(Entity player) {
        List<BlockPos> square = new ArrayList<>();

        int range = (int) Utils.roundDouble(placeRange.getValue(), 0);
        BlockPos pos = player.getBlockPos();
        for(int x = -range; x <= range; x++)
            for(int y = -range; y <= range; y++)
                for(int z = -range; z <= range; z++)
                    square.add(pos.add(x, y, z));

        return square.stream().filter(blockPos -> canAnchorBePlacedHere(blockPos) && MC.player.squaredDistanceTo(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5) <= (range * range)).collect(Collectors.toList());
    }

    private boolean canAnchorBePlacedHere(BlockPos pos) {
        return MC.world.getBlockState(pos).isAir() || MC.world.getBlockState(pos).getMaterial().isLiquid();
    }

    private boolean shouldRotate() {
        if (rotateMode.getValue() == Rotations.NONE)
            return false;
        else return true;
    }
}

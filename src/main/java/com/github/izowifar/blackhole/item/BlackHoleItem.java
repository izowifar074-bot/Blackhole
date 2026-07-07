package com.github.izowifar.blackhole.item;

import com.github.izowifar.blackhole.BlackholeMod;
import com.github.izowifar.blackhole.entity.BlackHoleEntity;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class BlackHoleItem extends Item {
    /** How far the spawn point may be from the player's eyes, in blocks. */
    public static final double MAX_CAST_DISTANCE = 96.0;
    public static final int COOLDOWN_TICKS = 20 * 20;

    public BlackHoleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getViewVector(1.0f);
            Vec3 reach = eye.add(look.scale(MAX_CAST_DISTANCE));
            BlockHitResult hit = level.clip(new ClipContext(eye, reach,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            // Pull the center slightly back out of the wall so the horizon sphere is visible
            Vec3 spot = hit.getType() == HitResult.Type.MISS
                    ? reach
                    : hit.getLocation().subtract(look.scale(0.75));

            BlackHoleEntity hole = new BlackHoleEntity(BlackholeMod.BLACK_HOLE, level);
            hole.setPos(spot.x, spot.y, spot.z);
            serverLevel.addFreshEntity(hole);

            serverLevel.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    spot.x, spot.y, spot.z, 120, 1.6, 1.6, 1.6, 0.6);
            serverLevel.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0f, 1.0f, 1.0f),
                    spot.x, spot.y, spot.z, 1, 0, 0, 0, 0);
            level.playSound(null, spot.x, spot.y, spot.z,
                    SoundEvents.END_PORTAL_SPAWN, SoundSource.NEUTRAL, 2.0f, 0.55f);

            player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
            stack.consume(1, player);
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResult.SUCCESS;
    }
}

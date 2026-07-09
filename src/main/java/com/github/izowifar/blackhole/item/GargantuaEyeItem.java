package com.github.izowifar.blackhole.item;

import com.github.izowifar.blackhole.BlackholeMod;
import com.github.izowifar.blackhole.entity.GargantuaEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class GargantuaEyeItem extends Item {
    public static final int COOLDOWN_TICKS = 20 * 120;

    public GargantuaEyeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            GargantuaEntity gargantua = new GargantuaEntity(BlackholeMod.GARGANTUA, level);
            gargantua.setPos(player.getX(), player.getY(), player.getZ());
            gargantua.setYRot(player.getYRot());
            serverLevel.addFreshEntity(gargantua);

            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 3.0f, 0.45f);

            player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
            stack.consume(1, player);
            player.awardStat(Stats.ITEM_USED.get(this));
        }
        return InteractionResult.SUCCESS;
    }
}

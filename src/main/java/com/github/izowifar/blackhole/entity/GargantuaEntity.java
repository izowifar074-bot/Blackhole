package com.github.izowifar.blackhole.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The sky-scale Gargantua event. The entity itself is an invisible anchor near
 * the summoner; the renderer paints the black hole onto the sky in the fixed
 * direction given by this entity's yaw. The choreography timeline is shared
 * between server (night fall, sounds, the final devouring) and client
 * (visuals) through the tick constants below.
 *
 * <p>Timeline: the hole fades in flat ("2D") while the ring's crossing line
 * sweeps so the lower-right lobe overtakes the upper-left; the view then
 * rotates around the ring axis while the hole swells toward the player;
 * finally it rotates back until the ring bisects the shadow evenly, and the
 * white-out detonation consumes every non-player entity nearby.
 */
public class GargantuaEntity extends Entity {
    /** Fade/scale-in of the flat apparition. */
    public static final int EMERGE_END = 60;
    /** "2D" phase: lobe swap via the tilt sweep, size nearly constant. */
    public static final int PLANE_END = 400;
    /** Rotation around the ring axis plus the exponential zoom (p4-p8). */
    public static final int SWING_END = 800;
    /** Rotate back until the ring evenly bisects the shadow (p10). */
    public static final int SETTLE_END = 950;
    public static final int FLASH_PEAK = 1000;
    public static final int DEVOUR_TICK = 1000;
    public static final int LIFETIME = 1060;
    public static final double DEVOUR_RANGE = 256.0;

    private static final EntityDataAccessor<Integer> DATA_AGE =
            SynchedEntityData.defineId(GargantuaEntity.class, EntityDataSerializers.INT);

    private int age;

    public GargantuaEntity(EntityType<? extends GargantuaEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public int getAgeTicks() {
        return this.age;
    }

    /** Apparent angular radius of the event-horizon shadow, in degrees. */
    public static float apparentAngleAt(float t) {
        if (t <= 0) {
            return 0.0f;
        }
        if (t < EMERGE_END) {
            float p = t / EMERGE_END;
            return 3.0f * p * p * (3.0f - 2.0f * p);
        }
        if (t < PLANE_END) {
            float p = (t - EMERGE_END) / (PLANE_END - EMERGE_END);
            return Mth.lerp(p * p * (3.0f - 2.0f * p), 3.0f, 4.5f);
        }
        if (t < SWING_END) {
            float p = (t - PLANE_END) / (SWING_END - PLANE_END);
            float s = p * p * (3.0f - 2.0f * p);
            // exponential swell: the hole closes in on the viewer
            return 4.5f * (float) Math.pow(28.0 / 4.5, s);
        }
        if (t < SETTLE_END) {
            float p = (t - SWING_END) / (SETTLE_END - SWING_END);
            return Mth.lerp(p * p * (3.0f - 2.0f * p), 28.0f, 34.0f);
        }
        return 34.0f;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_AGE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel) {
            serverTick(serverLevel);
        } else {
            clientTick();
        }
    }

    private void serverTick(ServerLevel serverLevel) {
        this.age++;
        if (this.age == 1 || this.age % 10 == 0) {
            this.entityData.set(DATA_AGE, this.age);
        }

        // night falls as the apparition takes hold
        if (this.age == 40) {
            serverLevel.setDayTime(18000L);
        }

        if (this.age == 1) {
            playAt(serverLevel, SoundEvents.END_PORTAL_SPAWN, 3.0f, 0.5f);
        }
        if (this.age % 80 == 0 && this.age < SETTLE_END) {
            float swell = 2.0f + this.age / 300.0f;
            playAt(serverLevel, SoundEvents.BEACON_AMBIENT, swell, 0.25f);
            playAt(serverLevel, SoundEvents.PORTAL_AMBIENT, swell * 0.8f, 0.3f);
        }
        if (this.age == PLANE_END) {
            playAt(serverLevel, SoundEvents.WITHER_SPAWN, 5.0f, 0.55f);
        }
        if (this.age == SETTLE_END - 50) {
            playAt(serverLevel, SoundEvents.WARDEN_SONIC_CHARGE, 6.0f, 0.9f);
        }
        if (this.age == DEVOUR_TICK) {
            devour(serverLevel);
            playAt(serverLevel, SoundEvents.GENERIC_EXPLODE.value(), 8.0f, 0.5f);
            playAt(serverLevel, SoundEvents.LIGHTNING_BOLT_THUNDER, 8.0f, 0.55f);
            playAt(serverLevel, SoundEvents.WARDEN_SONIC_BOOM, 6.0f, 0.7f);
        }
        if (this.age >= LIFETIME) {
            discard();
        }
    }

    private void playAt(ServerLevel serverLevel, net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
        serverLevel.playSound(null, getX(), getY(), getZ(), sound, SoundSource.HOSTILE, volume, pitch);
    }

    private void devour(ServerLevel serverLevel) {
        Vec3 c = position();
        AABB box = new AABB(c, c).inflate(DEVOUR_RANGE);
        for (Entity e : serverLevel.getEntities(this, box, entity ->
                !(entity instanceof Player) && !(entity instanceof GargantuaEntity) && entity.isAlive())) {
            serverLevel.sendParticles(ParticleTypes.EXPLOSION,
                    e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ(), 1, 0, 0, 0, 0);
            serverLevel.sendParticles(ParticleTypes.PORTAL,
                    e.getX(), e.getY() + e.getBbHeight() * 0.5, e.getZ(), 8, 0.2, 0.3, 0.2, 0.15);
            e.discard();
        }
    }

    private void clientTick() {
        int synced = this.entityData.get(DATA_AGE);
        this.age = Math.max(this.age + 1, synced);

        // Matter streaming skyward while the hole feeds
        if (this.age > 150 && this.age < DEVOUR_TICK) {
            Vec3 c = position();
            for (int i = 0; i < 3; i++) {
                double px = c.x + (this.random.nextDouble() - 0.5) * 48.0;
                double py = c.y + this.random.nextDouble() * 6.0;
                double pz = c.z + (this.random.nextDouble() - 0.5) * 48.0;
                double vy = 0.3 + this.random.nextDouble() * 0.6;
                level().addParticle(this.random.nextInt(5) == 0 ? ParticleTypes.FLAME : ParticleTypes.END_ROD,
                        px, py, pz,
                        (this.random.nextDouble() - 0.5) * 0.06, vy, (this.random.nextDouble() - 0.5) * 0.06);
            }
        }
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 1024.0 * 1024.0;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.age = input.getIntOr("Age", 0);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("Age", this.age);
    }
}

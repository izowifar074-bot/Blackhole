package com.github.izowifar.blackhole.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A black hole with a three-phase lifecycle: grow from nothing, sustain at full
 * size while devouring everything in reach, then violently collapse and detonate.
 *
 * <p>All visual sizing derives from {@link #radiusAt(float)} so the renderer and
 * the gameplay logic always agree on how big the hole currently is.
 */
public class BlackHoleEntity extends Entity {
    public static final int GROW_TICKS = 150;
    public static final int SUSTAIN_TICKS = 160;
    public static final int COLLAPSE_TICKS = 18;
    public static final int LIFETIME = GROW_TICKS + SUSTAIN_TICKS + COLLAPSE_TICKS;

    /** Visual radius of the event horizon at full size, in blocks. */
    public static final float MAX_RADIUS = 3.0f;
    /** Blocks are devoured out to radius * this factor. */
    public static final float DEVOUR_RADIUS_SCALE = 2.2f;
    /** Entities are pulled from radius * this factor. */
    public static final float PULL_RADIUS_SCALE = 6.0f;

    private static final EntityDataAccessor<Integer> DATA_AGE =
            SynchedEntityData.defineId(BlackHoleEntity.class, EntityDataSerializers.INT);

    private int age;

    public BlackHoleEntity(EntityType<? extends BlackHoleEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public int getAgeTicks() {
        return this.age;
    }

    /** Event horizon radius at the given lifetime tick (fractional for render interpolation). */
    public static float radiusAt(float t) {
        if (t <= 0) {
            return 0.0f;
        }
        if (t < GROW_TICKS) {
            float p = t / GROW_TICKS;
            float s = p * p * (3.0f - 2.0f * p);
            return MAX_RADIUS * s;
        }
        float u = t - GROW_TICKS;
        if (u < SUSTAIN_TICKS) {
            return MAX_RADIUS * (1.0f + 0.03f * Mth.sin(t * 0.35f));
        }
        float v = Math.min(1.0f, (u - SUSTAIN_TICKS) / COLLAPSE_TICKS);
        float k = 1.0f - v;
        return MAX_RADIUS * (0.25f + 0.75f * k * k);
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

        float radius = radiusAt(this.age);
        pullEntities(serverLevel, radius);
        if (this.age > 20) {
            devourBlocks(serverLevel, radius);
        }
        consumeAtHorizon(serverLevel, radius);

        if (this.age % 36 == 0) {
            serverLevel.playSound(null, getX(), getY(), getZ(),
                    SoundEvents.BEACON_AMBIENT, SoundSource.HOSTILE,
                    4.0f, 0.35f + this.random.nextFloat() * 0.1f);
        }
        if (this.age == GROW_TICKS + SUSTAIN_TICKS) {
            serverLevel.playSound(null, getX(), getY(), getZ(),
                    SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 5.0f, 1.1f);
        }
        if (this.age >= LIFETIME) {
            detonate(serverLevel);
        }
    }

    private void clientTick() {
        int synced = this.entityData.get(DATA_AGE);
        this.age = Math.max(this.age + 1, synced);

        float radius = radiusAt(this.age);
        if (radius < 0.1f) {
            return;
        }
        // Sparks spiralling into the hole
        Vec3 c = position();
        for (int i = 0; i < 3; i++) {
            double ang = this.random.nextDouble() * Math.PI * 2.0;
            double dist = radius * (1.8 + this.random.nextDouble() * 2.6);
            double px = c.x + Math.cos(ang) * dist;
            double py = c.y + (this.random.nextDouble() - 0.5) * radius * 1.2;
            double pz = c.z + Math.sin(ang) * dist;
            double inX = c.x - px;
            double inY = c.y - py;
            double inZ = c.z - pz;
            double inLen = Math.sqrt(inX * inX + inY * inY + inZ * inZ);
            double vx = -Math.sin(ang) * 0.30 + inX / inLen * 0.22;
            double vy = inY / inLen * 0.10;
            double vz = Math.cos(ang) * 0.30 + inZ / inLen * 0.22;
            level().addParticle(this.random.nextInt(4) == 0 ? ParticleTypes.FLAME : ParticleTypes.END_ROD,
                    px, py, pz, vx, vy, vz);
        }
    }

    private void pullEntities(ServerLevel serverLevel, float radius) {
        double pullRange = Math.max(5.0, radius * PULL_RADIUS_SCALE);
        Vec3 c = position();
        AABB box = new AABB(c, c).inflate(pullRange);
        for (Entity e : serverLevel.getEntities(this, box, this::affectsEntity)) {
            Vec3 diff = c.subtract(e.position());
            double dist = diff.length();
            if (dist > pullRange || dist < 1.0e-4) {
                continue;
            }
            double norm = 1.0 - dist / pullRange;
            double strength = 0.06 + 0.34 * norm * norm;
            if (this.age > GROW_TICKS) {
                strength *= 1.35;
            }
            Vec3 inward = diff.scale(strength / dist);
            // A tangential component so victims spiral in instead of falling straight
            Vec3 tangent = new Vec3(-diff.z, 0.0, diff.x);
            double tLen = tangent.length();
            if (tLen > 1.0e-4) {
                tangent = tangent.scale(strength * 0.35 / tLen);
            } else {
                tangent = Vec3.ZERO;
            }
            e.setDeltaMovement(e.getDeltaMovement().scale(0.92).add(inward).add(tangent));
            e.hurtMarked = true;
            e.fallDistance = 0;
        }
    }

    private void devourBlocks(ServerLevel serverLevel, float radius) {
        float eatRadius = radius * DEVOUR_RADIUS_SCALE;
        if (eatRadius < 1.0f) {
            return;
        }
        Vec3 c = position();
        int attempts = this.age < GROW_TICKS ? 36 : 64;
        int broken = 0;
        for (int i = 0; i < attempts && broken < 24; i++) {
            double r = eatRadius * Math.cbrt(this.random.nextDouble());
            double ang = this.random.nextDouble() * Math.PI * 2.0;
            double cosPitch = this.random.nextDouble() * 2.0 - 1.0;
            double sinPitch = Math.sqrt(1.0 - cosPitch * cosPitch);
            BlockPos pos = BlockPos.containing(
                    c.x + sinPitch * Math.cos(ang) * r,
                    c.y + cosPitch * r,
                    c.z + sinPitch * Math.sin(ang) * r);
            BlockState state = serverLevel.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(serverLevel, pos) < 0.0f) {
                continue;
            }
            serverLevel.removeBlock(pos, false);
            broken++;
            if (broken % 2 == 0) {
                Vec3 bp = Vec3.atCenterOf(pos);
                Vec3 dir = c.subtract(bp);
                double len = dir.length();
                if (len > 1.0e-3) {
                    dir = dir.scale(1.0 / len);
                    serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                            bp.x, bp.y, bp.z, 0, dir.x, dir.y, dir.z,
                            0.30 + this.random.nextDouble() * 0.25);
                }
            }
        }
    }

    private void consumeAtHorizon(ServerLevel serverLevel, float radius) {
        if (radius < 0.4f) {
            return;
        }
        Vec3 c = position();
        AABB box = new AABB(c, c).inflate(radius);
        for (Entity e : serverLevel.getEntities(this, box, this::affectsEntity)) {
            if (e.position().distanceTo(c) > radius) {
                continue;
            }
            if (e instanceof LivingEntity living) {
                living.hurtServer(serverLevel, this.damageSources().magic(), 6.0f);
            } else {
                serverLevel.sendParticles(ParticleTypes.PORTAL,
                        e.getX(), e.getY(), e.getZ(), 10, 0.2, 0.2, 0.2, 0.1);
                e.discard();
            }
        }
    }

    private void detonate(ServerLevel serverLevel) {
        Vec3 c = position();
        float craterRadius = MAX_RADIUS * DEVOUR_RADIUS_SCALE + 1.5f;
        int cr = Mth.ceil(craterRadius);
        BlockPos center = BlockPos.containing(c.x, c.y, c.z);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-cr, -cr, -cr), center.offset(cr, cr, cr))) {
            if (pos.distToCenterSqr(c.x, c.y, c.z) > craterRadius * craterRadius) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(pos);
            if (state.isAir() || state.getDestroySpeed(serverLevel, pos) < 0.0f) {
                continue;
            }
            serverLevel.removeBlock(pos, false);
        }
        serverLevel.explode(this, c.x, c.y, c.z, 7.0f, Level.ExplosionInteraction.BLOCK);

        double blastRange = 24.0;
        AABB box = new AABB(c, c).inflate(blastRange);
        for (Entity e : serverLevel.getEntities(this, box, this::affectsEntity)) {
            Vec3 away = e.position().subtract(c);
            double dist = away.length();
            if (dist > blastRange) {
                continue;
            }
            double power = 1.0 - dist / blastRange;
            Vec3 kick = (dist < 1.0e-3 ? new Vec3(0.0, 1.0, 0.0) : away.scale(1.0 / dist))
                    .scale(2.8 * power).add(0.0, 0.9 * power, 0.0);
            e.setDeltaMovement(e.getDeltaMovement().add(kick));
            e.hurtMarked = true;
            if (e instanceof LivingEntity living) {
                living.hurtServer(serverLevel, this.damageSources().explosion(this, null),
                        (float) (28.0 * power));
            }
        }

        serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y, c.z, 8, 2.5, 2.5, 2.5, 0.0);
        serverLevel.sendParticles(ParticleTypes.FLASH, c.x, c.y, c.z, 3, 0.5, 0.5, 0.5, 0.0);
        for (int i = 0; i < 16; i++) {
            double ang = Math.PI * 2.0 * i / 16.0;
            serverLevel.sendParticles(ParticleTypes.SONIC_BOOM,
                    c.x + Math.cos(ang) * 3.0, c.y, c.z + Math.sin(ang) * 3.0, 1, 0, 0, 0, 0);
        }
        serverLevel.playSound(null, c.x, c.y, c.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 8.0f, 0.55f);
        serverLevel.playSound(null, c.x, c.y, c.z,
                SoundEvents.DRAGON_FIREBALL_EXPLODE, SoundSource.HOSTILE, 6.0f, 0.7f);
        serverLevel.playSound(null, c.x, c.y, c.z,
                SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 6.0f, 0.8f);
        discard();
    }

    private boolean affectsEntity(Entity e) {
        if (e instanceof BlackHoleEntity || !e.isAlive()) {
            return false;
        }
        if (e instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }
        return !e.noPhysics;
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
        return distance < 384.0 * 384.0;
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

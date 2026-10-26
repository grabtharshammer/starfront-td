package com.androidex.starfront.game

import kotlin.math.max
import kotlin.math.roundToInt

enum class TargetMode(val label: String) { FIRST("FIRST"), STRONG("STRONG"), CLOSE("CLOSE") }

class Tower(val type: TowerType, val col: Int, val row: Int) {
    var level = 1
    var invested = type.cost
    var cooldown = 0f
    var angle = -Math.PI.toFloat() / 2
    var mode = TargetMode.FIRST
    var kills = 0
    /** Seconds since the last shot, for muzzle-flash drawing. */
    var sinceShot = 10f

    val x get() = col + 0.5f
    val y get() = row + 0.5f
    val damage get() = Balance.damage(type.damage, level)
    /** Damage after one more upgrade. */
    val nextDamage get() = damage * Balance.DAMAGE_GROWTH
    val range get() = Balance.range(type.range, level)
    val rate get() = Balance.rate(type.rate, level)
    val sellValue get() = (invested * Balance.SELL_RATIO).roundToInt()

    /** Null when maxed out. */
    val upgradeCost: Int?
        get() = if (level >= Balance.MAX_LEVEL) null else Balance.upgradeCost(type.cost, level)

    // Weapon extras that scale with level.
    val splash get() = Balance.splash(level)
    val slowFactor get() = Balance.slowFactor(level)
    val chainJumps get() = Balance.chainJumps(level)

    /** Whether this tower, at its current level, can hit units on [layer]. */
    fun reaches(layer: Layer) = when (layer) {
        Layer.GROUND -> true
        Layer.AIR -> type.airFromLevel?.let { level >= it } ?: false
    }
}

class Enemy(val type: EnemyType, scale: Float, shieldScale: Float = scale) {
    val maxHp = type.hp * scale
    var hp = maxHp
    val maxShield = type.shield * shieldScale
    var shield = maxShield
    var shieldDelay = 0f
    var dist = 0f
    var x = 0f
    var y = 0f
    var heading = 0f
    var slowTimer = 0f
    var slowFactor = 1f
    var alive = true
    /** Seconds since the last hit, for a short flash. */
    var sinceHit = 10f
    /** Index of the sector the enemy is in; it gains a barrier each time this drops. */
    var sector = 0
    /** Non-regenerating shield picked up at sector seams. Absorbs damage before anything else. */
    var barrier = 0f
    var maxBarrier = 0f
    /** Fraction of the route covered, 0..1; compares ground and air units for FIRST targeting. */
    var progress = 0f
    /** Furthest [dist] this enemy may advance this frame (keeps big ground units from overlapping). */
    var limit = Float.MAX_VALUE
    // Flight path shape for air units.
    var waveAmp = 0f
    var waveFreq = 0f
    var wavePhase = 0f
    val radius get() = type.radius

    fun addBarrier(amount: Float) {
        barrier += amount
        maxBarrier = max(maxBarrier, barrier)
    }

    /** Applies damage after barrier, shield and armor. Returns true if this hit killed it. */
    fun hit(amount: Float, pierceArmor: Boolean = false): Boolean {
        if (!alive) return false
        var dmg = amount
        sinceHit = 0f
        if (barrier > 0f) {
            val absorbed = minOf(barrier, dmg)
            barrier -= absorbed
            dmg -= absorbed
            if (dmg <= 0f) return false
        }
        if (maxShield > 0f) {
            shieldDelay = Balance.SHIELD_REGEN_DELAY
            if (shield > 0f) {
                val absorbed = minOf(shield, dmg)
                shield -= absorbed
                dmg -= absorbed
                if (dmg <= 0f) return false
            }
        }
        if (!pierceArmor && type.armor > 0f) dmg = max(dmg - type.armor, dmg * Balance.ARMOR_FLOOR)
        hp -= dmg
        if (hp <= 0f) {
            alive = false
            return true
        }
        return false
    }

    fun slow(factor: Float, seconds: Float) {
        if (factor < slowFactor || slowTimer <= 0f) slowFactor = factor
        slowTimer = max(slowTimer, seconds)
    }
}

enum class ShotKind { BOLT, ORB }

class Projectile(
    val kind: ShotKind,
    var x: Float,
    var y: Float,
    var target: Enemy?,
    val speed: Float,
    val damage: Float,
    val splash: Float,
    val color: Int,
    /** Whether its splash can catch air units (fixed when fired). */
    val hitsAir: Boolean,
) {
    var tx = target?.x ?: x
    var ty = target?.y ?: y
    var done = false
}

enum class FxKind { BEAM, ARC, RING, BLAST, CORE_HIT }

/** A short-lived visual effect. Pure data; the renderer decides how it looks. */
class Fx(val kind: FxKind, val x: Float, val y: Float, val color: Int, val life: Float, val points: FloatArray? = null, val size: Float = 1f) {
    var t = 0f
    val progress get() = (t / life).coerceIn(0f, 1f)
}

class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val life: Float, val color: Int, val size: Float) {
    var t = 0f
}

class Floater(val text: String, val x: Float, var y: Float, val color: Int) {
    var t = 0f
}

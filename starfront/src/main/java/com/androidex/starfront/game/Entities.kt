package com.androidex.starfront.game

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

const val MAX_LEVEL = 4
const val SELL_RATIO = 0.7f

enum class TowerType(
    val title: String,
    val blurb: String,
    val cost: Int,
    val range: Float,
    /** Shots per second. */
    val rate: Float,
    val damage: Float,
    val color: Int,
) {
    PULSE("Pulse Laser", "Fast bolts", 50, 2.6f, 2.4f, 7f, 0xFF00E5FF.toInt()),
    PLASMA("Plasma Mortar", "Splash damage", 90, 3.2f, 0.7f, 22f, 0xFFFF9100.toInt()),
    CRYO("Cryo Emitter", "Slows in area", 70, 2.0f, 1.0f, 3f, 0xFF80D8FF.toInt()),
    TESLA("Arc Coil", "Chain lightning", 120, 2.4f, 1.0f, 15f, 0xFFB388FF.toInt()),
    RAIL("Railgun", "Pierces armor", 160, 5.5f, 0.35f, 75f, 0xFF76FF03.toInt()),
}

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
    val damage get() = type.damage * 1.55f.pow(level - 1)
    val range get() = type.range * (1f + 0.12f * (level - 1))
    val rate get() = type.rate * (1f + 0.15f * (level - 1))
    val sellValue get() = (invested * SELL_RATIO).roundToInt()

    /** Null when maxed out. */
    val upgradeCost: Int?
        get() = if (level >= MAX_LEVEL) null else (type.cost * (0.75f + 0.5f * (level - 1))).roundToInt()

    // Type-specific extras that scale with level.
    val splash get() = 1.0f + 0.12f * (level - 1)
    val slowFactor get() = 0.55f - 0.07f * (level - 1)
    val chainJumps get() = 2 + level
}

enum class EnemyType(
    val title: String,
    val hp: Float,
    val speed: Float,
    val armor: Float,
    val shield: Float,
    val bounty: Int,
    val coreDamage: Int,
    val radius: Float,
    val color: Int,
) {
    SCOUT("Scout Drone", 18f, 2.2f, 0f, 0f, 4, 1, 0.22f, 0xFFEEFF41.toInt()),
    GRUNT("Crawler", 42f, 1.3f, 0f, 0f, 6, 1, 0.27f, 0xFFFF5252.toInt()),
    SWARM("Swarmling", 9f, 2.6f, 0f, 0f, 2, 1, 0.14f, 0xFFFFAB40.toInt()),
    BRUTE("Juggernaut", 130f, 0.8f, 4f, 0f, 14, 2, 0.36f, 0xFFFF6E40.toInt()),
    PHASE("Phase Wraith", 50f, 1.6f, 0f, 40f, 11, 1, 0.26f, 0xFFF06292.toInt()),
    BOSS("Dreadnought", 700f, 0.55f, 7f, 140f, 120, 8, 0.55f, 0xFFD500F9.toInt()),
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
            shieldDelay = 2.5f
            if (shield > 0f) {
                val absorbed = minOf(shield, dmg)
                shield -= absorbed
                dmg -= absorbed
                if (dmg <= 0f) return false
            }
        }
        if (!pierceArmor && type.armor > 0f) dmg = max(dmg - type.armor, dmg * 0.2f)
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

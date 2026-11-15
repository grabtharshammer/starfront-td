package com.androidex.starfront.game

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Every tuning number in the game. Gameplay code reads from here and from the tower and enemy
 * tables below, so rebalancing means editing this file only. GameTest.botBalance reports how far
 * a simple bot gets, as a quick read on overall difficulty after a change.
 */
object Balance {
    // ---- Economy ----
    const val START_CREDITS = 220
    const val START_LIVES = 20
    const val SELL_RATIO = 0.7f

    fun waveClearBonus(wave: Int) = 20 + 6 * wave

    /** Credits for a kill: the type's base bounty, growing a little each wave. */
    fun bounty(base: Int, wave: Int) = (base * (1f + 0.04f * (wave - 1))).roundToInt()

    // ---- Towers ----
    const val MAX_LEVEL = 4
    /** Damage multiplier per upgrade. */
    const val DAMAGE_GROWTH = 1.55f

    fun damage(base: Float, level: Int) = base * DAMAGE_GROWTH.pow(level - 1)
    fun range(base: Float, level: Int) = base * (1f + 0.12f * (level - 1))
    fun rate(base: Float, level: Int) = base * (1f + 0.15f * (level - 1))
    fun upgradeCost(baseCost: Int, level: Int) = (baseCost * (0.75f + 0.5f * (level - 1))).roundToInt()

    /** Plasma splash radius in cells. */
    fun splash(level: Int) = 1.0f + 0.12f * (level - 1)
    /** Speed multiplier applied by cryo (lower is slower). */
    fun slowFactor(level: Int) = 0.55f - 0.07f * (level - 1)
    const val SLOW_SECONDS = 1.6f
    /** Extra enemies an arc jumps to. */
    fun chainJumps(level: Int) = 2 + level
    const val CHAIN_REACH = 1.8f
    const val CHAIN_FALLOFF = 0.75f
    /** Half-width of a railgun beam, added to the target's radius. */
    const val RAIL_WIDTH = 0.3f
    /** Level at which pulse lasers and railguns can shoot air units. */
    const val AIR_LEVEL = 3

    // ---- Enemies ----
    /** Overall enemy toughness. */
    const val HP_BASE = 1.6f
    /** Extra HP and shields per sector opened. */
    const val SECTOR_STRENGTH = 0.15f
    /** Barrier gained at each sector seam, as a fraction of max HP. */
    const val SEAM_BARRIER = 0.3f
    /** Armor never cuts a hit below this fraction of its damage. */
    const val ARMOR_FLOOR = 0.2f
    const val SHIELD_REGEN_DELAY = 2.5f
    /** Shield regained per second, as a fraction of max shield. */
    const val SHIELD_REGEN = 0.25f
    /** Ground units at least this big queue behind each other instead of overlapping. */
    const val QUEUE_RADIUS = 0.25f
    /** Gap kept between queued units, as a multiple of their combined radii. */
    const val QUEUE_GAP = 1.1f

    // ---- Tank ----
    // One is earned per sector opened. Its HP and damage scale with the wave like enemy HP does.
    const val TANK_HP = 600f
    /** Damage per shell, before scaling. */
    const val TANK_DAMAGE = 16f
    /** Shells per second. */
    const val TANK_RATE = 2f
    const val TANK_RANGE = 2.4f
    const val TANK_SPLASH = 0.7f
    const val TANK_SHOT_SPEED = 9f
    /** Cells per second along the route. */
    const val TANK_SPEED = 1.0f
    const val TANK_RADIUS = 0.36f
    /** Cells south of the core where it parks. */
    const val TANK_PARK_OFFSET = 1.9f
    /** Units this close (beyond touching) to its front wear it down. */
    const val TANK_CONTACT = 0.25f
    /** Damage per second a unit pressed against it deals, as a fraction of the unit's max HP. */
    const val TANK_CONTACT_DPS = 0.12f
    const val TANK_COLOR = 0xFF69F0AE.toInt()
    /** Gap kept between units held up behind the tank, as a multiple of their combined radii (below 1 overlaps a little). */
    const val STACK_GAP = 0.8f
    /** Cells per second a held-up unit backs off when it ends up overlapping the one ahead. */
    const val SETTLE_SPEED = 0.6f
    /** How close behind the line held up by the tank a unit must be to count as part of it. */
    const val JAM_SLACK = 0.3f

    fun hpScale(wave: Int): Float {
        // Exponential: the path and the tower count keep growing, so linear growth would stall.
        val n = (wave - 1).toFloat()
        return HP_BASE * (1f + 0.1f * n) * 1.17f.pow(n)
    }

    /** Each sector opened makes every enemy this much tougher. */
    fun sectorScale(sections: Int) = 1f + SECTOR_STRENGTH * (sections - 1)

    // ---- Sectors and power ----
    const val WAVES_PER_SECTOR = 5
    /** Power available for towers in each sector (the core sector is larger). */
    const val FIRST_SECTION_POWER = 12
    const val SECTION_POWER = 10
    /** Extra power a sector earns once it is fully built out and every tower in it is maxed. */
    const val EFFICIENCY_BONUS = 5

    // ---- Waves ----
    fun composeWave(n: Int): List<Spawn> {
        val out = ArrayList<Spawn>()
        var t = 0.5f
        val pace = max(0.5f, 1f - 0.02f * n)
        fun group(type: EnemyType, count: Int, gap: Float) {
            repeat(count) {
                out.add(Spawn(type, t))
                t += gap * pace
            }
            if (count > 0) t += 1.5f * pace
        }
        val grunts = 5 + n * 3 / 2
        group(EnemyType.GRUNT, (grunts + 1) / 2, 0.8f)
        if (n >= 2) group(EnemyType.SCOUT, 2 + n, 0.5f)
        if (n >= 3) group(EnemyType.SWARM, 6 + n, 0.18f)
        group(EnemyType.GRUNT, grunts / 2, 0.8f)
        if (n >= 4) group(EnemyType.BRUTE, n / 2 - 1, 1.6f)
        if (n >= 6) group(EnemyType.PHASE, (n - 3) / 2, 1.0f)
        if (n >= 8 && n % 2 == 0) group(EnemyType.SWARM, 2 * n, 0.15f)
        if (n % 5 == 0) group(EnemyType.BOSS, n / 5, 4f)
        return out
    }

    /** Gunships in the raid that follows a sector opening: one per sector index, up to three. */
    fun airRaidSize(sectorIndex: Int) = sectorIndex.coerceIn(1, 3)
    const val AIR_RAID_FIRST = 4f
    const val AIR_RAID_GAP = 5f
}

/** Where an enemy moves; towers must be able to reach the layer to hit it. */
enum class Layer { GROUND, AIR }

/** How a tower attacks. [aims] is false for weapons that hit everything in range at once. */
enum class Weapon(val aims: Boolean) {
    /** Fast homing projectile. */
    BOLT(true),
    /** Slow projectile that splashes on impact. */
    MORTAR(true),
    /** Pulse that slows and damages everything in range. */
    FROST(false),
    /** Lightning that jumps between nearby enemies. */
    ARC(true),
    /** Instant beam that pierces every enemy in line and ignores armor. */
    RAIL(true),
}

enum class TowerType(
    val title: String,
    val blurb: String,
    val cost: Int,
    val range: Float,
    /** Shots per second. */
    val rate: Float,
    val damage: Float,
    val color: Int,
    /** Sector power the tower draws; each sector has a fixed budget. */
    val power: Int,
    val weapon: Weapon,
    /** Level from which it can hit air units; null if never. */
    val airFromLevel: Int?,
    val fireSfx: Sfx,
    /** Projectile speed in cells per second, for projectile weapons. */
    val shotSpeed: Float = 0f,
) {
    PULSE("Pulse Laser", "Air at LV3+", 50, 2.6f, 2.4f, 7f, 0xFF00E5FF.toInt(), 1, Weapon.BOLT, Balance.AIR_LEVEL, Sfx.PULSE, shotSpeed = 12f),
    PLASMA("Plasma Mortar", "Splash damage", 90, 3.2f, 0.7f, 22f, 0xFFFF9100.toInt(), 2, Weapon.MORTAR, null, Sfx.PLASMA, shotSpeed = 5.5f),
    CRYO("Cryo Emitter", "Slows in area", 70, 2.0f, 1.0f, 3f, 0xFF80D8FF.toInt(), 2, Weapon.FROST, 1, Sfx.CRYO),
    TESLA("Arc Coil", "Chain lightning", 120, 2.4f, 1.0f, 15f, 0xFFB388FF.toInt(), 3, Weapon.ARC, null, Sfx.TESLA),
    RAIL("Railgun", "Pierce, air LV3+", 160, 5.5f, 0.35f, 75f, 0xFF76FF03.toInt(), 3, Weapon.RAIL, Balance.AIR_LEVEL, Sfx.RAIL),
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
    val layer: Layer = Layer.GROUND,
    /** Gets the big explosion and sound when destroyed. */
    val bigDeath: Boolean = false,
) {
    SCOUT("Scout Drone", 18f, 2.2f, 0f, 0f, 4, 1, 0.22f, 0xFFEEFF41.toInt()),
    GRUNT("Crawler", 42f, 1.3f, 0f, 0f, 6, 1, 0.27f, 0xFFFF5252.toInt()),
    SWARM("Swarmling", 9f, 2.6f, 0f, 0f, 2, 1, 0.14f, 0xFFFFAB40.toInt()),
    BRUTE("Juggernaut", 130f, 0.8f, 4f, 0f, 14, 2, 0.36f, 0xFFFF6E40.toInt()),
    PHASE("Phase Wraith", 50f, 1.6f, 0f, 40f, 11, 1, 0.26f, 0xFFF06292.toInt()),
    BOSS("Dreadnought", 700f, 0.55f, 7f, 140f, 120, 8, 0.55f, 0xFFD500F9.toInt(), bigDeath = true),
    GUNSHIP("Gunship", 200f, 0.85f, 2f, 0f, 40, 3, 0.45f, 0xFF64FFDA.toInt(), layer = Layer.AIR, bigDeath = true),
}

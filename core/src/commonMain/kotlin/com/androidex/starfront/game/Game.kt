package com.androidex.starfront.game

import kotlin.math.max
import kotlin.random.Random

const val BARRIER_COLOR = 0xFFFFE57F.toInt()

enum class Phase { BUILD, WAVE, GAME_OVER }

sealed class GameEvent {
    class Message(val text: String) : GameEvent()
    class Expanded(val section: Section) : GameEvent()
    object Cleared : GameEvent()
    class GameOver(val wave: Int) : GameEvent()
}

/**
 * One play-through. Owns the shared state (map, enemies, credits, lives, phase) and runs the
 * fixed-step update; the work itself lives in the components below. Its methods are the
 * player's commands and the queries the view needs.
 */
class Game(val seed: Long = Random.nextLong()) {
    val world = World(seed)
    /** Gameplay randomness (air raid flight paths). Cosmetic effects have their own in [Feedback]. */
    internal val rng = Random(seed xor 0x5DEECE66DL)

    val feedback = Feedback(Random(seed xor 0x2545F4914F6CDD1DL))
    val defenses = Defenses(this)
    val waves = WaveDirector(this)
    val tanks = TankBay(this)
    internal val movement = Movement(this)
    internal val combat = Combat(this)

    val enemies = ArrayList<Enemy>()

    var credits = Balance.START_CREDITS
    var lives = Balance.START_LIVES
    /** Number of the wave in progress, or of the last one launched. */
    var wave = 0
        internal set
    var phase = Phase.BUILD
        private set
    /** Seconds since the game started; drives idle animations. */
    var clock = 0f
        private set

    /** Save taken as the current wave began, so quitting mid-wave resumes from its start. */
    private var checkpoint: String? = null

    // ---- What the view draws ----
    val towers get() = defenses.towers
    val projectiles get() = combat.projectiles
    val effects get() = feedback.effects
    val particles get() = feedback.particles
    val floaters get() = feedback.floaters
    val events get() = feedback.events

    val waveSize get() = waves.size
    val enemiesRemaining get() = waves.remaining
    val nextExpansionWave get() = waves.nextExpansionWave

    // ---- Building (see Defenses) ----
    fun towerAt(col: Int, row: Int) = defenses.at(col, row)
    fun canBuild(col: Int, row: Int) = defenses.canBuild(col, row)
    fun powerLeft(section: Int) = defenses.powerLeft(section)
    fun hasPowerFor(type: TowerType, col: Int) = defenses.hasPowerFor(type, col)
    fun hasPowerToReplace(t: Tower, type: TowerType) = defenses.hasPowerToReplace(t, type)
    fun isFullyUpgraded(section: Int) = defenses.isFullyUpgraded(section)
    fun build(type: TowerType, col: Int, row: Int) = defenses.build(type, col, row)
    fun upgrade(t: Tower) = defenses.upgrade(t)
    fun sell(t: Tower) = defenses.sell(t)
    fun replaceCost(t: Tower, type: TowerType) = defenses.replaceCost(t, type)
    fun replace(t: Tower, type: TowerType) = defenses.replace(t, type)

    fun canHit(t: Tower, e: Enemy) = combat.canHit(t, e)

    fun launchTank() = tanks.launch()

    fun sound(sfx: Sfx, x: Float = Float.NaN, y: Float = Float.NaN) = feedback.sound(sfx, x, y)

    // ---- Waves ----

    fun startWave() {
        if (phase != Phase.BUILD) return
        checkpoint = SaveCodec.encode(this)
        wave++
        phase = Phase.WAVE
        waves.begin()
    }

    fun update(dt: Float) {
        clock += dt
        if (phase == Phase.WAVE) {
            waves.update(dt)
            tanks.update(dt)
            movement.update(dt)
            combat.update(dt)
            enemies.removeAll { !it.alive }
            if (phase == Phase.WAVE && waves.finished) {
                combat.clear()
                tanks.clear()
                phase = Phase.BUILD
                checkpoint = null
                waves.complete()
            }
        }
        feedback.update(dt)
    }

    /** An enemy reached the end of its route. */
    internal fun coreHit(e: Enemy) {
        e.alive = false
        lives = max(0, lives - e.type.coreDamage)
        val cx = world.coreCol + 0.5f
        val cy = world.coreRow + 0.5f
        feedback.fx(FxKind.CORE_HIT, cx, cy, e.type.color, 0.6f)
        feedback.sound(Sfx.CORE_HIT, cx, cy)
        if (lives == 0 && phase != Phase.GAME_OVER) {
            phase = Phase.GAME_OVER
            waves.abort()
            tanks.clear()
            feedback.event(GameEvent.GameOver(wave))
            feedback.sound(Sfx.GAME_OVER)
        }
    }

    // ---- Persistence ----

    /** State to save: the live state between waves, or the state from just before the current wave. */
    fun saveState(): String? = when (phase) {
        Phase.BUILD -> SaveCodec.encode(this)
        Phase.WAVE -> checkpoint
        Phase.GAME_OVER -> null
    }

    companion object {
        fun restore(text: String): Game? = SaveCodec.decode(text)
    }
}

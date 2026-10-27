package com.androidex.starfront.game

data class Spawn(val type: EnemyType, val at: Float)

/** The wave schedule: what spawns when, the air raid after a sector opens, and what clearing a wave unlocks (sectors and tanks). */
class WaveDirector(private val game: Game) {
    private val queue = ArrayDeque<Spawn>()
    private var time = 0f

    /** Enemies in the current wave, for the progress bar. */
    var size = 0
        private set

    val remaining get() = queue.size + game.enemies.size

    /** True once every enemy of the wave has spawned and been dealt with. */
    val finished get() = queue.isEmpty() && game.enemies.isEmpty()

    /** The wave whose clearing opens the next sector. */
    val nextExpansionWave: Int
        get() {
            val per = Balance.WAVES_PER_SECTOR
            val w = game.wave
            return (w / per + 1) * per - if (game.phase == Phase.WAVE && w % per == 0) per else 0
        }

    /** Queues up wave [Game.wave], which has just begun. */
    fun begin() {
        time = 0f
        queue.clear()
        val wave = game.wave
        val world = game.world
        val spawns = Balance.composeWave(wave).toMutableList()
        // The first wave after a sector opens brings an air raid with it.
        if (world.sections.size > 1 && wave == (world.sections.size - 1) * Balance.WAVES_PER_SECTOR + 1) {
            val n = Balance.airRaidSize(world.frontier.index)
            repeat(n) { spawns.add(Spawn(EnemyType.GUNSHIP, Balance.AIR_RAID_FIRST + it * Balance.AIR_RAID_GAP)) }
            spawns.sortBy { it.at }
            game.feedback.message("AIR RAID  ·  $n GUNSHIP${if (n > 1) "S" else ""} INBOUND  ·  LV${Balance.AIR_LEVEL} PULSE / RAIL ONLY")
            game.feedback.sound(Sfx.AIR_RAID)
        }
        queue.addAll(spawns)
        size = queue.size
        game.feedback.sound(Sfx.WAVE_START)
    }

    fun update(dt: Float) {
        time += dt
        while (queue.isNotEmpty() && queue.first().at <= time) spawn(queue.removeFirst().type)
    }

    /** Drops whatever hasn't spawned yet (the core fell). */
    fun abort() = queue.clear()

    private fun spawn(type: EnemyType) {
        val strength = Balance.hpScale(game.wave) * Balance.sectorScale(game.world.sections.size)
        val e = Enemy(type, strength)
        e.sector = game.world.frontier.index
        game.movement.launch(e)
        game.enemies.add(e)
    }

    /** Pays the clear bonus and, every few waves, opens the next sector. */
    fun complete() {
        val wave = game.wave
        val bonus = Balance.waveClearBonus(wave)
        game.credits += bonus
        val fb = game.feedback
        if (wave % Balance.WAVES_PER_SECTOR == 0) {
            val s = game.world.addSection()
            game.tanks.charges++
            fb.message("WAVE $wave CLEARED  +$bonus  ·  SECTOR ${s.index + 1} ONLINE  ·  TANK READY")
            fb.event(GameEvent.Expanded(s))
            fb.sound(Sfx.SECTOR_OPEN)
        } else {
            val left = nextExpansionWave - wave
            fb.message("WAVE $wave CLEARED  +$bonus  ·  NEW SECTOR IN $left")
            fb.event(GameEvent.Cleared)
            fb.sound(Sfx.WAVE_CLEAR)
        }
    }
}

package com.androidex.starfront.game

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

const val START_CREDITS = 220
const val START_LIVES = 20

enum class Phase { BUILD, WAVE, GAME_OVER }

sealed class GameEvent {
    class Message(val text: String) : GameEvent()
    class Expanded(val section: Section) : GameEvent()
    class GameOver(val wave: Int) : GameEvent()
}

data class Spawn(val type: EnemyType, val at: Float)

class Game(val seed: Long = Random.nextLong()) {
    val world = World(seed)
    val towers = ArrayList<Tower>()
    private val towerIndex = HashMap<Long, Tower>()
    val enemies = ArrayList<Enemy>()
    val projectiles = ArrayList<Projectile>()
    val effects = ArrayList<Fx>()
    val particles = ArrayList<Particle>()
    val floaters = ArrayList<Floater>()
    val events = ArrayDeque<GameEvent>()

    var credits = START_CREDITS
    var lives = START_LIVES
    /** Number of the wave in progress, or of the last one launched. */
    var wave = 0
        private set
    var phase = Phase.BUILD
        private set
    /** Seconds since the game started; drives idle animations. */
    var clock = 0f
        private set

    private val spawnQueue = ArrayDeque<Spawn>()
    private var waveTime = 0f
    var waveSize = 0
        private set
    private var checkpoint: String? = null
    private val rng = Random(seed xor 0x5DEECE66DL)
    private val tmp = FloatArray(3)

    val enemiesRemaining get() = spawnQueue.size + enemies.size

    // ---- Building ----

    fun towerAt(col: Int, row: Int): Tower? = towerIndex[World.key(col, row)]

    fun canBuild(col: Int, row: Int) =
        phase != Phase.GAME_OVER && world.inBounds(col, row) && !world.isPath(col, row) && towerAt(col, row) == null

    fun build(type: TowerType, col: Int, row: Int): Tower? {
        if (!canBuild(col, row) || credits < type.cost) return null
        credits -= type.cost
        return place(Tower(type, col, row))
    }

    fun upgrade(t: Tower): Boolean {
        val cost = t.upgradeCost ?: return false
        if (credits < cost || phase == Phase.GAME_OVER) return false
        credits -= cost
        t.invested += cost
        t.level++
        effects.add(Fx(FxKind.RING, t.x, t.y, t.type.color, 0.5f, size = 0.8f))
        return true
    }

    fun sell(t: Tower) {
        if (phase == Phase.GAME_OVER || towerIndex.remove(World.key(t.col, t.row)) == null) return
        towers.remove(t)
        credits += t.sellValue
        floaters.add(Floater("+${t.sellValue}", t.x, t.y, 0xFFFFD740.toInt()))
    }

    fun replaceCost(t: Tower, type: TowerType) = type.cost - t.sellValue

    /** Swaps [t] for a fresh level-1 tower of [type], refunding the old one. */
    fun replace(t: Tower, type: TowerType): Tower? {
        if (type == t.type || credits < replaceCost(t, type) || phase == Phase.GAME_OVER) return null
        sell(t)
        floaters.removeAt(floaters.size - 1)
        credits -= type.cost
        return place(Tower(type, t.col, t.row))
    }

    private fun place(t: Tower): Tower {
        towers.add(t)
        towerIndex[World.key(t.col, t.row)] = t
        effects.add(Fx(FxKind.RING, t.x, t.y, t.type.color, 0.4f, size = 0.7f))
        return t
    }

    // ---- Waves ----

    fun startWave() {
        if (phase != Phase.BUILD) return
        checkpoint = serialize()
        wave++
        phase = Phase.WAVE
        waveTime = 0f
        spawnQueue.clear()
        spawnQueue.addAll(composeWave(wave))
        waveSize = spawnQueue.size
    }

    fun update(dt: Float) {
        clock += dt
        if (phase == Phase.WAVE) {
            waveTime += dt
            while (spawnQueue.isNotEmpty() && spawnQueue.first().at <= waveTime) spawn(spawnQueue.removeFirst().type)
            updateEnemies(dt)
            updateTowers(dt)
            updateProjectiles(dt)
            enemies.removeAll { !it.alive }
            if (phase == Phase.WAVE && spawnQueue.isEmpty() && enemies.isEmpty()) clearWave()
        }
        updateFx(dt)
    }

    private fun spawn(type: EnemyType) {
        val e = Enemy(type, hpScale(wave))
        world.positionAt(0f, tmp)
        e.x = tmp[0]; e.y = tmp[1]; e.heading = tmp[2]
        enemies.add(e)
    }

    private fun clearWave() {
        val bonus = 20 + 6 * wave
        credits += bonus
        projectiles.clear()
        val s = world.addSection()
        phase = Phase.BUILD
        checkpoint = null
        events.add(GameEvent.Message("WAVE $wave CLEARED  +$bonus  ·  SECTOR ${s.index + 1} ONLINE"))
        events.add(GameEvent.Expanded(s))
    }

    private fun updateEnemies(dt: Float) {
        for (e in enemies) {
            if (!e.alive) continue
            e.sinceHit += dt
            if (e.slowTimer > 0f) {
                e.slowTimer -= dt
                if (e.slowTimer <= 0f) e.slowFactor = 1f
            }
            if (e.maxShield > 0f) {
                e.shieldDelay -= dt
                if (e.shieldDelay <= 0f) e.shield = minOf(e.maxShield, e.shield + e.maxShield * 0.25f * dt)
            }
            e.dist += e.type.speed * e.slowFactor * dt
            if (e.dist >= world.pathLength) {
                e.alive = false
                lives = max(0, lives - e.type.coreDamage)
                effects.add(Fx(FxKind.CORE_HIT, world.coreCol + 0.5f, world.coreRow + 0.5f, e.type.color, 0.6f))
                if (lives == 0 && phase != Phase.GAME_OVER) {
                    phase = Phase.GAME_OVER
                    spawnQueue.clear()
                    events.add(GameEvent.GameOver(wave))
                }
                continue
            }
            world.positionAt(e.dist, tmp)
            e.x = tmp[0]; e.y = tmp[1]; e.heading = tmp[2]
        }
    }

    private fun updateTowers(dt: Float) {
        for (t in towers) {
            t.sinceShot += dt
            t.cooldown -= dt
            if (t.cooldown > 0f) continue
            val target = pickTarget(t) ?: continue
            t.cooldown = 1f / t.rate
            t.sinceShot = 0f
            if (t.type != TowerType.CRYO) t.angle = atan2(target.y - t.y, target.x - t.x)
            val mx = t.x + cos(t.angle) * 0.35f
            val my = t.y + sin(t.angle) * 0.35f
            when (t.type) {
                TowerType.PULSE -> projectiles.add(Projectile(ShotKind.BOLT, mx, my, target, 12f, t.damage, 0f, t.type.color))
                TowerType.PLASMA -> projectiles.add(Projectile(ShotKind.ORB, mx, my, target, 5.5f, t.damage, t.splash, t.type.color))
                TowerType.CRYO -> {
                    val r2 = t.range * t.range
                    for (e in enemies) {
                        if (!e.alive || sq(e.x - t.x) + sq(e.y - t.y) > r2) continue
                        e.slow(t.slowFactor, 1.6f)
                        damage(e, t.damage, false, t)
                    }
                    effects.add(Fx(FxKind.RING, t.x, t.y, t.type.color, 0.45f, size = t.range))
                }
                TowerType.TESLA -> chain(t, target)
                TowerType.RAIL -> rail(t)
            }
        }
    }

    private fun pickTarget(t: Tower): Enemy? {
        val r2 = t.range * t.range
        var best: Enemy? = null
        var bestScore = 0f
        for (e in enemies) {
            if (!e.alive || e.dist <= 0.3f) continue
            val d2 = sq(e.x - t.x) + sq(e.y - t.y)
            if (d2 > r2) continue
            val score = when (t.mode) {
                TargetMode.FIRST -> e.dist
                TargetMode.STRONG -> e.hp + e.shield
                TargetMode.CLOSE -> -d2
            }
            if (best == null || score > bestScore) {
                best = e
                bestScore = score
            }
        }
        return best
    }

    private fun chain(t: Tower, first: Enemy) {
        val hit = ArrayList<Enemy>()
        val pts = ArrayList<Float>()
        pts.add(t.x); pts.add(t.y - 0.2f)
        var cur: Enemy? = first
        var dmg = t.damage
        repeat(t.chainJumps + 1) {
            val e = cur ?: return@repeat
            pts.add(e.x); pts.add(e.y)
            hit.add(e)
            damage(e, dmg, false, t)
            dmg *= 0.75f
            var next: Enemy? = null
            var nd = 1.8f * 1.8f
            for (o in enemies) {
                if (!o.alive || o in hit) continue
                val d2 = sq(o.x - e.x) + sq(o.y - e.y)
                if (d2 < nd) { nd = d2; next = o }
            }
            cur = next
        }
        effects.add(Fx(FxKind.ARC, t.x, t.y, t.type.color, 0.18f, pts.toFloatArray()))
    }

    private fun rail(t: Tower) {
        val dx = cos(t.angle)
        val dy = sin(t.angle)
        val len = t.range
        for (e in enemies) {
            if (!e.alive) continue
            val px = e.x - t.x
            val py = e.y - t.y
            val along = px * dx + py * dy
            if (along < 0f || along > len) continue
            val perp = kotlin.math.abs(px * dy - py * dx)
            if (perp <= 0.3f + e.radius) damage(e, t.damage, true, t)
        }
        effects.add(Fx(FxKind.BEAM, t.x, t.y, t.type.color, 0.3f, floatArrayOf(t.x + dx * 0.4f, t.y + dy * 0.4f, t.x + dx * len, t.y + dy * len)))
    }

    private fun updateProjectiles(dt: Float) {
        for (p in projectiles) {
            val target = p.target
            if (target != null && target.alive) {
                p.tx = target.x; p.ty = target.y
            } else {
                p.target = null
                if (p.kind == ShotKind.BOLT) { p.done = true; continue }
            }
            val dx = p.tx - p.x
            val dy = p.ty - p.y
            val d = hypot(dx, dy)
            val step = p.speed * dt
            if (d <= step + 0.05f) {
                p.x = p.tx; p.y = p.ty
                p.done = true
                impact(p)
            } else {
                p.x += dx / d * step
                p.y += dy / d * step
            }
        }
        projectiles.removeAll { it.done }
    }

    private fun impact(p: Projectile) {
        when (p.kind) {
            ShotKind.BOLT -> p.target?.let { damage(it, p.damage, false, null) }
            ShotKind.ORB -> {
                val r2 = p.splash * p.splash
                for (e in enemies) {
                    if (e.alive && sq(e.x - p.x) + sq(e.y - p.y) <= r2) damage(e, p.damage, false, null)
                }
                effects.add(Fx(FxKind.BLAST, p.x, p.y, p.color, 0.35f, size = p.splash))
            }
        }
    }

    private fun damage(e: Enemy, amount: Float, pierce: Boolean, by: Tower?) {
        if (!e.hit(amount, pierce)) return
        if (by != null) by.kills++
        val bounty = (e.type.bounty * (1f + 0.04f * (wave - 1))).roundToInt()
        credits += bounty
        floaters.add(Floater("+$bounty", e.x, e.y - 0.2f, 0xFFFFD740.toInt()))
        val n = if (e.type == EnemyType.BOSS) 40 else 10
        repeat(n) {
            val a = rng.nextFloat() * 6.2832f
            val s = 0.6f + rng.nextFloat() * 2.2f
            particles.add(Particle(e.x, e.y, cos(a) * s, sin(a) * s, 0.4f + rng.nextFloat() * 0.4f, e.type.color, 0.05f + rng.nextFloat() * 0.06f))
        }
        effects.add(Fx(FxKind.BLAST, e.x, e.y, e.type.color, 0.3f, size = e.radius * 2f))
    }

    private fun updateFx(dt: Float) {
        for (f in effects) f.t += dt
        effects.removeAll { it.t >= it.life }
        for (p in particles) {
            p.t += dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 0.92f
            p.vy *= 0.92f
        }
        particles.removeAll { it.t >= it.life }
        for (f in floaters) {
            f.t += dt
            f.y -= 0.7f * dt
        }
        floaters.removeAll { it.t >= 0.9f }
    }

    // ---- Persistence ----

    /** State to save: the live state between waves, or the state from just before the current wave. */
    fun saveState(): String? = when (phase) {
        Phase.BUILD -> serialize()
        Phase.WAVE -> checkpoint
        Phase.GAME_OVER -> null
    }

    private fun serialize(): String = buildString {
        appendLine(HEADER)
        appendLine("seed=$seed")
        appendLine("sections=${world.sections.size}")
        appendLine("wave=$wave")
        appendLine("credits=$credits")
        appendLine("lives=$lives")
        for (t in towers) appendLine("tower=${t.type.name},${t.col},${t.row},${t.level},${t.invested},${t.mode.name},${t.kills}")
    }

    companion object {
        private const val HEADER = "starfront/1"

        fun restore(text: String): Game? = try {
            val lines = text.lines().filter { it.isNotBlank() }
            require(lines.firstOrNull() == HEADER)
            val values = lines.drop(1).filter { !it.startsWith("tower=") }.associate {
                it.substringBefore('=') to it.substringAfter('=')
            }
            val g = Game(values.getValue("seed").toLong())
            repeat(values.getValue("sections").toInt() - 1) { g.world.addSection() }
            g.wave = values.getValue("wave").toInt()
            g.credits = values.getValue("credits").toInt()
            g.lives = values.getValue("lives").toInt()
            for (line in lines) {
                if (!line.startsWith("tower=")) continue
                val f = line.removePrefix("tower=").split(',')
                val t = Tower(TowerType.valueOf(f[0]), f[1].toInt(), f[2].toInt())
                t.level = f[3].toInt().coerceIn(1, MAX_LEVEL)
                t.invested = f[4].toInt()
                t.mode = TargetMode.valueOf(f[5])
                t.kills = f[6].toInt()
                require(g.canBuild(t.col, t.row))
                g.towers.add(t)
                g.towerIndex[World.key(t.col, t.row)] = t
            }
            g
        } catch (e: Exception) {
            null
        }

        fun hpScale(wave: Int): Float {
            // Exponential: the path and the tower count grow every wave, so linear growth would stall.
            val n = (wave - 1).toFloat()
            return (1f + 0.1f * n) * 1.12f.pow(n)
        }

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

        private fun sq(v: Float) = v * v
    }
}

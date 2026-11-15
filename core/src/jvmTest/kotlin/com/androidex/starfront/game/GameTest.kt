package com.androidex.starfront.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GameTest {

    @Test
    fun pathIsContiguousAcrossExpansions() {
        for (seed in 1L..200L) {
            val world = World(seed)
            repeat(10) { world.addSection() }
            val cells = world.sections.asReversed().flatMap { it.path }
            assertEquals("no repeated cells", cells.size, cells.toSet().size)
            assertEquals(world.minCol, cells.first().col)
            assertEquals(world.coreCol - 1, cells.last().col)
            assertEquals(world.coreRow, cells.last().row)
            for ((a, b) in cells.zipWithNext()) {
                assertEquals("step $a -> $b (seed $seed)", 1, abs(a.col - b.col) + abs(a.row - b.row))
            }
            for (c in cells) assertTrue(c.row in 0 until ROWS)
            // Path only ever touches itself where consecutive.
            val index = cells.withIndex().associate { it.value to it.index }
            for ((i, c) in cells.withIndex()) {
                for (n in listOf(Cell(c.col + 1, c.row), Cell(c.col - 1, c.row), Cell(c.col, c.row + 1), Cell(c.col, c.row - 1))) {
                    val j = index[n] ?: continue
                    assertTrue("$c touches $n (seed $seed)", abs(i - j) == 1)
                }
            }
        }
    }

    @Test
    fun routeGrowsWestAndEndsAtCore() {
        val world = World(7)
        val before = world.pathLength
        val out = FloatArray(3)
        world.addSection()
        assertTrue(world.pathLength > before + SECTION_COLS - 1)
        assertEquals(-SECTION_COLS, world.minCol)
        world.positionAt(world.pathLength, out)
        assertEquals(world.coreCol + 0.5f, out[0], 1e-4f)
        assertEquals(world.coreRow + 0.5f, out[1], 1e-4f)
        world.positionAt(0f, out)
        assertTrue(out[0] < world.minCol)
    }

    @Test
    fun buildUpgradeSellReplace() {
        val g = Game(3)
        val cell = buildableCells(g).first()
        assertFalse(g.canBuild(g.world.sections[0].path[0].col, g.world.sections[0].path[0].row))
        val t = g.build(TowerType.PULSE, cell.col, cell.row)!!
        assertEquals(Balance.START_CREDITS - 50, g.credits)
        assertNull(g.build(TowerType.PULSE, cell.col, cell.row))
        assertTrue(g.upgrade(t))
        assertEquals(2, t.level)
        val r = g.replace(t, TowerType.CRYO)!!
        assertEquals(TowerType.CRYO, g.towerAt(cell.col, cell.row)!!.type)
        assertEquals(1, g.towers.size)
        val credits = g.credits
        g.sell(r)
        assertEquals(credits + r.sellValue, g.credits)
        assertNull(g.towerAt(cell.col, cell.row))
    }

    @Test
    fun fieldExpandsEveryFiveWaves() {
        val g = Game(11)
        // Plenty of credits and towers by the path so the early waves can't get through.
        g.credits = 2000
        for (c in buildableCells(g).sortedByDescending { nearPath(g, it) }.take(10)) g.build(TowerType.PULSE, c.col, c.row)
        val oldMin = g.world.minCol
        val towers = g.towers.toList()
        for (w in 1 until Balance.WAVES_PER_SECTOR) {
            assertEquals(Balance.WAVES_PER_SECTOR, g.nextExpansionWave)
            g.startWave()
            runWave(g)
            assertEquals(Phase.BUILD, g.phase)
            assertEquals("after wave $w", 1, g.world.sections.size)
        }
        assertTrue(g.events.none { it is GameEvent.Expanded })
        g.startWave()
        assertEquals(Balance.WAVES_PER_SECTOR, g.nextExpansionWave)
        runWave(g)
        assertEquals(Phase.BUILD, g.phase)
        assertEquals(2, g.world.sections.size)
        assertEquals(oldMin - SECTION_COLS, g.world.minCol)
        assertEquals(towers, g.towers)
        assertTrue(g.events.any { it is GameEvent.Expanded })
        assertEquals(2 * Balance.WAVES_PER_SECTOR, g.nextExpansionWave)
        // The new sector is open for building.
        assertTrue(g.canBuild(g.world.minCol, (0 until ROWS).first { !g.world.isPath(g.world.minCol, it) }))
    }

    @Test
    fun enemiesGainBarrierCrossingSeams() {
        val g = Game(21)
        repeat(2) { g.world.addSection() }
        // Wave 1 with no towers: every enemy walks the whole route.
        g.startWave()
        var maxBarrier = 0f
        while (g.phase == Phase.WAVE) {
            g.update(1f / 30)
            for (e in g.enemies) {
                val expected = (g.world.frontier.index - e.sector) * e.maxHp * Balance.SEAM_BARRIER
                assertEquals(expected, e.barrier, 1e-3f)
                maxBarrier = maxOf(maxBarrier, e.barrier / e.maxHp)
            }
        }
        assertEquals(2 * Balance.SEAM_BARRIER, maxBarrier, 1e-4f)

        // Barrier soaks damage before HP.
        val e = Enemy(EnemyType.GRUNT, 1f)
        e.addBarrier(10f)
        e.hit(15f)
        assertEquals(0f, e.barrier, 0f)
        assertEquals(e.maxHp - 5f, e.hp, 1e-4f)
    }

    @Test
    fun sectorsMakeEnemiesStronger() {
        assertEquals(1f, Balance.sectorScale(1), 0f)
        assertTrue(Balance.sectorScale(4) > Balance.sectorScale(2))
        assertEquals(1, World.sectionIndexAt(-1))
        assertEquals(1, World.sectionIndexAt(-SECTION_COLS))
        assertEquals(2, World.sectionIndexAt(-SECTION_COLS - 1))
        assertEquals(0, World.sectionIndexAt(FIRST_SECTION_COLS - 1))
    }

    @Test
    fun sectorPowerLimitsTowers() {
        val g = Game(8)
        g.credits = 100_000
        val cells = buildableCells(g)
        var built = 0
        for (c in cells) if (g.build(TowerType.RAIL, c.col, c.row) != null) built++
        assertEquals(Balance.FIRST_SECTION_POWER / TowerType.RAIL.power, built)
        assertEquals(Balance.FIRST_SECTION_POWER % TowerType.RAIL.power, g.powerLeft(0))
        val free = cells.first { g.canBuild(it.col, it.row) }
        assertNull(g.build(TowerType.RAIL, free.col, free.row))
        // Selling frees power; swapping counts the old tower's power as freed.
        val t = g.towers.first()
        val swapped = g.replace(t, TowerType.TESLA)!!
        assertEquals(TowerType.TESLA, g.towerAt(t.col, t.row)!!.type)
        g.sell(swapped)
        assertNotNull(g.build(TowerType.RAIL, free.col, free.row))
        // A new sector brings its own budget.
        g.world.addSection()
        assertEquals(Balance.SECTION_POWER, g.powerLeft(1))
    }

    @Test
    fun airRaidFollowsSectorOpening() {
        val g = Game(31)
        g.credits = 100_000
        g.lives = 1_000_000
        // Plenty of ground defence so the early waves clear.
        for (c in buildableCells(g).sortedByDescending { nearPath(g, it) }.take(10)) g.build(TowerType.PULSE, c.col, c.row)
        for (t in g.towers) repeat(3) { g.upgrade(t) }
        val airByWave = ArrayList<Int>()
        repeat(2 * Balance.WAVES_PER_SECTOR + 1) {
            g.startWave()
            var air = 0
            while (g.phase == Phase.WAVE) {
                g.update(1f / 30)
                air = maxOf(air, g.enemies.count { it.type.layer == Layer.AIR })
            }
            airByWave.add(air)
            assertEquals(Phase.BUILD, g.phase)
        }
        // Waves 6 and 11 open sectors 2 and 3; nothing flies otherwise.
        val expected = List(airByWave.size) { w -> if (w + 1 == 6) 1 else if (w + 1 == 11) 2 else 0 }
        assertTrue("air per wave $airByWave", airByWave.zip(expected).all { (a, e) -> if (e == 0) a == 0 else a in 1..e })
        assertEquals(3, Balance.airRaidSize(7))
    }

    @Test
    fun airIsHitByCryoAndUpgradedPulseAndRail() {
        val g = Game(2)
        val ship = Enemy(EnemyType.GUNSHIP, 1f)
        val crawler = Enemy(EnemyType.GRUNT, 1f)
        for (type in TowerType.entries) {
            val t = Tower(type, 0, 0)
            assertTrue(g.canHit(t, crawler))
            assertEquals("$type L1", type == TowerType.CRYO, g.canHit(t, ship))
            t.level = Balance.AIR_LEVEL
            assertEquals("$type L$Balance.AIR_LEVEL", type != TowerType.PLASMA && type != TowerType.TESLA, g.canHit(t, ship))
        }
    }

    @Test
    fun airUnitsFlyAWavyLineToTheCore() {
        val g = Game(12)
        g.world.addSection()
        // Skip to the wave right after the sector opened.
        repeat(Balance.WAVES_PER_SECTOR) { g.startWave(); while (g.phase == Phase.WAVE) g.update(1f / 30); if (g.phase == Phase.GAME_OVER) return }
        g.lives = 1000
        g.startWave()
        val ys = ArrayList<Float>()
        var ship: Enemy? = null
        while (g.phase == Phase.WAVE) {
            g.update(1f / 30)
            ship = ship ?: g.enemies.firstOrNull { it.type.layer == Layer.AIR }
            ship?.let { if (it.alive) { ys.add(it.y); assertTrue(it.y in 0f..ROWS.toFloat()) } }
        }
        assertTrue("ship never flew", ys.size > 100)
        // It strays well off a straight line and doesn't touch the path's corners.
        assertTrue(ys.max() - ys.min() > 2f)
    }

    @Test
    fun bigGroundUnitsDontOverlap() {
        val g = Game(9)
        g.lives = 1000
        repeat(8) { g.startWave() ; while (g.phase == Phase.WAVE) {
            g.update(1f / 30)
            val big = g.enemies.filter { it.alive && it.type.layer == Layer.GROUND && it.radius >= Balance.QUEUE_RADIUS && it.dist > 1f }.sortedBy { it.dist }
            for ((a, b) in big.zipWithNext()) assertTrue("${a.type} and ${b.type} overlap", b.dist - a.dist >= (a.radius + b.radius) * 1.05f)
        } }
    }

    @Test
    fun tankHoldsUpGroundUnits() {
        val g = Game(9)
        g.lives = 1000
        g.wave = 7
        g.tanks.charges = 1
        assertFalse("only during a wave", g.launchTank())
        g.startWave()
        while (g.enemies.size < 15) g.update(1f / 30)
        assertTrue(g.launchTank())
        assertEquals(0, g.tanks.charges)
        assertFalse(g.launchTank())
        val tank = g.tanks.tank!!
        val behind = HashSet<Enemy>()
        var sawJam = false
        var kills = 0
        // Frames each pair in the line has spent piled up: a unit overtaking as the line forms
        // overlaps briefly, then backs off.
        var piled = HashMap<Pair<Enemy, Enemy>, Int>()
        while (g.tanks.tank != null && g.phase == Phase.WAVE) {
            val before = g.enemies.count { it.alive }
            g.update(1f / 30)
            kills += maxOf(0, before - g.enemies.count { it.alive })
            for (e in g.enemies) if (e.alive && e.type.layer == Layer.GROUND && e.dist < tank.dist) behind.add(e)
            for (e in behind) if (e.alive) assertTrue("${e.type} got past the tank", e.dist < tank.dist)
            // The held-up line is spaced out rather than piled on one spot.
            val jam = g.enemies.filter { it.alive && it.type.layer == Layer.GROUND && it.dist < tank.dist && it.dist > tank.dist - 3f }.sortedByDescending { it.dist }
            if (tank.blocked && jam.size >= 3) sawJam = true
            val now = HashMap<Pair<Enemy, Enemy>, Int>()
            for ((a, b) in jam.zipWithNext()) {
                if (a.dist - b.dist >= (a.radius + b.radius) * Balance.STACK_GAP * 0.9f) continue
                val frames = (piled[a to b] ?: 0) + 1
                assertTrue("${a.type} and ${b.type} piled up", frames < 60)
                now[a to b] = frames
            }
            piled = now
        }
        assertTrue("never held up a line", sawJam)
        assertTrue("never fired", kills > 0)
    }

    @Test
    fun tanksAreEarnedPerSectorAndSaved() {
        val g = Game(4)
        g.wave = Balance.WAVES_PER_SECTOR
        g.waves.complete()
        assertEquals(2, g.world.sections.size)
        assertEquals(1, g.tanks.charges)
        val r = Game.restore(g.saveState()!!)!!
        assertEquals(1, r.tanks.charges)
        // Saves from before tanks get one per sector opened.
        r.world.addSection()
        val old = r.saveState()!!.lines().filter { !it.startsWith("tanks=") }.joinToString("\n")
        assertEquals(2, Game.restore(old)!!.tanks.charges)
    }

    @Test
    fun cryoSlowsGunships() {
        val g = Game(14)
        g.lives = 1000
        repeat(Balance.WAVES_PER_SECTOR) { g.startWave(); while (g.phase == Phase.WAVE) g.update(1f / 30) }
        // Cryo on every free tile of the core sector: any gunship crossing it gets slowed.
        g.credits = 100_000
        for (c in buildableCells(g).filter { it.col >= 0 }) g.build(TowerType.CRYO, c.col, c.row)
        g.startWave()
        var slowed = false
        while (g.phase == Phase.WAVE) {
            g.update(1f / 30)
            if (g.enemies.any { it.type.layer == Layer.AIR && it.slowTimer > 0f }) slowed = true
        }
        assertTrue(slowed)
    }

    @Test
    fun fullyUpgradedSectorGainsPowerEfficiency() {
        val g = Game(19)
        g.credits = 1_000_000
        val cells = buildableCells(g).filter { it.col >= 0 }
        // Fill the core sector's base budget with railguns (3 each) and pulses (1).
        for (c in cells) if (g.build(TowerType.RAIL, c.col, c.row) == null) break
        for (c in cells) if (g.powerLeft(0) > 0) g.build(TowerType.PULSE, c.col, c.row)
        assertEquals(0, g.powerLeft(0))
        // Full but not maxed: no bonus.
        assertFalse(g.world.sections[0].efficient)
        val towers = g.towers.toList()
        for (t in towers.dropLast(1)) repeat(Balance.MAX_LEVEL) { g.upgrade(t) }
        assertFalse(g.world.sections[0].efficient)
        repeat(Balance.MAX_LEVEL) { g.upgrade(towers.last()) }
        assertTrue(g.world.sections[0].efficient)
        assertEquals(Balance.EFFICIENCY_BONUS, g.powerLeft(0))
        assertTrue(g.events.any { it is GameEvent.Message && it.text.contains("EFFICIENCY") })
        // The bonus can be spent on fresh towers without losing it.
        val free = cells.first { g.canBuild(it.col, it.row) }
        assertNotNull(g.build(TowerType.PULSE, free.col, free.row))
        assertTrue(g.world.sections[0].efficient)
        // And it survives a save.
        val r = Game.restore(g.saveState()!!)!!
        assertTrue(r.world.sections[0].efficient)
        assertEquals(g.powerLeft(0), r.powerLeft(0))
    }

    @Test
    fun cosmeticEffectsDontChangeGameplay() {
        // Same seed; one game also burns through cosmetic randomness every frame.
        fun play(extraDebris: Boolean): String {
            val g = Game(77)
            g.lives = 1_000_000
            val trace = StringBuilder()
            while (g.wave < 7) {
                g.startWave()
                while (g.phase == Phase.WAVE) {
                    if (extraDebris) g.feedback.debris(0f, 0f, 0, 5)
                    g.update(1f / 30)
                    for (e in g.enemies) if (e.type.layer == Layer.AIR) trace.append(e.y).append(',')
                }
            }
            assertTrue("no air raid reached", trace.isNotEmpty())
            return trace.toString()
        }
        assertEquals(play(false), play(true))
    }

    @Test
    fun pathsWander() {
        // Most sectors should double back on themselves, not just run straight west.
        var backtracking = 0
        for (seed in 1L..50L) {
            val world = World(seed)
            world.addSection()
            val path = world.sections[1].path
            if (path.zipWithNext().any { (a, b) -> b.col < a.col }) backtracking++
            assertTrue("seed $seed path too short: ${path.size}", path.size > SECTION_COLS * 2)
        }
        assertTrue("only $backtracking/50 sectors backtrack", backtracking >= 35)
    }

    @Test
    fun saveRoundTrip() {
        val g = Game(42)
        g.world.addSection()
        val c = buildableCells(g).first()
        val t = g.build(TowerType.TESLA, c.col, c.row)!!
        g.upgrade(t)
        t.mode = TargetMode.STRONG
        val text = g.saveState()!!
        val r = Game.restore(text)!!
        assertEquals(g.credits, r.credits)
        assertEquals(2, r.world.sections.size)
        assertEquals(g.world.waypoints.toList(), r.world.waypoints.toList())
        val rt = r.towerAt(c.col, c.row)!!
        assertEquals(2, rt.level)
        assertEquals(TargetMode.STRONG, rt.mode)
        assertEquals(text, r.saveState())

        // Mid-wave the save is the pre-wave checkpoint.
        g.startWave()
        g.update(1f)
        assertEquals(text, g.saveState())
        assertNull(Game.restore("garbage"))
    }

    @Test
    fun undefendedCoreFalls() {
        val g = Game(5)
        var waves = 0
        while (g.phase != Phase.GAME_OVER && waves < 10) {
            g.startWave(); runWave(g); waves++
        }
        assertEquals(Phase.GAME_OVER, g.phase)
        assertTrue("fell on wave $waves", waves <= 3)
        assertNull(g.saveState())
    }

    /** A simple bot should get well into the game but not play forever. */
    @Test
    fun botBalance() {
        val reached = (1L..12L).map { seed -> playBot(Game(seed), maxWaves = 60) }
        println("bot reached waves: $reached")
        assertTrue("too hard: $reached", reached.average() >= 10)
        assertTrue("too easy: $reached", reached.average() < 60)
    }

    private fun playBot(g: Game, maxWaves: Int): Int {
        val types = TowerType.entries
        while (g.phase != Phase.GAME_OVER && g.wave < maxWaves) {
            var spent = true
            while (spent) {
                spent = false
                // Spend: alternate new towers by the path (where there's power) with upgrading
                // the cheapest-to-upgrade tower.
                val type = types[(g.towers.size * 7 + g.wave) % types.size]
                if (g.towers.size < 3 + g.wave && g.credits >= type.cost) {
                    val cell = buildableCells(g).filter { g.hasPowerFor(type, it.col) }.maxByOrNull { nearPath(g, it) }
                    if (cell != null && g.build(type, cell.col, cell.row) != null) spent = true
                }
                if (!spent) {
                    val t = g.towers.filter { it.upgradeCost != null }.minByOrNull { it.upgradeCost!! }
                    if (t != null && g.upgrade(t)) spent = true
                }
            }
            g.startWave()
            runWave(g)
        }
        return g.wave
    }

    private fun runWave(g: Game) {
        var guard = 0
        while (g.phase == Phase.WAVE && guard++ < 200_000) g.update(1f / 30)
        assertTrue("wave stalled", guard < 200_000)
    }

    private fun buildableCells(g: Game) = (g.world.minCol until g.world.coreCol).flatMap { col ->
        (0 until ROWS).filter { g.canBuild(col, it) }.map { Cell(col, it) }
    }

    private fun nearPath(g: Game, c: Cell): Int {
        var n = 0
        for (dc in -2..2) for (dr in -2..2) if (g.world.isPath(c.col + dc, c.row + dr)) n++
        return n
    }

    @Test
    fun wavesScale() {
        assertNotNull(Balance.composeWave(1).firstOrNull())
        assertTrue(Balance.composeWave(10).size > Balance.composeWave(1).size)
        assertTrue(Balance.composeWave(5).any { it.type == EnemyType.BOSS })
        assertTrue(Balance.hpScale(10) > Balance.hpScale(2))
    }
}

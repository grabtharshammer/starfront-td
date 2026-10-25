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
        assertEquals(START_CREDITS - 50, g.credits)
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
    fun clearingAWaveExpandsTheField() {
        val g = Game(11)
        // Ring the start of the path with towers so wave 1 can't get through.
        for (c in buildableCells(g).sortedByDescending { nearPath(g, it) }.take(4)) g.build(TowerType.PULSE, c.col, c.row)
        val oldMin = g.world.minCol
        val towers = g.towers.toList()
        g.startWave()
        runWave(g)
        assertEquals(Phase.BUILD, g.phase)
        assertEquals(2, g.world.sections.size)
        assertEquals(oldMin - SECTION_COLS, g.world.minCol)
        assertEquals(towers, g.towers)
        assertTrue(g.events.any { it is GameEvent.Expanded })
        // The new sector is open for building.
        assertTrue(g.canBuild(g.world.minCol, (0 until ROWS).first { !g.world.isPath(g.world.minCol, it) }))
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
            // Spend: new towers in the freshest sector first, then upgrade the cheapest-to-upgrade tower.
            var spent = true
            while (spent) {
                spent = false
                val type = types[(g.towers.size * 7 + g.wave) % types.size]
                val frontierCols = g.world.frontier.startCol until g.world.frontier.endCol
                val inFrontier = g.towers.count { it.col in frontierCols }
                if (inFrontier < 4 && g.credits >= type.cost) {
                    val cell = buildableCells(g).filter { it.col in frontierCols || g.towers.size < 6 }
                        .maxByOrNull { nearPath(g, it) }
                    if (cell != null && g.build(type, cell.col, cell.row) != null) spent = true
                } else {
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
        assertNotNull(Game.composeWave(1).firstOrNull())
        assertTrue(Game.composeWave(10).size > Game.composeWave(1).size)
        assertTrue(Game.composeWave(5).any { it.type == EnemyType.BOSS })
        assertTrue(Game.hpScale(10) > Game.hpScale(2))
    }
}

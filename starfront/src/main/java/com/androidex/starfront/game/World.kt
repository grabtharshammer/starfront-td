package com.androidex.starfront.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

const val ROWS = 9
const val FIRST_SECTION_COLS = 12
const val SECTION_COLS = 8

data class Cell(val col: Int, val row: Int)

/** One vertical slice of the battlefield. Section 0 holds the core; each later one sits further west. */
class Section(
    val index: Int,
    val startCol: Int,
    val width: Int,
    val entryRow: Int,
    val exitRow: Int,
    /** Path cells ordered west to east, i.e. in the direction enemies travel. */
    val path: List<Cell>,
) {
    val endCol get() = startCol + width
}

/**
 * The battlefield. It starts as a single section next to the core and grows west by one
 * section after every cleared wave. Enemies always enter at the western edge, so the path
 * gets longer and every tower built so far keeps seeing action.
 */
class World(val seed: Long) {
    /** Index 0 is the eastern (core) section; the last one is the current frontier. */
    val sections = ArrayList<Section>()
    private val pathKeys = HashSet<Long>()

    /** Corner points of the enemy route, from the warp gate to the core, in cell units. */
    var waypoints: FloatArray = FloatArray(0)
        private set
    private var cumulative: FloatArray = FloatArray(0)
    var pathLength = 0f
        private set

    val minCol get() = sections.last().startCol
    val coreCol get() = sections[0].endCol
    val coreRow get() = sections[0].exitRow
    val frontier get() = sections.last()

    init {
        addSection()
    }

    fun addSection(): Section {
        val index = sections.size
        val rng = Random(seed * 1_000_003L + index * 7_919L)
        val width = if (index == 0) FIRST_SECTION_COLS else SECTION_COLS
        val startCol = if (index == 0) 0 else minCol - width
        val exitRow = if (index == 0) ROWS / 2 else frontier.entryRow
        val turns = if (index == 0) 3 + rng.nextInt(2) else 2 + rng.nextInt(2)
        val (path, entryRow) = carve(rng, startCol, width, exitRow, turns)
        val section = Section(index, startCol, width, entryRow, exitRow, path)
        sections.add(section)
        for (c in path) pathKeys.add(key(c.col, c.row))
        rebuildRoute()
        return section
    }

    fun inBounds(col: Int, row: Int) = col >= minCol && col < coreCol && row >= 0 && row < ROWS

    fun isPath(col: Int, row: Int) = key(col, row) in pathKeys

    fun sectionAt(col: Int): Section? = sections.firstOrNull { col >= it.startCol && col < it.endCol }

    /** Writes the position at [dist] along the route into [out] (x, y, heading radians). */
    fun positionAt(dist: Float, out: FloatArray) {
        val n = waypoints.size / 2
        if (dist <= 0f) {
            segmentPoint(0, 0f, out); return
        }
        // Binary search for the segment holding dist.
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cumulative[mid] <= dist) lo = mid else hi = mid
        }
        val segLen = cumulative[lo + 1] - cumulative[lo]
        val t = if (segLen <= 0f) 0f else ((dist - cumulative[lo]) / segLen).coerceIn(0f, 1f)
        segmentPoint(lo, t, out)
    }

    private fun segmentPoint(i: Int, t: Float, out: FloatArray) {
        val x0 = waypoints[i * 2]
        val y0 = waypoints[i * 2 + 1]
        val x1 = waypoints[i * 2 + 2]
        val y1 = waypoints[i * 2 + 3]
        out[0] = x0 + (x1 - x0) * t
        out[1] = y0 + (y1 - y0) * t
        out[2] = kotlin.math.atan2(y1 - y0, x1 - x0)
    }

    private fun rebuildRoute() {
        val pts = ArrayList<Float>()
        fun add(x: Float, y: Float) {
            // Drop the middle of collinear runs so the route is just its corners.
            val n = pts.size / 2
            if (n >= 2) {
                val ax = pts[pts.size - 4]
                val ay = pts[pts.size - 3]
                val bx = pts[pts.size - 2]
                val by = pts[pts.size - 1]
                if ((ax == bx && bx == x) || (ay == by && by == y)) {
                    pts[pts.size - 2] = x
                    pts[pts.size - 1] = y
                    return
                }
            }
            pts.add(x); pts.add(y)
        }
        add(minCol - 1.5f, frontier.entryRow + 0.5f)
        for (s in sections.asReversed()) for (c in s.path) add(c.col + 0.5f, c.row + 0.5f)
        add(coreCol + 0.5f, coreRow + 0.5f)
        waypoints = pts.toFloatArray()
        val n = waypoints.size / 2
        cumulative = FloatArray(n)
        for (i in 1 until n) {
            cumulative[i] = cumulative[i - 1] + hypot(
                waypoints[i * 2] - waypoints[i * 2 - 2],
                waypoints[i * 2 + 1] - waypoints[i * 2 - 1],
            )
        }
        pathLength = cumulative[n - 1]
    }

    companion object {
        fun key(col: Int, row: Int): Long = (col.toLong() shl 32) or (row.toLong() and 0xffffffffL)

        /**
         * Carves a snaking path across one section, from its east edge at [exitRow] back to the
         * west edge. Turns happen in columns at least two apart so the path never touches itself.
         * Returns the path ordered west to east and the row it enters on.
         */
        fun carve(rng: Random, startCol: Int, width: Int, exitRow: Int, turns: Int): Pair<List<Cell>, Int> {
            val turnCols = HashSet<Int>()
            for (c in (1..width - 2).shuffled(rng)) {
                if (turnCols.size >= turns) break
                if (turnCols.all { abs(it - c) >= 2 }) turnCols.add(c)
            }
            val cells = ArrayList<Cell>()
            var row = exitRow
            for (local in width - 1 downTo 0) {
                val col = startCol + local
                cells.add(Cell(col, row))
                if (local in turnCols) {
                    val options = (1..ROWS - 2).filter { abs(it - row) >= 2 }
                    val newRow = options[rng.nextInt(options.size)]
                    val step = if (newRow > row) 1 else -1
                    while (row != newRow) {
                        row += step
                        cells.add(Cell(col, row))
                    }
                }
            }
            cells.reverse()
            return cells to row
        }
    }
}

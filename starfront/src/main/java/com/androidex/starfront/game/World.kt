package com.androidex.starfront.game

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

const val ROWS = 9
// Widths are odd so the path lattice (see carve) has a node column next to each edge.
const val FIRST_SECTION_COLS = 15
const val SECTION_COLS = 13
const val WAVES_PER_SECTOR = 5
/** Power available for towers in each sector (the core sector is larger). */
const val FIRST_SECTION_POWER = 12
const val SECTION_POWER = 10
/** Extra power a sector earns once it is fully built out and every tower in it is maxed. */
const val EFFICIENCY_BONUS = 5

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
    val basePower get() = if (index == 0) FIRST_SECTION_POWER else SECTION_POWER
    /** Earned once and kept, so spending the bonus on new level-1 towers doesn't revoke it. */
    var efficient = false
    val power get() = basePower + if (efficient) EFFICIENCY_BONUS else 0
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
        val exitRow = if (index == 0) ROWS / 2 - 1 else frontier.entryRow
        val (path, entryRow) = carve(rng, startCol, width, exitRow)
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
        /** Section index for a column (section 0 starts at column 0; later ones extend west). */
        fun sectionIndexAt(col: Int) = if (col >= 0) 0 else 1 + (-col - 1) / SECTION_COLS

        fun key(col: Int, row: Int): Long = (col.toLong() shl 32) or (row.toLong() and 0xffffffffL)

        /**
         * Carves a wandering path across one section, from its east edge at [exitRow] to the
         * west edge. The path is a random self-avoiding walk over a lattice of nodes on odd
         * local columns and odd rows, joined through the cells between them. Because nodes sit
         * two cells apart, separate stretches of path always have a buildable gap between them.
         * Returns the path ordered west to east and the row it enters on.
         */
        fun carve(rng: Random, startCol: Int, width: Int, exitRow: Int): Pair<List<Cell>, Int> {
            val cols = (width - 1) / 2 // node columns at local 1, 3, ..., width - 2
            val rows = (ROWS - 1) / 2 // node rows at 1, 3, ..., ROWS - 2
            val startRow = (exitRow - 1) / 2
            val target = (cols * rows * 0.7f).toInt()

            // Many random walks from the east node column; keep the one that reaches the west
            // column with a length near target, favouring walks that double back east and
            // penalising long straight runs. Straight across is the fallback.
            fun score(walk: List<Int>): Int {
                var s = abs(walk.size - target)
                var run = 1
                for (i in 1 until walk.size) {
                    val step = walk[i] - walk[i - 1]
                    if (step == rows) s -= 2
                    run = if (i >= 2 && step == walk[i - 1] - walk[i - 2]) run + 1 else 1
                    if (run > 2) s += 2
                }
                return s
            }
            var best: List<Int> = (cols - 1 downTo 0).map { it * rows + startRow }
            var bestScore = score(best)
            val visited = BooleanArray(cols * rows)
            repeat(400) {
                visited.fill(false)
                val walk = ArrayList<Int>()
                var cur = (cols - 1) * rows + startRow
                while (true) {
                    walk.add(cur)
                    visited[cur] = true
                    val c = cur / rows
                    val r = cur % rows
                    if (c == 0 && (walk.size >= target || rng.nextInt(3) == 0)) break
                    val next = ArrayList<Int>(4)
                    if (c > 0 && !visited[cur - rows]) next.add(cur - rows)
                    if (c < cols - 1 && !visited[cur + rows]) next.add(cur + rows)
                    if (r > 0 && !visited[cur - 1]) next.add(cur - 1)
                    if (r < rows - 1 && !visited[cur + 1]) next.add(cur + 1)
                    if (next.isEmpty()) break
                    cur = next[rng.nextInt(next.size)]
                }
                if (walk.last() / rows == 0) {
                    val score = score(walk)
                    if (score < bestScore) {
                        best = walk
                        bestScore = score
                    }
                }
            }

            // Expand nodes into cells, east edge to west edge.
            val cells = ArrayList<Cell>()
            fun cell(local: Int, row: Int) = Cell(startCol + local, row)
            cells.add(cell(width - 1, exitRow))
            var prev: Cell? = null
            for (n in best) {
                val here = cell(1 + 2 * (n / rows), 1 + 2 * (n % rows))
                prev?.let { cells.add(Cell((it.col + here.col) / 2, (it.row + here.row) / 2)) }
                cells.add(here)
                prev = here
            }
            val entryRow = prev!!.row
            cells.add(cell(0, entryRow))
            cells.reverse()
            return cells to entryRow
        }
    }
}

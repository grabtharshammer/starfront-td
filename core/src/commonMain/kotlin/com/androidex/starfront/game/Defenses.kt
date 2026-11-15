package com.androidex.starfront.game

/** The player's towers: building, upgrading, selling and swapping, and each sector's power budget. */
class Defenses(private val game: Game) {
    val towers = ArrayList<Tower>()
    private val index = HashMap<Long, Tower>()

    private val feedback get() = game.feedback

    fun at(col: Int, row: Int): Tower? = index[World.key(col, row)]

    fun canBuild(col: Int, row: Int) =
        game.phase != Phase.GAME_OVER && game.world.inBounds(col, row) && !game.world.isPath(col, row) && at(col, row) == null

    // ---- Power ----

    /** Power still free in the sector with index [section]. */
    fun powerLeft(section: Int): Int {
        val budget = game.world.sections.getOrNull(section)?.power ?: return 0
        return budget - powerUsed(section)
    }

    private fun powerUsed(section: Int) = towers.sumOf { if (World.sectionIndexAt(it.col) == section) it.type.power else 0 }

    fun hasPowerFor(type: TowerType, col: Int) = powerLeft(World.sectionIndexAt(col)) >= type.power

    /** Whether [t] could be swapped for [type] power-wise (its own power is freed first). */
    fun hasPowerToReplace(t: Tower, type: TowerType) = powerLeft(World.sectionIndexAt(t.col)) + t.type.power >= type.power

    /** Whether a sector qualifies for the efficiency bonus: base power all used, every tower maxed. */
    fun isFullyUpgraded(section: Int): Boolean {
        val s = game.world.sections.getOrNull(section) ?: return false
        val inSector = towers.filter { World.sectionIndexAt(it.col) == section }
        return inSector.isNotEmpty() && powerUsed(section) >= s.basePower && inSector.all { it.level >= Balance.MAX_LEVEL }
    }

    private fun checkEfficiency(col: Int) {
        val index = World.sectionIndexAt(col)
        val s = game.world.sections.getOrNull(index) ?: return
        if (s.efficient || !isFullyUpgraded(index)) return
        s.efficient = true
        feedback.message("SECTOR ${index + 1} POWER EFFICIENCY  ·  +${Balance.EFFICIENCY_BONUS} PWR")
        feedback.sound(Sfx.EFFICIENCY)
        feedback.fx(FxKind.RING, s.startCol + s.width / 2f, ROWS + 0.3f, BARRIER_COLOR, 0.8f, size = s.width / 2f)
    }

    // ---- Player actions ----

    fun build(type: TowerType, col: Int, row: Int): Tower? {
        if (!canBuild(col, row) || game.credits < type.cost || !hasPowerFor(type, col)) return null
        game.credits -= type.cost
        feedback.sound(Sfx.BUILD)
        return place(Tower(type, col, row)).also { checkEfficiency(col) }
    }

    fun upgrade(t: Tower): Boolean {
        val cost = t.upgradeCost ?: return false
        if (game.credits < cost || game.phase == Phase.GAME_OVER) return false
        game.credits -= cost
        t.invested += cost
        t.level++
        feedback.sound(Sfx.UPGRADE)
        checkEfficiency(t.col)
        feedback.fx(FxKind.RING, t.x, t.y, t.type.color, 0.5f, size = 0.8f)
        return true
    }

    fun sell(t: Tower) {
        if (game.phase == Phase.GAME_OVER || !remove(t)) return
        game.credits += t.sellValue
        feedback.sound(Sfx.SELL)
        feedback.floater("+${t.sellValue}", t.x, t.y)
    }

    fun replaceCost(t: Tower, type: TowerType) = type.cost - t.sellValue

    /** Swaps [t] for a fresh level-1 tower of [type], refunding the old one. */
    fun replace(t: Tower, type: TowerType): Tower? {
        if (type == t.type || game.credits < replaceCost(t, type) || !hasPowerToReplace(t, type) || game.phase == Phase.GAME_OVER) return null
        if (!remove(t)) return null
        game.credits += t.sellValue - type.cost
        feedback.sound(Sfx.BUILD)
        return place(Tower(type, t.col, t.row))
    }

    private fun remove(t: Tower): Boolean {
        if (index.remove(World.key(t.col, t.row)) == null) return false
        towers.remove(t)
        return true
    }

    private fun place(t: Tower): Tower {
        towers.add(t)
        index[World.key(t.col, t.row)] = t
        feedback.fx(FxKind.RING, t.x, t.y, t.type.color, 0.4f, size = 0.7f)
        return t
    }

    /** Puts back a tower from a save; false if its cell isn't free. */
    internal fun restore(t: Tower): Boolean {
        if (!canBuild(t.col, t.row)) return false
        towers.add(t)
        index[World.key(t.col, t.row)] = t
        return true
    }
}

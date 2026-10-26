package com.androidex.starfront.game

/** Text save format: what's needed to rebuild a game between waves (sectors regenerate from the seed). */
object SaveCodec {
    private const val HEADER = "starfront/2"

    fun encode(g: Game): String = buildString {
        appendLine(HEADER)
        appendLine("seed=${g.seed}")
        appendLine("sections=${g.world.sections.size}")
        appendLine("wave=${g.wave}")
        appendLine("credits=${g.credits}")
        appendLine("lives=${g.lives}")
        appendLine("efficient=${g.world.sections.filter { it.efficient }.joinToString(",") { it.index.toString() }}")
        for (t in g.towers) appendLine("tower=${t.type.name},${t.col},${t.row},${t.level},${t.invested},${t.mode.name},${t.kills}")
    }

    /** Null if the text isn't a readable save. */
    fun decode(text: String): Game? = try {
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
        values["efficient"]?.split(',')?.filter { it.isNotBlank() }?.forEach { g.world.sections[it.toInt()].efficient = true }
        for (line in lines) {
            if (!line.startsWith("tower=")) continue
            val f = line.removePrefix("tower=").split(',')
            val t = Tower(TowerType.valueOf(f[0]), f[1].toInt(), f[2].toInt())
            t.level = f[3].toInt().coerceIn(1, Balance.MAX_LEVEL)
            t.invested = f[4].toInt()
            t.mode = TargetMode.valueOf(f[5])
            t.kills = f[6].toInt()
            require(g.defenses.restore(t))
        }
        g
    } catch (e: Exception) {
        null
    }
}

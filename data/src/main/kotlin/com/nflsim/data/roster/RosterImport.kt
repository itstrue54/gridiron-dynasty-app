package com.nflsim.data.roster

import com.nflsim.engine.gen.ArchetypeInference
import com.nflsim.engine.gen.PlayerGenerator
import com.nflsim.engine.model.Archetype
import com.nflsim.engine.model.HiddenTraits
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.OverallWeights
import com.nflsim.engine.ratings.rawOverall
import com.nflsim.engine.rng.Rng
import com.nflsim.engine.rng.SplitMixRng

/** How much of a player the file actually described. */
enum class ImportFidelity {
    /** Every position-relevant rating was supplied. Nothing invented. */
    FULL,
    /** Some ratings supplied; the rest inferred from what was there. */
    PARTIAL,
    /** Only an overall (or nothing) - a full sheet was generated to match. */
    OVERALL_ONLY,
}

data class RowIssue(val row: Int, val player: String, val message: String)

data class ImportReport(
    val rowsRead: Int,
    val imported: Int,
    val skipped: Int,
    val fidelity: Map<ImportFidelity, Int>,
    val errors: List<RowIssue>,
    val warnings: List<RowIssue>,
    val unknownColumns: List<String>,
    val recognisedRatingColumns: List<RatingId>,
) {
    val ok: Boolean get() = errors.isEmpty()

    fun summary(): String = buildString {
        appendLine("Read $rowsRead rows: $imported imported, $skipped skipped")
        appendLine("Ratings columns recognised: ${recognisedRatingColumns.size} of ${RatingId.COUNT}")
        fidelity.entries.sortedBy { it.key.ordinal }.forEach { (level, n) ->
            appendLine("  ${level.name.lowercase().replace('_', ' ')}: $n")
        }
        if (unknownColumns.isNotEmpty()) {
            appendLine("Ignored columns: ${unknownColumns.joinToString(", ")}")
        }
        if (warnings.isNotEmpty()) {
            appendLine("Warnings (${warnings.size}):")
            warnings.take(10).forEach { appendLine("  row ${it.row} ${it.player}: ${it.message}") }
            if (warnings.size > 10) appendLine("  ... and ${warnings.size - 10} more")
        }
        if (errors.isNotEmpty()) {
            appendLine("Errors (${errors.size}):")
            errors.take(10).forEach { appendLine("  row ${it.row} ${it.player}: ${it.message}") }
            if (errors.size > 10) appendLine("  ... and ${errors.size - 10} more")
        }
    }
}

data class ImportResult(val players: List<Player>, val report: ImportReport)

/**
 * Reads a roster file into real Players.
 *
 * The game ships fictional. This is how you bring your own: a CSV you supply,
 * at whatever level of detail you have. Three tiers all work -
 *
 *   name,position                          -> a plausible player is generated
 *   name,position,ovr                      -> generated to hit that overall
 *   name,position,spd,acc,awr,mcv,...      -> your numbers, used as given
 *
 * Anything missing is filled in deterministically from the seed, so the same
 * file always produces the same league. Anything unrecognised is reported.
 */
object RosterImporter {

    fun import(
        csvText: String,
        year: Int,
        seed: Long = 0L,
        firstPlayerId: Int = 1,
        teamIdFor: (String) -> TeamId? = { null },
    ): ImportResult = importTable(Csv.parse(csvText), year, seed, firstPlayerId, teamIdFor)

    /**
     * The same, from rows already split into cells: a header row, then one
     * row a player. The JSON reader (RosterJson) arrives here too, so every
     * format gets the same aliases, fidelity levels and report. [rowOffset]
     * is what a row's index is reported as, past the header: 2 for a CSV
     * line number, 1 for the n-th player of a JSON file.
     */
    fun importTable(
        rows: List<List<String>>,
        year: Int,
        seed: Long = 0L,
        firstPlayerId: Int = 1,
        teamIdFor: (String) -> TeamId? = { null },
        rowOffset: Int = 2,
    ): ImportResult {
        if (rows.isEmpty()) {
            return ImportResult(
                emptyList(),
                ImportReport(0, 0, 0, emptyMap(), listOf(RowIssue(0, "", "file is empty")),
                    emptyList(), emptyList(), emptyList())
            )
        }

        val header = rows.first().map { RosterFormat.normalise(it) }
        val body = rows.drop(1)
        val index = Index(header)

        val players = mutableListOf<Player>()
        val errors = mutableListOf<RowIssue>()
        val warnings = mutableListOf<RowIssue>()
        val fidelity = mutableMapOf<ImportFidelity, Int>()
        var nextId = firstPlayerId
        var skipped = 0

        body.forEachIndexed { i, cells ->
            val rowNumber = i + rowOffset
            val get = { keys: Set<String> -> index.value(keys, cells) }
            val nameForReport = displayName(get) ?: "(row $rowNumber)"

            val positionRaw = get(RosterFormat.Columns.POSITION)
            if (positionRaw.isNullOrBlank()) {
                errors += RowIssue(rowNumber, nameForReport, "no position column value")
                skipped++
                return@forEachIndexed
            }
            val positionKey = RosterFormat.normalise(positionRaw)
            val position = RosterFormat.POSITION_ALIASES[positionKey]
            if (position == null) {
                errors += RowIssue(rowNumber, nameForReport, "unknown position '$positionRaw'")
                skipped++
                return@forEachIndexed
            }
            if (positionKey in RosterFormat.AMBIGUOUS_POSITIONS) {
                warnings += RowIssue(rowNumber, nameForReport,
                    "'$positionRaw' is ambiguous, read as ${position.label}")
            }

            val rng = SplitMixRng(seed).split("import|row=$rowNumber|$nameForReport")

            val supplied = index.ratings(cells)
            val overall = get(RosterFormat.Columns.OVERALL)?.toIntOrNull()

            val (ratings, level) = buildRatings(position, supplied, overall, rng)
            if (level == ImportFidelity.OVERALL_ONLY && overall == null) {
                warnings += RowIssue(rowNumber, nameForReport,
                    "no ratings and no overall - generated an average player")
            }
            fidelity[level] = (fidelity[level] ?: 0) + 1

            val archetype = resolveArchetype(get, position, ratings, rowNumber, nameForReport, warnings, rng)

            val (first, last) = splitName(get) ?: run {
                warnings += RowIssue(rowNumber, "(row $rowNumber)", "no name, generated one")
                com.nflsim.engine.gen.NameGenerator.fullName(rng)
            }

            val age = get(RosterFormat.Columns.AGE)?.toIntOrNull()
            val birthYear = get(RosterFormat.Columns.BIRTH_YEAR)?.toIntOrNull()
                ?: age?.let { year - it }
                ?: (year - (24 + rng.nextInt(8)))

            val template = PlayerGenerator.generate(
                id = PlayerId(nextId), position = position,
                targetOverall = rawOverall(position, ratings),
                year = year, rng = rng, archetype = archetype,
            )

            players += template.copy(
                id = PlayerId(nextId++),
                firstName = first,
                lastName = last,
                birthYear = birthYear,
                heightIn = get(RosterFormat.Columns.HEIGHT_IN)?.let(::parseHeight) ?: template.heightIn,
                weightLb = get(RosterFormat.Columns.WEIGHT_LB)?.toIntOrNull() ?: template.weightLb,
                college = get(RosterFormat.Columns.COLLEGE)?.takeIf { it.isNotBlank() } ?: template.college,
                jersey = get(RosterFormat.Columns.JERSEY)?.toIntOrNull(),
                ratings = ratings,
                traits = resolveTraits(get, template.traits),
                teamId = get(RosterFormat.Columns.TEAM)?.let { teamIdFor(it.trim().uppercase()) },
                accruedSeasons = maxOf(0, (year - birthYear) - 22),
            )
        }

        return ImportResult(
            players,
            ImportReport(
                rowsRead = body.size,
                imported = players.size,
                skipped = skipped,
                fidelity = fidelity,
                errors = errors,
                warnings = warnings,
                unknownColumns = index.unknown,
                recognisedRatingColumns = index.ratingColumns.values.distinct(),
            )
        )
    }

    // ---------------------------------------------------------------

    /**
     * Fills the sheet. Supplied ratings always win. Position-relevant gaps are
     * filled from whatever level the file did describe; irrelevant gaps get a
     * low baseline so a quarterback does not import with 70 man coverage.
     */
    private fun buildRatings(
        position: Position,
        supplied: Map<RatingId, Int>,
        overall: Int?,
        rng: Rng,
    ): Pair<Ratings, ImportFidelity> {
        val relevant = OverallWeights.forPosition(position).keys

        if (supplied.isEmpty()) {
            val target = overall ?: 68
            val generated = PlayerGenerator.generate(
                PlayerId(0), position, target.coerceIn(1, 99), 2000, rng
            )
            return generated.ratings to ImportFidelity.OVERALL_ONLY
        }

        val suppliedRelevant = relevant.count { it in supplied }
        val level = if (suppliedRelevant == relevant.size) ImportFidelity.FULL
                    else ImportFidelity.PARTIAL

        // Baseline for gaps: what this player looks like where we do have data.
        val relevantMean = relevant.mapNotNull { supplied[it] }.average()
            .takeIf { !it.isNaN() }?.toInt()
            ?: overall ?: 68

        val values = IntArray(RatingId.COUNT)
        for (rating in RatingId.entries) {
            values[rating.ordinal] = when {
                rating in supplied -> supplied.getValue(rating)
                rating in relevant -> relevantMean
                rating in KICKING && position.group != com.nflsim.engine.model.PositionGroup.ST -> 12
                else -> 34
            }.coerceIn(Ratings.MIN, Ratings.MAX)
        }
        return Ratings(values) to level
    }

    private val KICKING = setOf(
        RatingId.KICK_POWER, RatingId.KICK_ACCURACY,
        RatingId.PUNT_POWER, RatingId.PUNT_ACCURACY,
    )

    private fun resolveArchetype(
        get: (Set<String>) -> String?,
        position: Position,
        ratings: Ratings,
        row: Int,
        name: String,
        warnings: MutableList<RowIssue>,
        rng: Rng,
    ): Archetype {
        val raw = get(RosterFormat.Columns.ARCHETYPE)
        if (!raw.isNullOrBlank()) {
            val found = RosterFormat.ARCHETYPE_ALIASES[position.group]
                ?.get(RosterFormat.normalise(raw))
            if (found != null) return found
            warnings += RowIssue(row, name,
                "unknown archetype '$raw' at ${position.label}, inferred from ratings")
        }
        val inference = ArchetypeInference.infer(position, ratings)
        if (inference.confidence < 0.15f) {
            warnings += RowIssue(row, name,
                "rating sheet does not clearly favour an archetype, guessed ${inference.archetype.label}")
        }
        return inference.archetype
    }

    private fun resolveTraits(get: (Set<String>) -> String?, generated: HiddenTraits): HiddenTraits {
        val devRaw = get(RosterFormat.Columns.DEV_CURVE) ?: return generated
        val dev = RosterFormat.DEV_ALIASES[RosterFormat.normalise(devRaw)] ?: return generated
        return generated.copy(developmentCurve = dev)
    }

    private fun displayName(get: (Set<String>) -> String?): String? =
        get(RosterFormat.Columns.FULL)?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(
                get(RosterFormat.Columns.FIRST)?.takeIf { it.isNotBlank() },
                get(RosterFormat.Columns.LAST)?.takeIf { it.isNotBlank() },
            ).takeIf { it.isNotEmpty() }?.joinToString(" ")

    private fun splitName(get: (Set<String>) -> String?): Pair<String, String>? {
        val first = get(RosterFormat.Columns.FIRST)?.trim()
        val last = get(RosterFormat.Columns.LAST)?.trim()
        if (!first.isNullOrBlank() && !last.isNullOrBlank()) return first to last

        val full = get(RosterFormat.Columns.FULL)?.trim()
        if (full.isNullOrBlank()) return null
        // "Smith, John" and "John Smith" both appear in exports.
        if (full.contains(',')) {
            val parts = full.split(',', limit = 2)
            return parts[1].trim() to parts[0].trim()
        }
        val parts = full.split(' ').filter { it.isNotBlank() }
        return when (parts.size) {
            0 -> null
            1 -> parts[0] to ""
            else -> parts.first() to parts.drop(1).joinToString(" ")
        }
    }

    /** Accepts 74, "6-2", "6'2\"" and "6 ft 2". Returns inches. */
    private fun parseHeight(raw: String): Int? {
        val text = raw.trim()
        text.toIntOrNull()?.let { return if (it in 55..90) it else null }
        val m = Regex("""(\d+)\s*(?:'|-|ft|feet)\s*(\d+)""").find(text) ?: return null
        return m.groupValues[1].toInt() * 12 + m.groupValues[2].toInt()
    }

    /** Header lookup, built once per file. */
    private class Index(header: List<String>) {

        private val byName: Map<String, Int> = header.withIndex()
            .associate { (i, name) -> name to i }

        val ratingColumns: Map<Int, RatingId> = header.withIndex().mapNotNull { (i, name) ->
            RosterFormat.RATING_ALIASES[name]?.let { i to it }
        }.toMap()

        val unknown: List<String> = header.withIndex()
            .filter { (i, name) ->
                name.isNotBlank() && i !in ratingColumns && !isKnownMeta(name)
            }
            .map { it.value }

        fun value(keys: Set<String>, cells: List<String>): String? {
            for (key in keys) {
                val i = byName[key] ?: continue
                val v = cells.getOrNull(i)?.trim()
                if (!v.isNullOrBlank()) return v
            }
            return null
        }

        fun ratings(cells: List<String>): Map<RatingId, Int> =
            ratingColumns.mapNotNull { (i, rating) ->
                cells.getOrNull(i)?.trim()?.toIntOrNull()?.let { rating to it.coerceIn(1, 99) }
            }.toMap()

        private fun isKnownMeta(name: String): Boolean = META.any { name in it }

        private companion object {
            /**
             * Must live in the companion, not as an instance property: `unknown`
             * reads it during construction, and instance properties initialise in
             * declaration order.
             */
            val META = listOf(
                RosterFormat.Columns.TEAM, RosterFormat.Columns.FIRST, RosterFormat.Columns.LAST,
                RosterFormat.Columns.FULL, RosterFormat.Columns.POSITION,
                RosterFormat.Columns.ARCHETYPE, RosterFormat.Columns.OVERALL,
                RosterFormat.Columns.JERSEY, RosterFormat.Columns.AGE,
                RosterFormat.Columns.BIRTH_YEAR, RosterFormat.Columns.HEIGHT_IN,
                RosterFormat.Columns.WEIGHT_LB, RosterFormat.Columns.COLLEGE,
                RosterFormat.Columns.DEV_CURVE,
            )
        }
    }
}

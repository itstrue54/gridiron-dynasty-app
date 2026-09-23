package com.nflsim.data

import com.nflsim.engine.gen.Tendencies
import com.nflsim.engine.gen.StaffGenerator
import com.nflsim.engine.model.CoachId
import com.nflsim.engine.model.Staff
import com.nflsim.engine.rng.SplitMixRng
import com.nflsim.engine.season.Dynasty
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Turning a dynasty into bytes and back.
 *
 * Deliberately knows nothing about Android: it hands back a ByteArray and the
 * app decides where to put it. That keeps :data testable on the JVM and keeps
 * file handling where it belongs.
 *
 * CBOR rather than JSON because a league carries 1,700 players with sixty
 * ratings each, and gzip on top of a binary format is roughly a third the size.
 */
@OptIn(ExperimentalSerializationApi::class)
object SaveFile {

    private val cbor = Cbor { ignoreUnknownKeys = true }

    fun encode(dynasty: Dynasty): ByteArray {
        val raw = cbor.encodeToByteArray(Envelope.serializer(),
            Envelope(CURRENT_SAVE_VERSION, dynasty))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Dynasty {
        val raw = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        val envelope = cbor.decodeFromByteArray(Envelope.serializer(), raw)
        return migrate(envelope)
    }

    /**
     * Save migration. Every model change that alters the shape of a Dynasty
     * needs a step here, in the same commit - a dynasty game that eats saves
     * on update is a dead dynasty game (docs/SPEC.md 9.1).
     */
    private fun migrate(envelope: Envelope): Dynasty = when (envelope.version) {
        CURRENT_SAVE_VERSION -> envelope.dynasty
        10, 9, 8, 7, 6 -> envelope.dynasty
        5, 4 -> formSquads(envelope.dynasty)
        3 -> formSquads(giveTendencies(envelope.dynasty))
        2 -> formSquads(giveTendencies(handOutPicks(envelope.dynasty)))
        1 -> formSquads(giveTendencies(handOutPicks(hireStaffs(envelope.dynasty))))
        else -> error(
            "save was written by version ${envelope.version}, this build reads $CURRENT_SAVE_VERSION")
    }

    /**
     * 10 -> 11: contract disputes arrived (SPEC 10.1). Nothing to move: a
     * save from before has nobody asking, and the asking starts next season.
     */

    /**
     * 9 -> 10: in-season form arrived (SPEC 10.1). Nothing to move: every
     * player starts level, which is what a new season does anyway.
     */

    /**
     * 8 -> 9: assisted tackles arrived. Nothing to move: seasons already
     * played have none, and their tackle counts mean what they meant.
     */

    /**
     * 7 -> 8: box scores are archived (SPEC 9.2). Nothing to move: a save
     * from before keeps the games it played as results only.
     */

    /**
     * 6 -> 7: the transactions wire arrived (SPEC 4.7). Nothing to move: a
     * save from before it starts its wire empty and fills it from the next
     * move, like the careers did at version 5.
     */

    /**
     * 5 -> 6: practice squads arrived (SPEC 7). A save from before them has
     * sixteen empty places per club; they are filled the way a new league's
     * are, from whoever is on the street and then from camp bodies, seeded off
     * the dynasty so the same save always gets the same squads.
     */
    private fun formSquads(dynasty: Dynasty): Dynasty =
        dynasty.copy(league = com.nflsim.engine.season.PracticeSquads.fill(dynasty.league, dynasty.seed))

    /**
     * 4 -> 5: careers and league history arrived (SPEC 9.2). Nothing needs
     * moving: a save written before them has no history, and both start
     * accumulating from the next season the save plays. What is gone is gone
     * - a dynasty carried over from version 4 has no record of the seasons it
     * already played, which is honest about what was never written down.
     */

    /**
     * 3 -> 4: coaches gained tendencies (SPEC 5.4). A save written before them
     * has coaches with none, who would all call games straight off their
     * schemes. Each draws his from his own id off the dynasty's seed, so the
     * same save always migrates to the same staffs.
     */
    private fun giveTendencies(dynasty: Dynasty): Dynasty {
        val league = dynasty.league
        val coaches = league.coaches.mapValues { (_, c) -> Tendencies.forExisting(c, dynasty.seed) }
        return dynasty.copy(league = league.copy(coaches = coaches))
    }

    /**
     * 2 -> 3: draft picks became assets a club owns (SPEC 8.4). A save written
     * before them has none, so every club is handed its own picks for the
     * next three drafts - the one after the season in progress and the two
     * after that.
     */
    private fun handOutPicks(dynasty: Dynasty): Dynasty {
        val league = dynasty.league
        if (league.picks.isNotEmpty()) return dynasty
        val picks = com.nflsim.engine.offseason.Picks.own(
            league.teams.map { it.id },
            (dynasty.year + 1)..(dynasty.year + com.nflsim.engine.offseason.Picks.WINDOW))
        return dynasty.copy(league = league.copy(picks = picks))
    }

    /**
     * 1 -> 2: coaching staffs arrived in M7. A save written before them has no
     * staff field, so every team decodes as [Staff.UNASSIGNED] and would
     * develop its players at a flat league average forever. Hire each of them
     * a staff off the dynasty's own seed, so the same save always migrates to
     * the same coaches.
     */
    private fun hireStaffs(dynasty: Dynasty): Dynasty {
        val league = dynasty.league
        if (league.teams.none { it.staff == Staff.UNASSIGNED }) return dynasty

        val coaches = league.coaches.toMutableMap()
        var nextCoachId = (coaches.keys.maxOfOrNull { it.v } ?: 0) + 1
        val rng = SplitMixRng(dynasty.seed)

        val teams = league.teams.map { team ->
            if (team.staff != Staff.UNASSIGNED) return@map team
            val (staff, hired) = StaffGenerator.generate(
                offenseScheme = team.offenseScheme,
                defenseScheme = team.defenseScheme,
                nextId = { CoachId(nextCoachId++) },
                rng = rng.split("staff|team=${team.id.v}"),
            )
            hired.forEach { coaches[it.id] = it }
            team.copy(staff = staff)
        }
        return dynasty.copy(league = league.copy(teams = teams, coaches = coaches))
    }

    @kotlinx.serialization.Serializable
    private data class Envelope(val version: Int, val dynasty: Dynasty)
}

package com.example.nflsimtext.ui

import com.example.nflsimtext.ui.components.FilterChipRow
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.stats.StatLine
import com.nflsim.engine.model.LeagueHistory
import com.nflsim.engine.model.ArchivedGame
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.PlayEvent
import com.example.nflsimtext.ui.components.PlayLogEntry
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.Scoreboard
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.Situation
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.components.SortState
import com.example.nflsimtext.ui.components.StatusTag
import com.example.nflsimtext.ui.components.TagTone
import com.example.nflsimtext.ui.components.TeamMark
import com.example.nflsimtext.ui.components.TeamScore
import com.example.nflsimtext.ui.theme.NdTheme
import com.example.nflsimtext.ui.theme.ThemeSetting
import com.example.nflsimtext.ui.theme.next
import com.nflsim.engine.model.Conference
import com.nflsim.engine.model.Division
import com.nflsim.engine.model.NewsKind
import com.nflsim.engine.model.Player
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.TeamId
import com.nflsim.engine.ratings.SchemeCatalog
import com.nflsim.engine.ratings.ScoutingLens
import com.nflsim.engine.ratings.SchemeFitGrade
import com.nflsim.engine.ratings.TraitScouting
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.ratings.schemeFit
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.DynastyPhase
import com.nflsim.engine.season.Schedule
import com.nflsim.engine.sim.PlayLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Shared formatting. Records take an en dash, yardage a true minus
// (docs/DESIGN.md 3).
// ---------------------------------------------------------------------------

internal fun String.enDashed() = replace('-', '–')

internal fun signed(value: Int) = if (value >= 0) "+$value" else "−${-value}"

/**
 * How the user's club reads one of its own players (SPEC 4.6). Nothing in the
 * app shows a true rating: it shows what the club believes.
 */
internal fun lensFor(dynasty: Dynasty, player: Player): ScoutingLens = ScoutingLens.of(
    playerId = player.id.v,
    viewerId = dynasty.userTeamId.v,
    confidence = ScoutingLens.ownPlayer(player.clubYears, dynasty.team.staff.scoutingDept),
)

/** Whether the club played in the week that just finished, rather than sat a bye. */
internal fun Dynasty.playedLastWeek(): Boolean = userResults().lastOrNull()?.week == week - 1

/** A club's mark, drawn from tokens: the league has no brand colours. */
@Composable
private fun Mark(abbrev: String) =
    TeamMark(abbrev, NdTheme.colors.sitNormal, NdTheme.colors.chalk)

@Composable
internal fun ScreenList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) =
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NdTheme.spacing.screen, end = NdTheme.spacing.screen,
            top = NdTheme.spacing.l, bottom = NdTheme.spacing.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.blockGap),
        content = content,
    )

// ---------------------------------------------------------------------------
// Hub - the screen you live on
// ---------------------------------------------------------------------------

@Composable
fun HubScreen(
    dynasty: Dynasty,
    store: DynastyStore,
    scope: CoroutineScope,
    theme: ThemeSetting,
    onTheme: (ThemeSetting) -> Unit,
    haptics: Boolean,
    onHaptics: (Boolean) -> Unit,
    onNavigate: (Tab) -> Unit = {},
) {
    val c = NdTheme.colors
    val team = dynasty.team
    val record = dynasty.record()
    val next = dynasty.nextGame()
    val roster = dynasty.league.roster(team.id)

    ScreenList {
        item {
            // Big type: the record drops under the club rather than fighting
            // its name for the line.
            val stacked = LocalConfiguration.current.fontScale > 1.3f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mark(team.abbrev)
                Column(Modifier.padding(start = NdTheme.spacing.m).weight(1f)) {
                    Text(team.name, style = NdTheme.type.display, color = c.chalk)
                    Text(
                        "${team.divisionName}, ${dynasty.year}",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                    // Who is in charge, which the hub never used to say.
                    dynasty.league.coaches[team.staff.headCoach]?.let { coach ->
                        Text(
                            "${coach.name}, head coach",
                            style = NdTheme.type.caption, color = c.chalkDim,
                        )
                    }
                    if (stacked) {
                        Text(
                            record.recordText.enDashed(),
                            style = NdTheme.type.display, color = c.chalk,
                        )
                    }
                }
                if (!stacked) {
                    Text(record.recordText.enDashed(), style = NdTheme.type.display, color = c.chalk)
                }
            }
            Text(
                "${record.pointsFor} for, ${record.pointsAgainst} against " +
                    "(${signed(record.pointDifferential)})",
                style = NdTheme.type.body, color = c.chalkDim,
            )
        }

        // Men waiting on an answer about their contracts (SPEC 10.1).
        val demands = com.nflsim.engine.season.ContractDisputes
            .pending(dynasty.league, team.id, dynasty.playerStats)
        if (demands.isNotEmpty()) {
            item {
                SituationBlock(
                    if (demands.size == 1) "A contract to answer" else "${demands.size} contracts to answer",
                    situation = Situation.THIRD_DOWN,
                    onClick = { onNavigate(Tab.DEMANDS) },
                ) {
                    Text(
                        demands.take(2).joinToString("; ") {
                            "${it.player.position.label} ${it.player.name} wants ${it.market / 1000}M a year"
                        } + ". Every week you leave it costs him morale.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                }
            }
        }

        // Injured reserve opens places the league's clubs fill on their own;
        // the user's are left open until the user fills them.
        val open = com.nflsim.engine.season.Transactions.ROSTER_LIMIT -
            com.nflsim.engine.season.RosterMoves.active(dynasty.league, team.id).size
        if (open > 0 && dynasty.phase == DynastyPhase.REGULAR_SEASON) {
            item {
                SituationBlock(
                    "${open} open ${if (open == 1) "place" else "places"} on the 53",
                    situation = Situation.RED_ZONE,
                    onClick = { onNavigate(Tab.MARKET) },
                ) {
                    Text(
                        "Injured reserve took them. Sign or promote somebody in Free agents.",
                        style = NdTheme.type.body, color = c.chalkDim,
                    )
                }
            }
        }

        item {
            Column {
                Text(nextUpTitle(dynasty, next), style = NdTheme.type.headline, color = c.chalk)
                Text(nextUpDetail(dynasty, next), style = NdTheme.type.body, color = c.chalkDim)
                Spacer(Modifier.height(NdTheme.spacing.m))
                PrimaryButton(
                    text = when (dynasty.phase) {
                        DynastyPhase.PLAYOFFS -> "Play the postseason"
                        DynastyPhase.OFFSEASON -> "Open the draft room"
                        else -> "Play week ${dynasty.week}"
                    },
                    onClick = {
                        // The year turns over through the draft room, where the
                        // club makes its own picks, rather than in one jump.
                        if (dynasty.phase == DynastyPhase.OFFSEASON) {
                            scope.launch {
                                store.openDraftRoom()
                                if (store.draftRoom != null) onNavigate(Tab.DRAFT)
                            }
                        } else {
                            scope.launch {
                                store.advance()
                                // Straight to the game the club just played.
                                if (store.dynasty?.playedLastWeek() == true) onNavigate(Tab.GAME)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !store.busy,
                )
                // Wrapped, not scrolled: half of these used to sit off the right
                // edge of the phone with nothing to say they were there, and the
                // row scrolled back to the start every time the hub was reopened.
                FlowRow(Modifier.fillMaxWidth()) {
                    if (dynasty.lastGame != null) HubLink("Game day") { onNavigate(Tab.GAME) }
                    HubLink("Game plan") { onNavigate(Tab.PLAN) }
                    HubLink("Staff") { onNavigate(Tab.STAFF) }
                    HubLink("Free agents") { onNavigate(Tab.MARKET) }
                    HubLink("Transactions") { onNavigate(Tab.WIRE) }
                    HubLink("Demands") { onNavigate(Tab.DEMANDS) }
                    HubLink("Scouting") { onNavigate(Tab.SCOUTING) }
                    HubLink("History") { onNavigate(Tab.HISTORY) }
                    HubLink("Tuning") { onNavigate(Tab.TUNING) }
                    HubLink("Theme: ${theme.label}") { onTheme(theme.next()) }
                    HubLink(if (haptics) "Haptics: on" else "Haptics: off") { onHaptics(!haptics) }
                    HubLink("Design") { onNavigate(Tab.GALLERY) }
                }
            }
        }

        val hurt = roster.filter { it.injuryWeeks > 0 }.sortedByDescending { it.injuryWeeks }
        val expiring = roster.count { p ->
            p.contract?.let { it.signedYear + it.years - 1 <= dynasty.year } ?: false
        }
        if (hurt.isNotEmpty() || expiring > 0) {
            item {
                SituationBlock(
                    "Needs attention",
                    situation = if (hurt.isNotEmpty()) Situation.RED_ZONE else Situation.NORMAL,
                ) {
                    hurt.take(3).forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${p.name}, ${p.position.label}",
                                style = NdTheme.type.data, color = c.chalk,
                                modifier = Modifier.weight(1f),
                            )
                            StatusTag(
                                // Longer than the season has left is out for the season,
                                // whether that reads as 25 weeks or 30.
                                injuryLabel(p.injuryWeeks, dynasty),
                                if (p.injuryWeeks > 4) TagTone.URGENT else TagTone.NEUTRAL,
                            )
                        }
                    }
                    if (hurt.size > 3) {
                        Text(
                            "${hurt.size - 3} more hurt.",
                            style = NdTheme.type.body, color = c.chalkDim,
                        )
                    }
                    if (expiring > 0) {
                        Text(
                            if (expiring == 1) "1 contract expires after the season."
                            else "$expiring contracts expire after the season.",
                            style = NdTheme.type.body, color = c.chalkDim,
                        )
                    }
                }
            }
        }

        val news = hubNews(dynasty.news)
        if (news.isNotEmpty()) {
            item {
                SituationBlock(
                    "News",
                    meta = "Week ${news.first().week}",
                    situation = if (news.any { it.kind == NewsKind.INJURY && it.team == dynasty.userTeam })
                        Situation.RED_ZONE else Situation.NORMAL,
                ) {
                    news.forEach { story ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StatusTag(
                                label(story.kind),
                                tone(story.kind),
                                Modifier.padding(end = NdTheme.spacing.s),
                            )
                            Text(
                                story.headline,
                                style = NdTheme.type.body,
                                color = if (story.team == dynasty.userTeam) c.chalk else c.chalkDim,
                            )
                        }
                    }
                }
            }
        }

        val last = dynasty.userResults().lastOrNull()
        if (last != null) {
            item {
                val us = last.scoreFor(dynasty.userTeamId)
                val them = last.scoreAgainst(dynasty.userTeamId)
                val opponent = dynasty.league.team(last.opponentOf(dynasty.userTeamId)!!)
                val verdict = if (us > them) "Won" else if (us < them) "Lost" else "Tied"
                SituationBlock("Last result", meta = "Week ${last.week}") {
                    Text(
                        "$verdict $us–$them " +
                            "${if (last.home == dynasty.userTeamId) "vs" else "at"} ${opponent.name}",
                        style = NdTheme.type.data, color = c.chalk,
                    )
                    SecondaryButton(
                        "See the box score",
                        { onNavigate(Tab.BOX) },
                        Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }

        item {
            SituationBlock(team.divisionName, meta = "Week ${dynasty.week}") {
                DataTable(
                    columns = listOf(
                        ColumnSpec("Team", 2.2f),
                        ColumnSpec("W–L", 1.1f),
                        ColumnSpec("For", 0.9f, numeric = true),
                        ColumnSpec("Against", 1.1f, numeric = true),
                    ),
                    rows = dynasty.standings().division(team.conference, team.division).map { id ->
                        val r = dynasty.standings().record(id)
                        RowData(
                            listOf(
                                dynasty.league.team(id).nickname,
                                r.recordText.enDashed(),
                                "${r.pointsFor}",
                                "${r.pointsAgainst}",
                            ),
                            highlight = id == dynasty.userTeamId,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The week's news as the hub carries it: newest first, and no more than
 * [NEWS_PER_KIND] of any one kind. Benchings are filed last, so taking the
 * five newest buried the week's results under four men losing their places.
 */
internal fun hubNews(all: List<com.nflsim.engine.model.NewsEvent>): List<com.nflsim.engine.model.NewsEvent> {
    val newestFirst = all.asReversed()
    return newestFirst
        .groupBy { it.kind }
        .flatMap { (_, of) -> of.take(NEWS_PER_KIND) }
        .sortedBy { newestFirst.indexOf(it) }
        .take(NEWS_SHOWN)
}

/**
 * What to call an injury: weeks, until the weeks run past what the season
 * has left - counting the playoffs, which a club has to plan for.
 */
internal fun injuryLabel(weeks: Int, dynasty: Dynasty): String {
    val left = (Schedule.WEEKS - dynasty.week + 1 + PLAYOFF_WEEKS).coerceAtLeast(1)
    return when {
        weeks >= left -> "Out for the season"
        weeks == 1 -> "Out 1 week"
        else -> "Out $weeks weeks"
    }
}

/** Wild card, divisional, conference, final. */
private const val PLAYOFF_WEEKS = 4

/** How much of the week's news the hub carries, and how much of any one kind. */
private const val NEWS_SHOWN = 5
private const val NEWS_PER_KIND = 2

private fun label(kind: NewsKind) = when (kind) {
    NewsKind.INJURY -> "Hurt"
    NewsKind.PERFORMANCE -> "Game"
    NewsKind.MILESTONE -> "Mark"
    NewsKind.HOT_SEAT -> "Seat"
    NewsKind.BENCHING -> "Bench"
    NewsKind.DISPUTE -> "Deal"
}

private fun tone(kind: NewsKind) = when (kind) {
    NewsKind.INJURY -> TagTone.URGENT
    // Stripe belongs to the club's own row in the division table below.
    NewsKind.HOT_SEAT -> TagTone.NEUTRAL
    NewsKind.MILESTONE -> TagTone.INFO
    NewsKind.PERFORMANCE -> TagTone.NEUTRAL
    NewsKind.BENCHING -> TagTone.INFO
    NewsKind.DISPUTE -> TagTone.INFO
}

@Composable
private fun HubLink(text: String, onClick: () -> Unit) = TextButton(onClick = onClick) {
    Text(text, style = NdTheme.type.label, color = NdTheme.colors.pylonText)
}

private fun nextUpTitle(dynasty: Dynasty, next: com.nflsim.engine.season.Matchup?): String = when {
    dynasty.phase == DynastyPhase.OFFSEASON -> "Season complete"
    next == null && dynasty.phase == DynastyPhase.REGULAR_SEASON -> "Week ${dynasty.week}, bye"
    next != null -> {
        val opponent = dynasty.league.team(next.opponentOf(dynasty.userTeamId)!!)
        "${if (next.home == dynasty.userTeamId) "vs" else "at"} ${opponent.name}"
    }
    else -> "Playoffs"
}

private fun nextUpDetail(dynasty: Dynasty, next: com.nflsim.engine.season.Matchup?): String = when {
    dynasty.phase == DynastyPhase.OFFSEASON -> {
        val champ = dynasty.champion?.let { dynasty.league.team(TeamId(it)).name }
        if (champ != null) "$champ took the title. Players develop, contracts expire, the draft runs."
        else "Players develop, contracts expire, the draft runs."
    }
    next == null && dynasty.phase == DynastyPhase.REGULAR_SEASON -> "Nobody to play this week."
    next != null -> {
        val opponent = dynasty.league.team(next.opponentOf(dynasty.userTeamId)!!)
        val r = dynasty.standings().record(opponent.id).recordText.enDashed()
        "Week ${dynasty.week}, $r, ${SchemeCatalog[opponent.offenseScheme].name}"
    }
    else -> "The bracket is set."
}

// ---------------------------------------------------------------------------

@Composable
fun StandingsScreen(dynasty: Dynasty) {
    val standings = dynasty.standings()
    ScreenList {
        Conference.entries.forEach { conference ->
            Division.entries.forEach { division ->
                item {
                    SituationBlock(
                        "${conference.label} ${division.name.lowercase()}",
                        meta = "Week ${dynasty.week}",
                    ) {
                        DataTable(
                            columns = listOf(
                                ColumnSpec("Team", 2.2f),
                                ColumnSpec("W–L", 1.1f),
                                ColumnSpec("For", 0.9f, numeric = true),
                                ColumnSpec("Against", 1.1f, numeric = true),
                            ),
                            rows = standings.division(conference, division).map { id ->
                                val r = standings.record(id)
                                RowData(
                                    listOf(
                                        dynasty.league.team(id).nickname,
                                        r.recordText.enDashed(),
                                        "${r.pointsFor}",
                                        "${r.pointsAgainst}",
                                    ),
                                    highlight = id == dynasty.userTeamId,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------

private val ROSTER_ORDER = listOf(
    Position.QB, Position.RB, Position.FB, Position.WR, Position.TE,
    Position.LT, Position.LG, Position.C, Position.RG, Position.RT,
    Position.EDGE, Position.DT, Position.LB, Position.CB, Position.S,
    Position.K, Position.P, Position.LS,
)

private val ROSTER_FILTERS: List<Pair<String, Set<Position>>> = listOf(
    "All" to ROSTER_ORDER.toSet(),
    "QB" to setOf(Position.QB),
    "RB" to setOf(Position.RB, Position.FB),
    "WR" to setOf(Position.WR),
    "TE" to setOf(Position.TE),
    "OL" to setOf(Position.LT, Position.LG, Position.C, Position.RG, Position.RT),
    "DL" to setOf(Position.EDGE, Position.DT),
    "LB" to setOf(Position.LB),
    "DB" to setOf(Position.CB, Position.S),
    "ST" to setOf(Position.K, Position.P, Position.LS),
)

@Composable
fun RosterScreen(
    dynasty: Dynasty,
    onDepthChart: () -> Unit = {},
    onPlayer: (Int) -> Unit = {},
) {
    val c = NdTheme.colors
    val team = dynasty.team
    val offense = SchemeCatalog.tuned(team.offenseScheme, dynasty.league.tuning)
    val defense = SchemeCatalog.tuned(team.defenseScheme, dynasty.league.tuning)
    val roster = dynasty.league.roster(team.id)
    var filter by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf(SortState(3)) }

    val positions = ROSTER_FILTERS.first { it.first == filter }.second
    val shown = roster.filter { it.position in positions }.sortedWith(rosterOrder(dynasty, sort))

    ScreenList {
        item {
            Column {
                Text(team.name, style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Offense ${offense.name}, fit ${SchemeFitGrade.describe(roster, offense, true)}",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
                Text(
                    "Defense ${defense.name}, fit ${SchemeFitGrade.describe(roster, defense, false)}",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
                SecondaryButton(
                    "Set the depth chart", onDepthChart,
                    Modifier.padding(top = NdTheme.spacing.s),
                )
                // SPEC 9.4: the roster leaves in the shape the game reads back.
                var exported by remember { mutableStateOf<String?>(null) }
                val context = LocalContext.current
                SecondaryButton(
                    "Export this roster",
                    {
                        exported = try {
                            "Saved to " + RosterExport.write(dynasty.league, context, team.abbrev)
                        } catch (e: Exception) {
                            e.message ?: "The file would not write."
                        }
                    },
                )
                exported?.let {
                    Text(it, style = NdTheme.type.caption, color = c.chalkDim)
                }
            }
        }
        item {
            com.example.nflsimtext.ui.components.FilterChipRow(
                ROSTER_FILTERS.map { it.first }, filter, { filter = it },
            )
        }
        item {
            DataTable(
                columns = listOf(
                    // EDGE is the widest label the position column carries, and
                    // a rating the club is still guessing at prints as a band.
                    ColumnSpec("Pos", 1.0f),
                    ColumnSpec("Player", 2.2f),
                    ColumnSpec("Age", 0.6f, numeric = true),
                    ColumnSpec("Ovr", 1.1f, numeric = true, tier = true),
                    ColumnSpec("Scheme", 1.1f, numeric = true, tier = true),
                    ColumnSpec("Fit", 0.6f, numeric = true),
                ),
                rows = shown.map { p ->
                    val scheme = if (p.position.isOffense) offense else defense
                    val lens = lensFor(dynasty, p)
                    RowData(
                        listOf(
                            p.position.label,
                            p.name,
                            "${p.age(dynasty.year)}",
                            lens.view(overall(p)).text,
                            lens.view(overall(p, scheme)).text,
                            SchemeFitGrade.letter(schemeFit(p, scheme)),
                        ),
                        onClick = { onPlayer(p.id.v) },
                    )
                },
                sort = sort,
                onSort = { column ->
                    sort = if (sort.column == column) sort.copy(descending = !sort.descending)
                    else SortState(column)
                },
            )
        }
        item {
            Text(
                "Tap a player for his card. A range is a rating the club has " +
                    "not seen enough of to be sure about.",
                style = NdTheme.type.caption, color = c.chalkDim,
            )
        }
    }
}

private fun rosterOrder(dynasty: Dynasty, sort: SortState): Comparator<Player> {
    val scheme = { p: Player ->
        val team = dynasty.team
        SchemeCatalog.tuned(
            if (p.position.isOffense) team.offenseScheme else team.defenseScheme,
            dynasty.league.tuning,
        )
    }
    // A club orders its roster by what it believes, not by the truth.
    val by: Comparator<Player> = when (sort.column) {
        0, 1 -> compareBy({ ROSTER_ORDER.indexOf(it.position) }, { it.lastName })
        2 -> compareBy { it.age(dynasty.year) }
        4 -> compareBy { lensFor(dynasty, it).view(overall(it, scheme(it))).point }
        5 -> compareBy { schemeFit(it, scheme(it)) }
        else -> compareBy { lensFor(dynasty, it).view(overall(it)).point }
    }
    return if (sort.descending && sort.column !in setOf(0, 1)) by.reversed() else by
}

// ---------------------------------------------------------------------------

@Composable
fun ScheduleScreen(dynasty: Dynasty, onGame: (ArchivedGame) -> Unit = {}) {
    val us = dynasty.userTeam
    val archive = dynasty.league.history.games.filter { it.involves(us) }
    val years = (archive.map { it.year } + dynasty.year).distinct().sortedDescending()
    var year by remember(dynasty.year) { mutableStateOf(dynasty.year) }

    ScreenList {
        if (years.size > 1) {
            item { FilterChipRow(years.map { "$it" }, "$year", { year = it.toInt() }) }
        }
        item {
            val played = archive.filter { it.year == year }.associateBy { it.week }
            val current = year == dynasty.year
            SituationBlock(
                "$year schedule",
                meta = if (current) "Week ${dynasty.week}" else "Tap a game for its box score",
            ) {
                val games = if (current) dynasty.schedule.forTeam(dynasty.userTeamId) else emptyList()
                val bye = if (current) dynasty.schedule.byeWeek(dynasty.userTeamId) else null
                val weeks = ((if (current) (1..Schedule.WEEKS).toList() else emptyList()) + played.keys)
                    .distinct().sorted()
                DataTable(
                    columns = listOf(
                        ColumnSpec("Wk", 0.7f),
                        ColumnSpec("", 0.5f),
                        ColumnSpec("Opponent", 2.5f),
                        ColumnSpec("Result", 1.2f),
                    ),
                    rows = weeks.map { week ->
                        val archived = played[week]
                        val game = games.firstOrNull { it.week == week }
                        when {
                            archived != null -> {
                                val home = archived.home == us
                                val opponent = dynasty.league.team(TeamId(if (home) archived.away else archived.home))
                                val mine = if (home) archived.homeScore else archived.awayScore
                                val theirs = if (home) archived.awayScore else archived.homeScore
                                RowData(
                                    listOf(
                                        weekLabel(week), if (home) "vs" else "at", opponent.name,
                                        "${if (mine > theirs) "W" else if (mine < theirs) "L" else "T"} $mine–$theirs",
                                    ),
                                    onClick = { onGame(archived) },
                                )
                            }
                            game != null -> RowData(
                                listOf(
                                    weekLabel(week),
                                    if (game.home == dynasty.userTeamId) "vs" else "at",
                                    dynasty.league.team(game.opponentOf(dynasty.userTeamId)!!).name,
                                    // Played before box scores were kept: the score, if the season has it.
                                    dynasty.results.firstOrNull { it.week == week && it.involves(dynasty.userTeamId) }
                                        ?.let { r ->
                                            val m = r.scoreFor(dynasty.userTeamId)
                                            val t = r.scoreAgainst(dynasty.userTeamId)
                                            "${if (m > t) "W" else if (m < t) "L" else "T"} $m–$t"
                                        } ?: "",
                                ),
                                highlight = current && week == dynasty.week,
                            )
                            else -> RowData(listOf(weekLabel(week), "", if (week == bye) "Bye" else "", ""))
                        }
                    },
                )
            }
        }
    }
}

/** Regular-season weeks by number; the playoff rounds after them by name. */
private fun weekLabel(week: Int): String = when (week - Schedule.WEEKS) {
    in Int.MIN_VALUE..0 -> "$week"
    1 -> "WC"
    2 -> "Div"
    3 -> "Conf"
    else -> "Final"
}

// ---------------------------------------------------------------------------

/**
 * A game's box score: the one just played, with its play log, or any game
 * from the archive (SPEC 9.2) - full for five seasons, team totals after.
 */
@Composable
fun BoxScoreScreen(dynasty: Dynasty, archived: ArchivedGame? = null) {
    val c = NdTheme.colors
    val last = dynasty.lastGame
    val game = archived ?: dynasty.league.history.games.lastOrNull { g ->
        last != null && g.year == dynasty.year && g.home == last.home.v && g.away == last.away.v &&
            g.homeScore == last.homeScore && g.awayScore == last.awayScore
    }
    if (game == null && last == null) {
        Column(Modifier.fillMaxSize().padding(NdTheme.spacing.xl)) {
            Text("No games played yet.", style = NdTheme.type.title, color = c.chalk)
            Text(
                "Play a week from the hub and the box score lands here.",
                style = NdTheme.type.body, color = c.chalkDim,
            )
        }
        return
    }

    val homeId = game?.home ?: last!!.home.v
    val awayId = game?.away ?: last!!.away.v
    val home = dynasty.league.team(TeamId(homeId))
    val away = dynasty.league.team(TeamId(awayId))
    val box = game?.box ?: last!!.boxScore
    val h = box.home
    val a = box.away
    // The play log is kept for the most recent game only (SPEC 9.2).
    val plays = if (archived == null) last?.playByPlay.orEmpty() else emptyList()
    val retired = dynasty.league.history.retired.associate { it.player to it.name }
    fun name(id: Int) = dynasty.league.playersById[PlayerId(id)]?.name ?: retired[id] ?: "#$id"

    ScreenList {
        item {
            Scoreboard(
                away = TeamScore(away.abbrev, away.name, game?.awayScore ?: last!!.awayScore),
                home = TeamScore(home.abbrev, home.name, game?.homeScore ?: last!!.homeScore),
                quarter = 4,
                clock = "00:00",
                // Short enough for the scoreboard's middle column.
                status = game?.let { "${it.year} ${weekLabel(it.week).let { w -> if (it.week > Schedule.WEEKS) w else "wk $w" }}" }
                    ?: "Final",
            )
        }
        item {
            SituationBlock("Team stats", meta = "Final") {
                DataTable(
                    columns = listOf(
                        ColumnSpec("", 2.0f),
                        ColumnSpec(away.abbrev, 1.0f, numeric = true),
                        ColumnSpec(home.abbrev, 1.0f, numeric = true),
                    ),
                    rows = listOf(
                        "First downs" to (a.firstDowns.toString() to h.firstDowns.toString()),
                        "Total yards" to (a.totalYards.toString() to h.totalYards.toString()),
                        "Rushing" to ("${a.rushAttempts}–${a.rushYards}" to "${h.rushAttempts}–${h.rushYards}"),
                        "Passing" to (a.passYards.toString() to h.passYards.toString()),
                        "Completions" to ("${a.completions}–${a.passAttempts}" to "${h.completions}–${h.passAttempts}"),
                        "Sacked" to (a.sacksAllowed.toString() to h.sacksAllowed.toString()),
                        "Third down" to ("${a.thirdDownConversions}–${a.thirdDownAttempts}"
                            to "${h.thirdDownConversions}–${h.thirdDownAttempts}"),
                        "Turnovers" to (a.turnovers.toString() to h.turnovers.toString()),
                        "Penalties" to ("${a.penalties}–${a.penaltyYards}" to "${h.penalties}–${h.penaltyYards}"),
                        "Possession" to (a.possessionText to h.possessionText),
                    ).map { (label, values) ->
                        RowData(listOf(label, values.first, values.second))
                    },
                )
            }
        }

        // Both sides' leaders. The archive knows who played for whom; the
        // last game, before it was archived, reads today's rosters.
        val sides = listOf(away, home)
        if (box.players.isEmpty()) {
            item {
                Text(
                    "Player lines are kept for ${LeagueHistory.BOX_SCORE_SEASONS} seasons; " +
                        "this game keeps its team totals.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        } else sides.forEach { side ->
            val lines = game?.linesFor(side.id.v)
                ?: box.players.filterKeys { dynasty.league.playersById[PlayerId(it)]?.teamId == side.id }
            item {
                SituationBlock("${side.nickname} leaders") {
                    DataTable(
                        columns = listOf(
                            ColumnSpec("", 0.8f),
                            ColumnSpec("Player", 2.2f),
                            ColumnSpec("", 1.0f, numeric = true),
                            ColumnSpec("Yds", 0.9f, numeric = true),
                            ColumnSpec("TD", 0.6f, numeric = true),
                        ),
                        rows = leaderRows(lines, ::name),
                    )
                }
            }
        }

        if (plays.isNotEmpty()) {
            item {
                SituationBlock("Play log", meta = "Newest first") {
                    plays.indices.reversed().take(25).forEach { i ->
                        val play = plays[i]
                        PlayLogEntry(
                            downDistance = downAndDistance(play),
                            text = play.text,
                            event = eventOf(plays, i),
                        )
                    }
                    if (plays.size > 25) {
                        Text(
                            "${plays.size - 25} earlier plays.",
                            style = NdTheme.type.caption, color = c.chalkDim,
                            modifier = Modifier.padding(top = NdTheme.spacing.s),
                        )
                    }
                }
            }
        }
    }
}

private fun leaderRows(lines: Map<Int, StatLine>, name: (Int) -> String): List<RowData> = buildList {
    lines.entries.filter { it.value.passAttempts > 0 }.maxByOrNull { it.value.passYards }?.let { (id, s) ->
        add(RowData(listOf("Pass", name(id), "${s.completions}/${s.passAttempts}", "${s.passYards}", "${s.passTouchdowns}")))
    }
    lines.entries.filter { it.value.carries > 0 }.sortedByDescending { it.value.rushYards }.take(2).forEach { (id, s) ->
        add(RowData(listOf("Run", name(id), "${s.carries} car", "${s.rushYards}", "${s.rushTouchdowns}")))
    }
    lines.entries.filter { it.value.receptions > 0 }.sortedByDescending { it.value.receivingYards }.take(3).forEach { (id, s) ->
        add(RowData(listOf("Catch", name(id), "${s.receptions} rec", "${s.receivingYards}", "${s.receivingTouchdowns}")))
    }
    // Combined tackles, as a leaderboard counts them: his own stops and his assists.
    lines.entries.filter { it.value.combinedTackles > 0 }
        .sortedByDescending { it.value.combinedTackles }.take(2).forEach { (id, s) ->
        add(RowData(listOf(
            "Tkl", name(id), "${s.combinedTackles} tkl",
            if (s.assists > 0) "${s.assists} ast" else "",
            if (s.sacks > 0) "${s.sacks} sk" else "",
        )))
    }
}

internal fun downAndDistance(play: PlayLog): String {
    val down = when (play.down) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "4th"
    }
    return if (play.yardLine >= 90 && play.distance >= 100 - play.yardLine) "$down & goal"
    else "$down & ${play.distance}"
}

/**
 * The log carries no event flag, so the score line tells us about points and
 * the text about the ball changing hands. Nothing is guessed from a colour
 * alone - every entry still reads for itself.
 */
internal fun eventOf(plays: List<PlayLog>, i: Int): PlayEvent? {
    val play = plays[i]
    val nextPlay = plays.getOrNull(i + 1)
    if (nextPlay != null &&
        (nextPlay.homeScore > play.homeScore || nextPlay.awayScore > play.awayScore)
    ) return PlayEvent.SCORE
    val text = play.text.lowercase()
    if ("intercept" in text || "fumble" in text) return PlayEvent.TURNOVER
    if ("first down" in text) return PlayEvent.FIRST_DOWN
    return null
}

// ---------------------------------------------------------------------------

/** Coachability as far as the staff has seen it (SPEC 4.6). */
internal fun coachabilityGrade(player: Player, scoutingDept: Int): Pair<String, Boolean> {
    val seen = TraitScouting.confidence(player.clubYears, scoutingDept)
    return TraitScouting.grade(player.traits.coachability, seen, player.id.v, "coachability") to (seen >= 0.7f)
}

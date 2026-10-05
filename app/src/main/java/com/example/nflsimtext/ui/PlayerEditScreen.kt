package com.example.nflsimtext.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.nflsimtext.ui.components.Chip
import com.example.nflsimtext.ui.components.ColumnSpec
import com.example.nflsimtext.ui.components.DataTable
import com.example.nflsimtext.ui.components.FilterChipRow
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.RowData
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme
import com.nflsim.engine.model.DevCurve
import com.nflsim.engine.model.HiddenTraits
import com.nflsim.engine.model.PlayerId
import com.nflsim.engine.model.PlayerStatus
import com.nflsim.engine.model.Position
import com.nflsim.engine.model.PositionGroup
import com.nflsim.engine.model.RatingId
import com.nflsim.engine.model.Ratings
import com.nflsim.engine.ratings.overall
import com.nflsim.engine.season.Dynasty
import com.nflsim.engine.season.PlayerEdits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Every player in the league, to pick one to edit (SPEC 10.5): any club's,
 * the practice squads' and the free agents'. No rating shows here - true
 * ratings are the editor's alone (ADR: the editor reads true ratings).
 */
@Composable
fun PlayerFinderScreen(dynasty: Dynasty, onEdit: (Int) -> Unit, onBack: () -> Unit) {
    val c = NdTheme.colors
    var club by remember { mutableStateOf(ANY) }
    var group by remember { mutableStateOf(ANY) }
    var search by remember { mutableStateOf("") }
    val abbrev = dynasty.league.teams.associate { it.id.v to it.abbrev }
    val clubs = listOf(ANY, FREE) + dynasty.league.teams.map { it.abbrev }.sorted()
    val found = finder(dynasty, club, group, search)

    ScreenList {
        item {
            Column {
                Text("Edit players", style = NdTheme.type.display, color = c.chalk)
                Text(
                    "Anyone in the league: a club's roster, the practice squads, the free agents. " +
                        "Draft prospects can be edited once they are drafted.",
                    style = NdTheme.type.body, color = c.chalkDim,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                SearchBox(search, { search = it })
                FilterChipRow(clubs, club, { club = it })
                FilterChipRow(listOf(ANY) + PositionGroup.entries.map { it.name }, group, { group = it })
            }
        }
        item {
            if (found.isEmpty()) {
                Text("Nobody matches.", style = NdTheme.type.body, color = c.chalkDim)
            } else {
                Column {
                    DataTable(
                        columns = listOf(
                            ColumnSpec("Pos", 0.7f),
                            ColumnSpec("Player", 2.6f),
                            ColumnSpec("Club", 1.0f),
                            ColumnSpec("Age", 0.6f, numeric = true),
                        ),
                        rows = found.take(FINDER_ROWS).map { p ->
                            RowData(
                                listOf(p.position.label, p.name, p.teamId?.let { abbrev[it.v] } ?: "FA", "${p.age(dynasty.year)}"),
                                onClick = { onEdit(p.id.v) },
                            )
                        },
                    )
                    if (found.size > FINDER_ROWS) Text(
                        "${found.size - FINDER_ROWS} more. Search a name, or pick a club or position.",
                        style = NdTheme.type.caption, color = c.chalkDim,
                        modifier = Modifier.padding(top = NdTheme.spacing.s),
                    )
                }
            }
        }
        item { SecondaryButton("Back", onBack, Modifier.fillMaxWidth()) }
    }
}

/** The men the finder lists: on a club, a practice squad or the street, never retired; sorted by club, position, name. */
internal fun finder(dynasty: Dynasty, club: String, group: String, search: String): List<com.nflsim.engine.model.Player> {
    val abbrev = dynasty.league.teams.associate { it.id.v to it.abbrev }
    val words = search.trim().lowercase()
    return dynasty.league.players
        .filter { it.status != PlayerStatus.RETIRED }
        .filter { p ->
            when (club) {
                ANY -> true
                FREE -> p.teamId == null
                else -> p.teamId?.let { abbrev[it.v] } == club
            }
        }
        .filter { group == ANY || it.position.group.name == group }
        .filter { words.isEmpty() || words in it.name.lowercase() }
        .sortedWith(compareBy({ it.teamId?.let { t -> abbrev[t.v] } ?: "~" }, { it.position.ordinal }, { it.lastName }))
}

/**
 * One player, with his true ratings and traits to rewrite (SPEC 10.5). The
 * groups his position leans on open first. Nothing changes until Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerEditScreen(dynasty: Dynasty, playerId: Int?, store: DynastyStore, scope: CoroutineScope, onDone: () -> Unit) {
    val c = NdTheme.colors
    val player = playerId?.let { dynasty.league.playersById[PlayerId(it)] }
    if (player == null || !dynasty.editPlayers) {
        Column(Modifier.padding(NdTheme.spacing.xl)) {
            Text(if (player == null) "That player is gone." else "Player editing is off. Turn it on in Settings.",
                style = NdTheme.type.body, color = c.chalkDim)
            SecondaryButton("Back", onDone, Modifier.padding(top = NdTheme.spacing.m))
        }
        return
    }
    val start = remember(player.id) { PlayerEdits.current(dynasty, player.id.v) }
    var edit by remember(player.id) { mutableStateOf(start) }
    var open by remember(player.id) { mutableStateOf(groupsFor(player.position)) }
    // Overall as the edited man would have it: derived, never stored.
    val previewOverall = overall(player.copy(position = edit.position,
        ratings = player.ratings.with(*edit.ratings.map { it.key to it.value }.toTypedArray())))
    val abbrev = player.teamId?.let { t -> dynasty.league.teams.firstOrNull { it.id == t }?.abbrev } ?: "Free agent"

    ScreenList {
        item {
            Column {
                Text(player.name, style = NdTheme.type.display, color = c.chalk)
                Text("$abbrev, ${edit.position.label}, age ${player.age(dynasty.year)}. True overall $previewOverall " +
                    "(was ${overall(player)}).", style = NdTheme.type.body, color = c.chalkDim)
                Text("These are his true ratings. Everywhere else, your scouts still see what they see.",
                    style = NdTheme.type.caption, color = c.chalkDim, modifier = Modifier.padding(top = NdTheme.spacing.xs))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                PrimaryButton("Save changes", {
                    scope.launch { store.editPlayer(player.id.v, edit); onDone() }
                }, Modifier.weight(1f), enabled = edit != start && !store.busy)
                SecondaryButton("Discard", onDone, Modifier.weight(1f))
            }
        }
        item {
            SituationBlock("Position", meta = edit.position.label) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                    verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                    Position.entries.forEach { pos ->
                        Chip(pos.label, pos == edit.position, role = Role.RadioButton) { edit = edit.copy(position = pos) }
                    }
                }
                if (edit.position.group != player.position.group) Text(
                    "A new position group gives him that group's first style of play, and takes him off his club's " +
                        "depth chart pins at other positions.",
                    style = NdTheme.type.caption, color = c.chalkDim, modifier = Modifier.padding(top = NdTheme.spacing.s),
                )
            }
        }
        RATING_GROUPS.forEach { (name, ids) ->
            item(key = "group-$name") {
                SituationBlock(name, meta = if (name in open) "Hide" else "Show",
                    onClick = { open = if (name in open) open - name else open + name }) {
                    if (name in open) ids.forEach { id ->
                        Stepper(label(id), edit.ratings.getValue(id), PlayerEdits.RATING_RANGE) { v ->
                            edit = edit.copy(ratings = edit.ratings + (id to v))
                        }
                    }
                }
            }
        }
        item(key = "traits") {
            SituationBlock("Hidden traits", meta = if (TRAITS in open) "Hide" else "Show",
                onClick = { open = if (TRAITS in open) open - TRAITS else open + TRAITS }) {
                if (TRAITS in open) {
                    Text("Development", style = NdTheme.type.label, color = c.chalkDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(NdTheme.spacing.s),
                        modifier = Modifier.padding(vertical = NdTheme.spacing.xs)) {
                        DevCurve.entries.forEach { d ->
                            Chip(devLabel(d), d == edit.traits.developmentCurve, role = Role.RadioButton) {
                                edit = edit.copy(traits = edit.traits.copy(developmentCurve = d))
                            }
                        }
                    }
                    Stepper("Peak age (years from normal)", edit.traits.peakAgeOffset, PlayerEdits.PEAK_AGE_RANGE, small = true) { v ->
                        edit = edit.copy(traits = edit.traits.copy(peakAgeOffset = v))
                    }
                    TRAIT_FIELDS.forEach { (name, get, set) ->
                        Stepper(name, get(edit.traits), PlayerEdits.TRAIT_RANGE) { v -> edit = edit.copy(traits = set(edit.traits, v)) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
                PrimaryButton("Save changes", {
                    scope.launch { store.editPlayer(player.id.v, edit); onDone() }
                }, Modifier.weight(1f), enabled = edit != start && !store.busy)
                SecondaryButton("Discard", onDone, Modifier.weight(1f))
            }
        }
    }
}

/**
 * A value with steps either side: by one, and by five where the range is
 * wide. The name and the value on one line, the steps across the width
 * under them - beside four buttons, "Acceleration" had to wrap.
 */
@Composable
private fun Stepper(label: String, value: Int, range: IntRange, small: Boolean = false, onChange: (Int) -> Unit) {
    val c = NdTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs).semantics(mergeDescendants = false) { contentDescription = "$label $value" }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = NdTheme.type.body, color = c.chalk, modifier = Modifier.weight(1f))
            Text("$value", style = NdTheme.type.data, color = c.chalk, textAlign = TextAlign.End)
        }
        Row(Modifier.fillMaxWidth().padding(top = NdTheme.spacing.xs), horizontalArrangement = Arrangement.spacedBy(NdTheme.spacing.s)) {
            if (!small) StepButton("−5", "$label down five", Modifier.weight(1f)) { onChange((value - 5).coerceIn(range)) }
            StepButton("−1", "$label down one", Modifier.weight(1f)) { onChange((value - 1).coerceIn(range)) }
            StepButton("+1", "$label up one", Modifier.weight(1f)) { onChange((value + 1).coerceIn(range)) }
            if (!small) StepButton("+5", "$label up five", Modifier.weight(1f)) { onChange((value + 5).coerceIn(range)) }
        }
    }
}

@Composable
private fun StepButton(text: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.semantics { contentDescription = description }) {
        Chip(text, selected = false, modifier = Modifier.fillMaxWidth().defaultMinSize(minWidth = NdTheme.spacing.minTouch),
            role = Role.Button, onClick = onClick)
    }
}

/** The search box: a name, or part of one. */
@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    val c = NdTheme.colors
    Box(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = NdTheme.spacing.minTouch)
            .border(BorderStroke(1.dp, c.turfLine), NdTheme.shapes.tag)
            .padding(horizontal = NdTheme.spacing.m),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text("Search by name", style = NdTheme.type.body, color = c.chalkDim)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = NdTheme.type.body.copy(color = c.chalk),
            cursorBrush = SolidColor(c.chalk),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search players by name" },
        )
    }
}

/** The rating groups a position leans on, opened first. */
internal fun groupsFor(position: Position): Set<String> = when (position.group) {
    PositionGroup.QB -> setOf(PHYSICAL, MENTAL, QB)
    PositionGroup.RB -> setOf(PHYSICAL, CARRIER, RECEIVING)
    PositionGroup.WR -> setOf(PHYSICAL, RECEIVING, CARRIER)
    PositionGroup.TE -> setOf(PHYSICAL, RECEIVING, BLOCKING)
    PositionGroup.OL -> setOf(PHYSICAL, BLOCKING)
    PositionGroup.EDGE, PositionGroup.DT, PositionGroup.LB, PositionGroup.CB, PositionGroup.S -> setOf(PHYSICAL, DEFENSE)
    PositionGroup.ST -> setOf(KICKING)
}

private const val PHYSICAL = "Physical"
private const val MENTAL = "Mental"
private const val QB = "Quarterback"
private const val CARRIER = "Ball carrier"
private const val RECEIVING = "Receiving"
private const val BLOCKING = "Blocking"
private const val DEFENSE = "Defense"
private const val KICKING = "Kicking"
private const val TRAITS = "Hidden traits"

/** Every rating, once, in the order RatingId lists them. */
internal val RATING_GROUPS: List<Pair<String, List<RatingId>>> = listOf(
    PHYSICAL to listOf(RatingId.SPEED, RatingId.ACCELERATION, RatingId.AGILITY, RatingId.STRENGTH, RatingId.JUMPING,
        RatingId.STAMINA, RatingId.INJURY_RESIST, RatingId.TOUGHNESS),
    MENTAL to listOf(RatingId.AWARENESS, RatingId.PLAY_RECOGNITION, RatingId.DISCIPLINE),
    QB to listOf(RatingId.THROW_POWER, RatingId.THROW_ACC_SHORT, RatingId.THROW_ACC_MID, RatingId.THROW_ACC_DEEP,
        RatingId.THROW_ON_RUN, RatingId.THROW_UNDER_PRESSURE, RatingId.BREAK_SACK, RatingId.PLAY_ACTION, RatingId.SCRAMBLING),
    CARRIER to listOf(RatingId.CARRYING, RatingId.BALL_SECURITY, RatingId.BREAK_TACKLE, RatingId.TRUCKING, RatingId.ELUSIVENESS,
        RatingId.JUKE_MOVE, RatingId.SPIN_MOVE, RatingId.STIFF_ARM, RatingId.VISION),
    RECEIVING to listOf(RatingId.CATCHING, RatingId.CATCH_IN_TRAFFIC, RatingId.SPECTACULAR_CATCH,
        RatingId.ROUTE_SHORT, RatingId.ROUTE_MID, RatingId.ROUTE_DEEP, RatingId.RELEASE),
    BLOCKING to listOf(RatingId.RUN_BLOCK, RatingId.PASS_BLOCK, RatingId.IMPACT_BLOCK, RatingId.LEAD_BLOCK,
        RatingId.RUN_BLOCK_POWER, RatingId.RUN_BLOCK_FINESSE, RatingId.PASS_BLOCK_POWER, RatingId.PASS_BLOCK_FINESSE),
    DEFENSE to listOf(RatingId.POWER_MOVES, RatingId.FINESSE_MOVES, RatingId.BLOCK_SHEDDING, RatingId.PURSUIT, RatingId.TACKLE,
        RatingId.HIT_POWER, RatingId.MAN_COVERAGE, RatingId.ZONE_COVERAGE, RatingId.PRESS),
    KICKING to listOf(RatingId.KICK_POWER, RatingId.KICK_ACCURACY, RatingId.PUNT_POWER, RatingId.PUNT_ACCURACY),
)

/** "THROW_ACC_SHORT" read as "Throw acc short". */
private fun label(id: RatingId): String = id.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun devLabel(d: DevCurve): String = d.name.lowercase().replace('_', '-').replaceFirstChar { it.uppercase() }

/** The 0-100 traits: a name, how to read it, how to write it. */
private val TRAIT_FIELDS: List<Triple<String, (HiddenTraits) -> Int, (HiddenTraits, Int) -> HiddenTraits>> = listOf(
    Triple("Work ethic", { it.workEthic }, { t, v -> t.copy(workEthic = v) }),
    Triple("Football IQ", { it.footballIq }, { t, v -> t.copy(footballIq = v) }),
    Triple("Coachability", { it.coachability }, { t, v -> t.copy(coachability = v) }),
    Triple("Scheme versatility", { it.schemeVersatility }, { t, v -> t.copy(schemeVersatility = v) }),
    Triple("Injury proneness", { it.injuryProneness }, { t, v -> t.copy(injuryProneness = v) }),
    Triple("Clutch", { it.clutch }, { t, v -> t.copy(clutch = v) }),
    Triple("Big game", { it.bigGame }, { t, v -> t.copy(bigGame = v) }),
    Triple("Consistency", { it.consistency }, { t, v -> t.copy(consistency = v) }),
    Triple("Ego", { it.ego }, { t, v -> t.copy(ego = v) }),
    Triple("Loyalty", { it.loyalty }, { t, v -> t.copy(loyalty = v) }),
    Triple("Penalty prone", { it.penaltyProne }, { t, v -> t.copy(penaltyProne = v) }),
    Triple("Durability under load", { it.durabilityUnderLoad }, { t, v -> t.copy(durabilityUnderLoad = v) }),
)

private const val ANY = "ALL"
private const val FREE = "Free agents"
private const val FINDER_ROWS = 60

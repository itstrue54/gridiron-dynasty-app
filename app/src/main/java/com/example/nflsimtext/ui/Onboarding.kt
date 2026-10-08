package com.example.nflsimtext.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.example.nflsimtext.ui.components.PrimaryButton
import com.example.nflsimtext.ui.components.SecondaryButton
import com.example.nflsimtext.ui.components.SituationBlock
import com.example.nflsimtext.ui.theme.NdTheme

/** Whether the player has dismissed the welcome card. An app preference, not part of a save. */
object WelcomeStore {
    private const val PREFS = "call_sheet"
    private const val KEY = "welcome_seen"

    fun seen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}

/**
 * The first hub a new player sees: how a week works, and where the words are
 * explained. Every tester in the fresh-eyes review landed on the hub with no
 * idea what to press first. Shown until dismissed, once per install.
 */
@Composable
fun WelcomeCard(onGlossary: () -> Unit) {
    val context = LocalContext.current
    var seen by remember { mutableStateOf(WelcomeStore.seen(context)) }
    if (seen) return
    val c = NdTheme.colors
    SituationBlock("Welcome, GM") {
        Column {
            listOf(
                "Each week, tap Play week. Your coordinators call the game from your game plan, " +
                    "and the result comes back as a recap and a box score.",
                "Want to call it yourself? Call the plays yourself stops at each of your snaps.",
                "Needs attention lists what is yours to fix: an open roster spot, a player " +
                    "who wants a new deal, an injury.",
                "Trades are open until week 10. After the season come retirements, the draft and free agency.",
            ).forEach { Text("· $it", style = NdTheme.type.body, color = c.chalk, modifier = Modifier.padding(bottom = NdTheme.spacing.xs)) }
            Text(
                "New to a term? The glossary explains positions, ratings and the salary cap.",
                style = NdTheme.type.caption, color = c.chalkDim,
            )
            Row(Modifier.padding(top = NdTheme.spacing.s)) {
                SecondaryButton("Glossary", onGlossary, Modifier.weight(1f).padding(end = NdTheme.spacing.s))
                PrimaryButton("Got it", { WelcomeStore.markSeen(context); seen = true }, Modifier.weight(1f))
            }
        }
    }
}

/** A term and what it means here. */
private data class Term(val name: String, val meaning: String)

private val GLOSSARY: List<Pair<String, List<Term>>> = listOf(
    "Positions" to listOf(
        Term("QB", "Quarterback: takes the snap, throws the passes, and runs the offense."),
        Term("RB, FB", "Running back carries the ball; fullback mostly blocks for him."),
        Term("WR", "Wide receiver: catches passes, lined up wide."),
        Term("TE", "Tight end: blocks next to the line and catches passes."),
        Term("LT, LG, C, RG, RT", "The offensive line: left tackle, left guard, center, right guard, right tackle. They block."),
        Term("EDGE, DT", "The defensive line: edge rushers on the outside, defensive tackles inside. They chase the quarterback and stop runs."),
        Term("LB", "Linebacker: plays behind the line, stops runs and covers short passes."),
        Term("CB, S", "Cornerbacks cover receivers; safeties play deepest, the last line of defense."),
        Term("K, P, LS", "Kicker, punter and long snapper: the kicking game."),
    ),
    "Ratings" to listOf(
        Term("Ovr", "Overall: how good a player is at his position."),
        Term("Scheme", "How good he is in your offense or defense. A player can suit one system and not another."),
        Term("Fit", "How well he suits your scheme, graded A (ideal) to F."),
        Term("A range, like 79-92", "Your scouts are not sure yet. The range narrows each season he spends with your club."),
        Term("Colors", "90 and up elite, 80s good, 70s a starter, below 70 a backup."),
    ),
    "Roster and contracts" to listOf(
        Term("The 53", "The 53-man roster: who can play on Sunday. Injured reserve does not count against it."),
        Term("Practice squad", "Sixteen extra players who practice but do not play, unless promoted."),
        Term("Injured reserve", "Where a hurt player goes: he frees a roster spot until he is healthy."),
        Term("Inactive", "Of the 53, a club dresses 48 on game day (47 without eight offensive linemen). The rest are inactive: hurt men first, then the deepest. Depth chart, Game day."),
        Term("Special teams", "The kicking and return units. Each takes its men from the 48 by what the job needs, mostly backups: coverage runs and tackles, return blockers block in space. Depth chart, Special teams."),
        Term("Gunner", "One of the two men split wide on a punt who race down to the returner. Good gunners force fair catches."),
        Term("Jammer", "A man on the punt return team who blocks a gunner off the line to give the returner room."),
        Term("Call-up", "A practice-squad player brought up for one game, then back to the squad: two a game, each at most three times a season. Depth chart, Game day."),
        Term("Salary cap", "The most a club can spend on players in a season. Cap space is what is left."),
        Term("Dead money", "Money still owed to a player you cut or traded. It counts against your cap."),
        Term("Restructure", "Turning salary into bonus: cheaper this year, dearer every year after."),
        Term("Franchise tag", "A one-year deal at a set price that keeps a player from free agency."),
        Term("Free agency", "Players without a contract sign with whoever offers the best deal."),
        Term("Demands", "A player who thinks he is underpaid asks for a new deal. Pay him, offer less, or say no."),
        Term("Holdout", "A player who wants paying stays away from camp, and comes back out of form."),
        Term("Trade block", "Players you have said you would trade. Clubs that could use them call."),
        Term("Hot seat", "A head coach under pressure after losing seasons. Past his club's limit, other clubs fire theirs after the season; yours stays until you let him go."),
        Term("Hiring window", "After the season, before you start the offseason: the time to hire and fire your staff. Staff screen."),
        Term("Promotion", "No club can stop its coordinator becoming a head coach elsewhere. You can hire other clubs' coordinators that way, and they can hire yours."),
    ),
    "The game" to listOf(
        Term("Downs", "An offense has four tries (downs) to gain 10 yards. Make it and the count starts again."),
        Term("Third down", "The last try before a team usually punts or kicks."),
        Term("Fourth down", "Go for it, punt the ball away, or kick a field goal."),
        Term("Red zone", "Inside the opponent's 20-yard line."),
        Term("Sack", "The quarterback tackled behind the line before he can throw."),
        Term("Play action", "A fake handoff before a pass, to pull the defense in."),
        Term("Blitz", "Extra defenders rushing the quarterback."),
        Term("Man and zone", "In man coverage each defender follows a receiver; in zone each covers an area."),
        Term("Turnover", "Losing the ball to the defense: an interception or a lost fumble."),
    ),
)

/** Every term a new player is likely to meet, in plain words. */
@Composable
fun GlossaryScreen(onBack: () -> Unit) {
    val c = NdTheme.colors
    ScreenList {
        item {
            Column {
                Text("Glossary", style = NdTheme.type.display, color = c.chalk)
                Text("What the words in the game mean.", style = NdTheme.type.body, color = c.chalkDim)
            }
        }
        GLOSSARY.forEach { (section, terms) ->
            item(key = section) {
                SituationBlock(section) {
                    terms.forEach { t ->
                        Column(Modifier.fillMaxWidth().padding(vertical = NdTheme.spacing.xs)) {
                            Text(t.name, style = NdTheme.type.data.copy(fontWeight = FontWeight.W600), color = c.chalk)
                            Text(t.meaning, style = NdTheme.type.body, color = c.chalkDim)
                        }
                    }
                }
            }
        }
        item { SecondaryButton("Back", onBack, Modifier.fillMaxWidth()) }
    }
}

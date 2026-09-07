# Architecture Decision Log

## ADR-001: Engine is a pure Kotlin/JVM module
Date: 2026-09-05
Status: Accepted

Context: Android-coupled simulation logic would be untestable at speed and
unusable by the planned college football sim.

Decision: :engine has zero Android dependencies. CI enforces this with a grep.

Consequences: Save/load and file access live in :data. Slightly more
boilerplate crossing the module boundary. Calibration can run headless on the
JVM in seconds.

## ADR-002: One market value curve for the whole league
Date: 2026-09-06
Status: Accepted

Context: Free agency, roster generation and release decisions each need to know
what a player is worth. Three implementations of that idea would drift, and a
league where players are created at one price and signed at another does not
stay balanced for more than a season or two.

Decision: `engine/econ/MarketValue` owns the curve. `TeamNeeds.marketValue`
delegates to it; `RosterGenerator` prices generated contracts with it;
`CapManagement` ranks release candidates against it.

Consequences: Changing what a position is worth is a one-line change in one
file. The curve is convex on purpose - the gap between an 88 and an 80 costs
far more than the gap between an 80 and a 72, which is why a roster cannot be
all starters.

## ADR-003: Generated players are signed to staggered contracts
Date: 2026-09-06
Status: Accepted

Context: Generated players had no contracts. The offseason releases anyone
without an active deal, so after one season all 32 rosters emptied into free
agency and were redistributed - an accidental redraft every year, and nothing
for the salary cap to act on.

Decision: `RosterGenerator.sign()` puts every generated player on a deal, term
by age and depth, each player somewhere in the middle of it. Rosters open at
~88% of the cap. Prices are scaled against actual cap hits rather than annual
averages, because deals are back-loaded and the minimum salary is a floor.

Consequences: About a quarter of a roster reaches free agency each year.
Contract signing draws from a `split("contracts")` child stream so it does not
consume draws from the parent - the first version did, which regenerated a
different league and surfaced as a calibration regression three modules away.

## ADR-004: Roster decisions discount age; retirement does not carry the load
Date: 2026-09-06
Status: Accepted

Context: The league aged 25.6 to 26.8 over ten seasons and its mean overall
slid with it. Per-bracket development rates were identical in year one and year
ten, so progression was not the cause - the age mix was. Players over 31 held
20% of roster spots against roughly 10% in the real NFL.

Decision: `rosterValue()` discounts 2.2 rating points per year past 29, and
both the cut-to-53 pass and free agent signing rank on it.

Consequences: Rosters skew young because of front office decisions, which is
how it works in reality. A declining veteran loses his spot to a rookie, enters
free agency, and the existing 70-88% unsigned-retirement path ends his career.
Raising retirement rates directly would have treated the symptom - those
players were never getting cut in the first place.

## ADR-005: The draft board is far deeper than the draft
Date: 2026-09-06
Status: Accepted

Context: 350 prospects for 224 picks meant teams drafted 64% of the board, so
what arrived was barely better than what was generated. Rookies rated 70+
arrived at ~12 a year against a league losing roughly 28 elite players a year
to age. Players rated 80+ rose from 140 to 184 as the founding cohort peaked,
then collapsed to 124 once it aged out.

Decision: `SyntheticDraftClass.BOARD_DEPTH` widens the board to 1.45x - about
507 prospects for the same 224 picks.

Consequences: Selection, not rating inflation, is where the league's talent
comes from. Nothing about what a prospect is worth changed. Undrafted players
still flow into free agency. A deeper board also makes scouting error matter,
since there is more of a board to be wrong about.

## ADR-006: Player prices are relative to the league, not absolute
Date: 2026-09-07
Status: Accepted

Context: The price curve was calibrated against a league whose starters average
79, which is what the generator produces. Leagues settle around 73. The curve
is steeply convex, so a few points of league-wide compression collapsed the
cost of a roster from about 440m to 60m - teams spent 63m of a 255m cap, no
team came within 10m of it, and the cap forced no releases in ten seasons.

Decision: `MarketValue.score()` gives a player's standing in arbitrary units;
`MarketValue.pricer()` turns standing into money by dividing the money actually
chasing players (total league cap space x 0.85) by the total standing of the
free agents who will fill the league's open roster spots.

Consequences: A cap-rich offseason with a thin market is expensive, which is
how it works in reality, and the system stays honest as ratings drift instead
of needing recalibration every time the talent curve moves. Mean cap space fell
from 192m to 54m. One contract is capped at 24% of a team's cap, near where
the real quarterback market sits.

## ADR-007: Salaries are paid on production, not ratings
Date: 2026-09-07
Status: Accepted

Context: Prices were computed from ratings. Nobody in a front office can see a
rating (SPEC 4.6 says the same thing about the UI); they see what a player did.

Decision: `econ/Production` reduces a player's season to one number, normalised
against the median at his position group, and that multiplier scales his price.
Season stats only - career totals would pay a thirty-four year old for what he
did at twenty-six. Linemen and specialists have no counting stats and are
priced on ability, roughly as they are paid in reality.

Consequences: Two properties the market needs, for free. A player coming off a
big year gets paid for it even when the year was partly his offence, his
quarterback or luck - which is where genuinely bad contracts come from, and bad
contracts are what make the cap a game rather than an accounting exercise. And
a good player who sat behind a starter is cheap, because he has not proved
anything, so scouting properly is rewarded.

## ADR-008: Guarantees are consumed as they are paid
Date: 2026-09-07
Status: Accepted

Context: Ten simulated seasons produced zero cap casualties while 64 contracts
ran at more than 1.7x the player's value. The rule could not fire. Dead money
on release was unamortised bonus plus the current year's base, with `guaranteed`
treated as covering every year, so the saving from a release works out to
proration minus unamortised bonus - never positive. Releasing a player could
not save money in any year of any contract.

Decision: `Contract.guaranteedRemaining(year)` subtracts base salary already
paid from the guarantee, and `deadCap` charges only what is still owed.

Consequences: Year one of a deal is unescapable and the back of it is where a
team gets out, which is when real cap casualties happen. Cap casualties went
from 0 to 49 per offseason. This was a modelling bug wearing the costume of a
tuning problem - the third time this project has hit that, and the second time
the tell was a statistic that could not move rather than one that moved wrongly.

## ADR-009: General managers are not interchangeable
Date: 2026-09-07
Status: Accepted

Context: Every AI team bid the same fraction of its space, kept the same share
of its own players, and cut at the same threshold. A league of thirty-two
identically prudent front offices produces no bad contracts, and cap trouble is
not an accident of arithmetic - it is a decision somebody makes every March.

Decision: `GmProfile` (SPEC 8.2) carries aggression, winNowVsFuture,
loyaltyToOwnPlayers and riskTolerance, drawn once per team at league
generation. They drive the share of space committed in an offseason, the most
that goes on one player, how far past market a club goes to win a bidding war,
how readily it restructures, and how overpriced a contract gets before it cuts
the player.

Consequences: The spread is wide on purpose. Some clubs are reckless, and their
recklessness is what fills the league with the contracts the cap then punishes.

## ADR-010: Players ask for things
Date: 2026-09-07
Status: Accepted

Context: Players went wherever the money was and never had a view about it.

Decision: `PlayerIntent` scores three grievances - losing (weighted by age,
because winning matters more at thirty-three), not playing when good enough to
start elsewhere, and being underpaid against market - and the largest decides
what a player asks for. Loyalty decides whether he says it out loud. Free
agency appeal now includes the signing team's record, so a contender has pull
against money.

Consequences: A money grievance pushes a player toward the door at 42% the
force of the others. Treating them equally had 173 players a year demanding
trades, nearly all of them rookies on slotted deals and minimum-salary players
who had got good - underpaid by construction, which is what a rookie contract
is. Trades move contracts, not picks: the new club takes the deal as it stands
and the old club eats the bonus it already paid. Pick compensation waits for
the asset model in SPEC 8.4.

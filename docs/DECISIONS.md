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

## ADR-0xx — Offseason runs as a step machine in SPEC 7 order

`OffseasonEngine.run` was a single 696-line pipeline. It is now thirteen
step functions threading `OffseasonState`, with `OffseasonPhase` declared
in spec order. Extraction and reorder were separate commits so the
reorder's effect on the league is a readable diff.

Two orderings changed. Development moved from first to last (spec phase
11), so teams price, draft and sign against last season's ratings.
Free agency moved ahead of the draft (phases 7 then 9), the real NFL
sequence.

Measured over ten seasons, against `docs/baseline-dynasty.txt`:
- NET DEV terminal value 0.02 -> 0.32
- retirement mean +1.5 points
- mean cap space 78,472 -> 67,238

### Open, found while doing this
- **The cap does not bind.** 67m mean headroom, zero teams under 10m in
  any of ten seasons. SPEC 8.1 says the cap is the strategy game. It is
  not one yet. Pricing, not ordering — look at SPEND_SHARE and the
  marketPool cap in stepBuildPricer.
- **NET DEV still decays**, 0.61 -> 0.32 across ten years. Better than
  the 0.02 baseline, still sloping.
- **Coaching quality is fake.** `coaching = 55 + teamId % 25` in
  stepDevelopment derives development quality from team index, so team 0
  is permanently worse at developing players than team 24. There is a
  CoachId but no Coach model. Building the carousel will move progression
  league-wide and require a recalibration pass.

## ADR-0xx — The coach slope is 2.00

Staffs are real as of 7fc12d6, which resolves "coaching quality is fake"
above - and exposed that SPEC 7.1 barely lets coaching matter. Its
multiplier was `0.85 + 0.30 * (coaching/100) * (coachability/100)`. Over
five seeds, the top quarter of staffs out-developed the bottom quarter by
0.03 rating points a year on players 24 and under, correlation 0.09:
no difference at all. The section says coaching should matter most for
coachable young players and make the position-coach hiring screen
meaningful. At 0.30 it did neither.

The multiplier is now `0.2975 + 2.00 * ...`. The base is picked so
league-mean coaching (65) at mean coachability (50) still gives 0.9475:
the change widens the gap between staffs without moving how much the
league develops overall.

Measured over five seeds, ten seasons, against `docs/baseline-coaching.txt`:
- under-25 gap, top quarter of staffs vs bottom: 0.03 -> 0.15 a year
- correlation, staff rating vs under-25 development: 0.09 -> 0.41
- NET DEV, seed 2026: 0.61 -> 0.23 becomes 0.61 -> 0.31

Coach ratings then widened from N(65,12) clamped 35-95 to N(65,20)
clamped 20-99. A team's effective rating blends one head coach with ten
position coaches, so at sd 12 team ratings bunched into 53-74 and most
of the difference between staffs averaged away. At sd 20 they run about
46-78, and over the same five seeds:
- under-25 gap 0.15 -> 0.26 a year
- correlation 0.41 -> 0.53: staff explains about a quarter of
  young-player development
- league development unchanged: mean NET DEV 0.59 -> 0.28 at sd 12,
  0.58 -> 0.29 at sd 20

### Rejected
- **Wider coach ratings alone.** N(65,20) at the old slope: gap 0.02,
  correlation 0.00. Rating spread only shows once the slope lets it
  through.

### Open, found while doing this
- **Near the ceiling.** With the mean held, the slope cannot pass ~2.9
  before the base goes negative and bad coaching starts shrinking
  players. If the hiring screen needs more than 2.00 gives, the
  multiplicative shape is the problem, not the coefficient.
- **Coachability is now a big trait.** At league-average coaching, young
  players' growth multiplier spans ~0.56x to ~1.34x across coachability
  20-80, against 0.94x to 1.06x before. That is what 7.1 asks for, but it
  makes a hidden trait carry a lot of development - worth surfacing
  through scouting before it feels arbitrary.
- **The clamp is not symmetric.** 20-99 trims the top tail harder than
  the bottom, and truncating to Int shaves about half a point, so the
  generated mean sits nearer 64 than 65. Team-level means read 61-65
  across seeds, inside sampling noise, and league development did not
  move - but DEFAULT_COACHING and the 0.2975 base both assume 65.

## ADR-0xx — The cap's steady state is set by the pricer

"The cap does not bind" has been open since the step-machine ADR. The
CAP table in the dynasty health check shows it does bind in the first
offseason - about 12 of 32 teams under 10m - then comes loose as
generated contracts expire, settling near 11.5% of the cap unspent with
one or two teams under 10m from year five on. Year one is tight only
because the generator signs rosters to 88% of the cap.

Two bugs came out on the way and are fixed (8bc535c): 53-man cuts left
no dead money, and free agency still filled rosters to 51 after it
moved ahead of the draft. Neither was the drift.

Everything tried after that, years 5-10, five seeds:

    lever                               space   under 10m   side effect
    none                                11.7%      1.5
    SPEND_SHARE 0.85 -> 1.30             8.4%      3.0      FA signings 180 -> 90
    GM spend-share floor 0.62 -> 0.95   10.8%      2.8
    12 targets a day, up from 4         12.4%      3.8      paid vs market 1.28x -> 1.37x
    full rosters trade up (kept)        11.5%      2.1      FA 180 -> 232, 62 swaps a year

Recording why each team stopped bidding showed almost none stop for
money, need or price: 69% stop on a full roster and 30% when the market
closes. Removing either gate leaves space where it was. Only
SPEND_SHARE moves it.

Decision: park it. The likely reason is ADR-006 itself. The pricer sets
the market's payroll at 85% of the league's remaining cap space before
free agency opens, so whatever teams do - bid longer, trade up, commit
a bigger share - they are dividing a pot that is already fixed. More
demand comes out as lower prices, not more spending: trading up added
about fifty signings a year and the premium over market fell from 1.28x
to 1.22x. That explanation fits all six runs; it has not been proven.

Kept: full rosters trade up (margin 4, at most 3 swaps). It does not
bind the cap, but it makes cap room usable, which any future change to
the pricer will need.

### If the cap should bind harder
- The lever is what the pricer sizes against. SPEND_SHARE alone halves
  free agency, so the thing to test is anchoring market payroll to the
  cap rather than to remaining space - a change to ADR-006.

### Open, found while doing this
- **The 53-man cut ignores dead money.** It picks by roster value alone,
  so it will release a guaranteed second-round pick to keep a slightly
  better minimum veteran. About 110 players a year are still cut in the
  offseason they arrived.
- **Upgrade releases stand apart.** They are counted separately from cap
  casualties and do not reach the news screen's release list.
- **GM spend share barely shows.** It correlates with a team's space at
  only -0.1 to -0.3. ADR-009's spread of front offices shows up in the
  contracts they sign, not in how much of the cap they use.

## ADR-0xx — Loyalty is the player's; the club decides how far to go

ADR-009 said reckless front offices fill the league with bad contracts,
and nothing had measured it. The GM section of the dynasty health check
now compares the top and bottom eight clubs on each trait over every
offseason. Five seeds, before this change:

- **Aggression does it.** 1.41x market in free agency against 1.06x, 19%
  of big contracts over 1.5x value against 14%, 14% of the cap in dead
  money against 10%.
- **Win now shows nothing** on any measure - the same finding as the
  pricer ADR, from the other side.
- **Risk tolerance works, mildly.** Patient clubs cut 1.0 players a year,
  impatient ones 1.3.
- **Loyalty ran backwards.** The hometown discount was 0.93 + GM loyalty
  x 0.10, so the most loyal clubs paid 1.02x market to keep their own and
  the least loyal got 0.94x.

Decision: re-signing has two sides. The player names a price from his
own loyalty trait - 1.06x market for the least loyal down to 0.90x for
the most - and the club sets a limit from its GM's loyalty, 0.94x up to
1.08x. Under the limit he re-signs at his ask; over it he walks.
Team-friendly deals now come from loyal players, and a loyal club keeps
a mercenary by paying him.

Also fixed: free agency's loyalty bonus checked `player.teamId` against
the bidder, and every free agent's teamId is null, so player loyalty
never counted in free agency. It now reads last season's club.

Five seeds, after:

    GM loyalty, high / low    kept vs market   kept a year   over 1.5x
    before                    1.02x / 0.94x        -         15.6% / 16.3%
    after                     0.98x / 0.94x    2.8 / 1.3     14.6% / 18.9%

League-wide, 104 players kept an offseason becomes 87, free agents
signed 232 becomes 237, and cap space 11.5% becomes 11.3%.

Consequences: a hard-nosed club now pays for it. It lets mercenaries
walk, replaces them in free agency at 1.27x market, and carries more
overpaid contracts than a loyal one - loyalty protects a club's books
where before it only cost money.

### Open, found while doing this
- **Win now has no visible effect.** It drives spend share and
  restructures, and neither shows in a club's books.
- **The club's side is loyalty alone.** Aggression and win now do not
  change how far a club goes to keep a star it cannot afford to lose.

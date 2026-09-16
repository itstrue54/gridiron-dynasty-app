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
  *Resolved - it binds now.* Five seeds, steady state: mean space about
  42m (9.8% of the cap) and 3.5 clubs a season under 10m. The rookie
  scale did most of the last step, 11.9% to 9.7% (see "Draft picks are
  assets").
- **NET DEV still decays**, 0.61 -> 0.32 across ten years. Better than
  the 0.02 baseline, still sloping.
  *Closed - the mix, not the rate.* Now 0.58 -> 0.29 over five seeds,
  but no age bracket slopes: 21-24 1.43 -> 1.35, 25-27 0.59 -> 0.57,
  28-30 -0.89 -> -1.05, 31+ -3.63 -> -2.82, first season to tenth. The
  aggregate falls because the league's age mix shifts older, which the
  league-age test already bounds.
- **Coaching quality is fake.** `coaching = 55 + teamId % 25` in
  stepDevelopment derives development quality from team index, so team 0
  is permanently worse at developing players than team 24. There is a
  CoachId but no Coach model. Building the carousel will move progression
  league-wide and require a recalibration pass.
  *Resolved.* Development reads each club's staff (Team.staff, hired at
  league start and migrated into older saves), recalibrated in the
  coaching ADRs above. The team-index formula is gone from the engine.

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
  *Closed, no change.* Nothing has asked for more than 2.00 gives; this
  stands as a note on where the lever runs out.
- **Coachability is now a big trait.** At league-average coaching, young
  players' growth multiplier spans ~0.56x to ~1.34x across coachability
  20-80, against 0.94x to 1.06x before. That is what 7.1 asks for, but it
  makes a hidden trait carry a lot of development - worth surfacing
  through scouting before it feels arbitrary.
  *Resolved in c760bb6.* The roster shows coachability as a SPEC 4.6
  grade: a question mark for a new arrival, a range from 0.4 confidence,
  a grade from 0.7, the true grade from 0.9. Confidence starts at 0.2-0.4
  by scouting department and adds 0.25 a year in the building; a scout's
  miss is per player, drawn from the player's id, so saves need no field.
- **The clamp is not symmetric.** *Resolved.* Ratings now round and clamp
  30-100, the same distance either side of 65. Team-level means rise by
  a point (62.8 to 63.8 over five seeds; single leagues read 62-66), and
  young-player development and the coaching effect do not move.

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
- **The 53-man cut ignores dead money.** *Resolved in 1ba00d6.* A
  player's dead money now counts in his favour at the cut, one rating
  point per million. Dead money thrown away at the cut fell from 3.1% to
  2.4% of the cap per team; about 107 players a year are still cut in
  the offseason they arrived.
- **Upgrade releases stand apart.** *Resolved.* They now reach the news
  screen's release list, and are still counted apart from cap casualties.
- **GM spend share barely shows.** It correlates with a team's space at
  only -0.1 to -0.3. ADR-009's spread of front offices shows up in the
  contracts they sign, not in how much of the cap they use.
  Still true after the pick model: -0.28 over five seeds, from -0.06 to
  -0.45.
  *Resolved.* Spend share is now a reserve held through the market, in
  money: cap x (1 - spend share) x 0.4, about 1-15% of the cap. As a
  share of whatever space was left each day, ten days of bidding spent
  nearly all of it whatever the GM. Five seeds, steady state: correlation
  -0.28 -> -0.57, mean space 10.0% -> 10.6%, clubs under 10m 3.5 -> 1.4,
  as reckless clubs no longer spend to nothing. At 0.3 the correlation
  fell to -0.35; 0.5 matched 0.4 with more of the cap left idle.

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
- **Win now has no visible effect.** *Resolved:* it now sets how a club
  values age and whether a good club trades youth for a star - see "Front
  offices act on their personalities" below.
- **The club's side is loyalty alone.** *Resolved in 4e3b83b:* a starter
  the market cannot replace, win now for players in their prime, and
  aggression all add to how far a club goes.

## ADR-0xx — Front offices act on their personalities

Win now did nothing. Over 1,600 club-offseasons a club was over the cap
at compliance 0.2% of the time, so its restructure allowance never
fired, and its free agency budget share never bound. GmProfile said it
drove old players; nothing read it for age. Only loyalty set how far a
club went to keep its own, and nothing made a losing club pay to be
worth joining.

Four decisions, each measured on five seeds and committed on its own:

- **Win now values age** (f02ac9b). rosterValue takes the club's win
  now: an all-in club discounts age past 29 at half the usual rate and a
  rebuild at one and a half, and a rebuild pays for youth under 26 that
  an all-in club counts against. At 0.5 it is ADR-004's ranking. All-in
  rosters average 27.3 against 26.0 for rebuilds, and all-in clubs carry
  more deals over 1.5x value, 21.6% against 13.2%.
- **Keeping a star is more than loyalty** (4e3b83b). The re-signing
  limit rises for a starter the market cannot replace, moves 8% either
  way with win now for players 27 and over, and 6% either way with
  aggression. All-in clubs keep 3.2 of their own a year and rebuilds 1.4;
  aggressive clubs 2.6 and careful ones 1.8.
- **Bad clubs pay for key veterans** (4c470d0). Free agents prefer
  winners, so a losing club adds up to 25% to its offer for a veteran
  worth a tenth of the largest deal allowed. The worst clubs now pay
  1.35x market for veterans against the best clubs' 1.26x; before, it
  was 1.22x against 1.25x.
- **Contenders trade youth for a star** ("Let contenders trade young
  players for a star"). A club that won .550+, is all in, and has one or
  two real holes trades one or two young players for a 27-plus star from
  a losing or rebuilding club, each side valuing on its own win now.
  Aggression sets how far past break-even it goes and whether it deals
  twice; risk tolerance sets how old a star it takes. About 1.2 stars
  move a season, each for about two young players.

Whether buying a star pays: buyers' records fall from .682 to .595 the
next season against .671 to .564 for contenders who stood pat - about
twenty points less regression, on 52 buyer club-seasons, and noisy by
seed.

League health across all four: cap space 11.2-11.6%, starters
74.8-75.0, league age 26.9-27.0, and 7.4-8.0 distinct champions in ten
seasons.

### Open, found while doing this
- **Trades carry no picks.** SPEC 8.4 wants a pick chart; until the
  engine has a pick asset model a rebuilding club can only take players
  back.
  *Resolved in 86b603c.* Contender trades pay in picks as well as young
  players, and granted trade requests send one back - see "Draft picks
  are assets".
- **The payoff is small and noisy.** *Resolved - real, and modest.* Over
  twenty seeds (328 buyer club-seasons) buyers' records fell 81 points the
  season after a trade against 110 for contenders who stood pat: +28
  points, standard error 10 per seed, so roughly +7 to +48 - about half a
  win in seventeen games. Since the need bar (b326de8) about two stars
  move a season rather than 1.2.
- **Specialists read as holes everywhere.** *Resolved - see "Needs are
  judged against each position's own league".* Centers had it worst.

## ADR-0xx — Needs are judged against each position's own league

Every position's need was measured against one bar: a starting unit
rated under 74 was a need. Centers rate around 63 and fullbacks and
specialists around 45, so every club looked short at all of them, and a
flat no-backup penalty added 0.29 of need at every position the roster
template carries only one of. Free agency's shopping threshold is 0.2,
so every club kept bidding for a second center, kicker, punter, snapper
and fullback, paid 1.5-1.6x market for them, and the 53-man cut sent the
extras straight back: 17.9 centers a season were cut in the offseason
they arrived, the most of any position.

Decision: TeamNeeds.bar measures each position's typical starting unit
across the league, recomputed at each phase, and a club is judged
against that less two points - ADR-006's relative-not-absolute, applied
to needs. No backup counts only where the roster template carries one.
A club with nobody at a position still scores a full need: that is a
real hole.

Measured over five seeds, steady state, before -> after:

    position     need             FA signed      same-offseason cuts
    C            0.49 -> 0.24     16.9 -> 10.2   17.9 -> 10.8
    FB           0.74 -> 0.32      6.9 ->  7.4    4.4 ->  3.8
    K / P / LS   ~0.74 -> ~0.38    ~7.2 -> ~7.6   ~3.4 -> ~3.3

League age, cap space, starters and champions do not move. The bar alone
did little: quality was a small part of these needs, and the flat depth
penalty was most of it - found only by splitting need into its parts.

### Open, found while doing this
- **A club with no specialist still pays about 1.6x for one.** *Closed,
  no change.* Over five seeds the premium at FB, K, P and LS together
  costs about 0.16% of the league cap a season, against 3.1% for every
  free agent premium; at C, a real starter priced near 5.7m, 0.22%. A
  club with no kicker has to get one, and paying over the odds for him is
  right - it never costs enough to matter.

## ADR-0xx — Draft picks are assets, priced by the CBA and the Johnson chart

Context: every club drafted once a round in a fixed order and a pick was
worth nothing until it was used. Nothing could be traded for one, rookie
pay did not depend on where a player went, and a club losing a free agent
got nothing back.

Decision: follow the current CBA and the NFL's trade rules.

- **Order and ownership.** The NFL's order: non-playoff clubs worst
  first, then playoff clubs by the round they went out, strength of
  schedule breaking ties. Each club holds its picks for the coming draft
  and the two after it (`League.picks`, save v3), and whoever owns a
  pick uses it.
- **Compensatory picks.** Awarded in rounds 3-7 for net free agent
  losses, tiered by the lost contract's share of the cap, with the
  cancellation rule, at most 4 a club and 32 a year.
- **Rookie scale.** Pay is slotted by overall pick from 2025's contracts,
  taken as shares of the cap. First-rounders are fully guaranteed and
  carry the fifth-year option, which uses the 2020 CBA's tiers with depth
  standing in for Pro Bowls. Undrafted rookies sign for three years.
- **Value.** Picks are valued by the Jimmy Johnson chart, as-is. Measured
  over four years of `overall - 58` a pick yields 19.2 at 1, 13.4 at 32
  and 1.1 in round seven. That is a ratio of 1.43 from pick 1 to pick 32,
  against 5.08 on the chart. The user chose the chart anyway, because
  it is what front offices trade by. The least-squares rate is 59 chart
  points per yield point. Future picks are valued mid-round, one round
  later. Each club tilts values by its own win now,
  `x(1 + (0.5 - winNow) x 0.5)`.
- **Trades.**
  - Contender trades can pay for a star with picks as well as young
    players.
  - A club granting a trade request gets back the best pick the suitor
    holds that is worth no more than the player is to the club letting
    him go.
  - In round one, an aggressive club within twelve picks can trade up
    when the club on the clock does not need the best player left. It
    pays the chart difference in future picks.

Measured over five seeds, ten-year runs:

    step                         result
    order + ownership            distinct champions 7.4 -> 8.2
    compensatory picks           29.8 a year, to 14.8 clubs
    rookie scale + options       steady cap space 11.9% -> 9.7%, 3.5 clubs
                                 under 10m; 19.2 options exercised and
                                 11.0 declined a year
    picks traded, per run        0 -> 5.8 (contenders) -> 39.2 (requests)
                                 -> 51.4 (draft day)
    stars moved, per run         20.0 -> 21.0

Champions (7.4-8.4) and cap space (9.5-9.9%) stay within seed noise across
the three trade steps.

### Open, found while doing this
- **The chart overprices early picks against what they yield.** It is
  kept on purpose, so clubs overpay to move up the way real ones do. If
  that ever distorts rebuilds, the least-squares rate is the lever.
  *Closed, no change.* Across the four pick-trade steps distinct
  champions stayed 7.0-8.4 per ten years and steady cap space 9.5-10.0%.
  The last step reads 7.63 -> 7.13 over eight seeds, -0.5 with a
  standard error of 0.33 and differences both ways: noise. The chart
  stays.
- **Draft-day trades are only trade-ups in round one, paid in future
  picks.** There are no trade-downs for extra picks this year, and a
  club cannot pay with a later pick from the current draft. Adding either
  would mean reordering the slots while the draft runs.
  *Resolved in 79b1081.* A club moving up can pay with this draft's
  later picks as well as future ones, reassigned as the draft runs, so
  the club moving down leaves with extra picks this year. Picks traded
  per ten-year run 51.4 -> 55.4.

## ADR-0xx — Awards are the offseason's first phase

Context: SPEC 7 opens the offseason with awards, All-Pro teams and
retirements announced. The five awards existed but only the one-season
simulator decided them; a dynasty never handed any out, and there was no
Comeback Player, Coach of the Year, All-Pro team or Pro Bowl.

Decision: the offseason decides every SPEC 6 award before anyone retires
and keeps them in the report, winners named so a retiree still reads.
- **All-Pro and Pro Bowl** go by rating plus up to eight points of
  production and ballot noise, because a lineman has no stat line. AP
  shape: 24 first-team places, 24 second; 42 Pro Bowlers a conference.
- **Comeback Player** is the biggest rise in production from a veteran
  who managed under 40% of it the season before. Last season's stat
  lines now stay on the dynasty for this.
- **Coach of the Year** is the head coach whose club won and rose most,
  against last season's record from the previous report.

Awards draw on their own seed, so the simulation does not move.

### Open, found while doing this
- **The fifth-year option still reads depth, not Pro Bowls.** The 2020
  CBA tiers are by Pro Bowls; with a Pro Bowl now voted, the option could
  use it. That changes who is exercised, so it needs its own measurement.
  *Resolved.* Players count their Pro Bowls, and the option's tiers read
  them: two or more set it at the franchise tag, one at the transition
  tag, both priced by CBA tag position as the tag itself is; playing time
  and the basic tier are unchanged. Five seeds, per year: 12.5 -> 13.0
  options exercised, 8.8 -> 8.2 declined - fewer players now reach the
  top tiers than the old top-ten-at-his-position stand-in allowed, so
  options run a little cheaper. Cap space 10.4% -> 10.4%.

## ADR-0xx — The franchise and transition tags (2020 CBA)

Context: SPEC 7 phase 5 was a no-op. A club that could not re-sign its
best expiring player lost him to the market.

Decision: follow the CBA. After re-signing, each club may tag one player
it could not keep.
- **Franchise tag.** A fully guaranteed one-year tender at the mean of
  the five biggest cap hits at his CBA position (QB, RB, WR, TE, OL, DE,
  DT, LB, CB, S, K/P). He stays.
- **Transition tag.** The top ten's mean. He goes to market, and his club
  may match the offer he takes if it has the room. If nobody offers, he
  plays on the tender.
- **Consecutive tags** cost 120%, then 144% or the quarterback tag,
  whichever is more. A new deal ends the run.
- **AI clubs** tag the best player they could not keep, when he is worth
  1.3x the franchise tag, or 1.6x the transition tag, and fits.

Simplified: prices use this year's cap hits, not the CBA's five-year cap
shares, and there are no offer sheets on a franchise tag. AI clubs would
almost never give two firsts, and real ones rarely do.

Swept over five seeds, ten-year runs, per year:

    bar                          franchise   transition   cap space
    franchise 0.9                  13.6         4.1         8.9%
    franchise 1.3                   6.5         4.2         9.5%
    + transition 1.6                7.2         0.5         9.9%
    + transition 2.0                7.2         0.0        10.0%

The NFL runs five to eight franchise tags a year and zero or one
transition tag. Tenders tighten the cap a little: 10.6% mean space to
9.9%, clubs under 10m 1.4 to 2.2.

## ADR-0xx — The coaching carousel: fire, hire, and schemes follow the head coach

Context: SPEC 7 phase 2 was a no-op. Every club kept the staff it was
generated with forever, so a bad staff developed players badly for good
and schemes never changed.

Decision: head coaches are fired and replaced, and the new one brings his
schemes. No coordinator poaching.
- **The hot seat** carries 60% of itself over. A season under .500 adds
  to it, and a season worse than the last adds more. A playoff run takes
  20 off.
- **Firing.** A club fires its coach when the seat passes its GM's bar,
  55 for the most patient GM down to 35 for the most win-now. It also
  lets him go when his contract ends after a losing year. A kept coach
  whose deal is up is extended three years.
- **Hiring.** The club hires the best of four outside candidates, read
  through noise, on a five-year deal. He brings his offence, and a
  defensive coordinator with a defence. Players on a side whose scheme
  changed start learning it again. Position coaches stay.
- **Recalibration.** Candidates are drawn at a mean of 58, not the
  league's 65. The best of four drawn at the mean would lift league
  coaching every year, and development with it (SPEC 7.1).

Measured over five seeds, ten-year runs:

    fire bar   coaches replaced   new schemes   net dev   dev 21-24
    (none)          0.0/yr            0.0          0.41       1.37
    75              4.2               4.1          0.41       1.37
    65              4.6               4.5          0.42       1.38
    55              5.5               5.4          0.42       1.38

The NFL replaces five to ten head coaches a year. Development holds at
every setting. Cap space 9.9% -> 10.3% and champions 8.0 -> 8.0.

### Open, found while doing this
- **Almost every new head coach changes a scheme** (5.4 of 5.5). His
  schemes are drawn from the whole catalog. Real hires more often keep
  one side, and weighting the draw toward the club's schemes would fix
  that.
  *Resolved in b34e0d6.* Candidates run the club's
  own scheme 30% of the time and otherwise lean to schemes the roster
  suits, fit taken as a z-score across the catalog. A new staff now
  changes a scheme 74% of the time, keeping the offence 48% and the
  defence 50% - more continuity than the third aimed at, with no hard
  NFL figure to set it by.
- **A scheme change resets time in the system.** On the field that is
  the point, but it also resets the coachability grade's confidence
  (c760bb6), as if the new staff had never seen the player. A separate
  years-with-club count would split the two.
  *Resolved in 0146443.* Players count seasons with the club apart from
  seasons in the scheme. A new staff resets only the second, and the
  coachability grade reads the first.
- **Fired coaches never work again.** Every hire is an outside
  candidate, and a rehire pool is the natural next step.
  *Resolved in b34e0d6.* Head coaches out of work are
  candidates again. A club looks at two per vacancy, and a firing costs
  a coach six points in its eyes. 22% of hires are rehires; development
  0.42 -> 0.41, ages 21-24 1.38 -> 1.36, champions 8.0 -> 8.0.
- **Generated rosters are not built for their schemes.** At league
  creation a club's scheme is its roster's best fit for 4 of 32 offences
  and 3 of 32 defences, and on average no better than the rest (z = 0.0);
  schemes differ in fit by a few hundredths. Every club starts slightly
  out of scheme, and generation could build rosters toward them.
  *Shelved to M11.* Tried: generation picking each archetype weighted by
  exp(lean x fit) for the club's scheme. Five seeds at creation, the
  club's scheme is its best fit 16% -> 32% at lean 2, 56% at 4, 86% at
  8; at 4, mean z 1.16 and mean fit 0.815 -> 0.864. The cost lands on
  game calibration. At lean 4, 15 of SPEC 13.2's 18 bands pass (7.61
  yards an attempt, 364 a game); at leans 2 and 3, 14. Lowering the
  scheme multiplier's floor to 0.732 passes all 18, but deflates every
  effective rating about 3% and breaks valuations set on the old scale
  (the pick chart's rate, replacement 58, the star bar; the pick-trade
  test fails). Holding the mean multiplier - floor 0.748, or range 0.20
  with floor 0.783 - keeps valuations but fails the calibration and
  season-leader tests, and range 0.20 the seven-point scheme-effect
  test. It needs the passing game retuned with rosters in scheme: M11.

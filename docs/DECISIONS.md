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

## ADR-0xx — The tuning table is the league's, and schemes carry its scheme-fit math

Context: SPEC 12 puts every coefficient in the sim in one TuningTable,
saved with the league and editable in-game behind an Advanced toggle,
with three presets. The table existed with 7 of its groups and the
presets, but the league did not carry it: every game ran on Realistic,
special teams, progression, the AI and the scheme-fit math read none of
it, and about forty play-resolution coefficients were still literals.

Decision, in five slices. Each was verified against two seeded ten-year
dynasties and the calibration report, which came out identical apart
from their timings.
- **The league carries its table** (2020355). Older saves load as
  Realistic. Dynasty games, season sims and calibration default to it.
  Special teams joined as a group: the field-goal curve and range,
  clutch, extra points, punts, touchbacks and returns.
- **Play resolution** (de87ea5). The pass, run and penalty resolvers'
  coefficients moved into passing, coverage, blocking, rushing and
  penalties. Probability clamps and fallbacks stay literal.
- **Progression and AI** (feb7f02). Progression holds the development
  model, including SPEC 7.1's coach slope, the age curve and retirement
  by age. The ai group holds what SPEC 12 names: free-agent aggression,
  trade frequency, and how the draft weighs need and fit against talent.
- **The tuning screen** (5563c17). The Hub's Advanced button opens it:
  the three presets in one tap, then every value, group by group, as a
  slider from zero to twice its Realistic value. It reads the table
  through its own serializer, so a value added later appears by itself.
- **Scheme-fit math** (656794f). SPEC 4.9's constants (the scheme multiplier's floor and
  range, familiarity, emphasis, fatigue, morale) joined as a ratings
  group. Passing the table to every place that reads a rating meant 81
  call sites and about 12 helper signatures. Instead a scheme carries
  the group: the league's scheme lookups attach it (game teams, the
  offseason's resolver, the app's screens), and the rating math reads it
  from the scheme it is already given. Games, valuations and screens
  agree. A lookup that skips the league gets Realistic, and one search
  for SchemeCatalog finds any such lookup.

### Open, found while doing this
- **Fourth-down and play-calling literals stay in code.** They are coach
  tendencies (SPEC 5.4), which belong with the coaches rather than the
  table.
- **Sliders run from zero to twice the Realistic value.** That is the
  same rule for everything, so a probability near one can be set past
  one. The code clamps most probabilities, but not all.
- **The tuning screen has only been compiled,** not run on a device.

## ADR-0xx — The depth chart is pins over the automatic order

Context: SPEC 5.5 wants a per-position depth chart with package
overrides. Every game rebuilt each club's chart from scheme-adjusted
overall, and nothing let a club say who plays.

Decision: a club keeps pins, not a whole chart (a4826d1, screen fdd977d).
- **Order.** Per position, the players pinned to the top of the chart,
  in order. Everyone else follows the automatic order, so a pinned
  player who leaves drops out and a newcomer slots in where the
  automatic order puts him - no upkeep after trades, signings or the
  draft. A chart nobody touches is the automatic one exactly: with no
  pins, two seeded ten-year dynasties and the calibration report are
  unchanged apart from timings.
- **Packages.** Per personnel grouping or defensive front, who fills a
  position in it, ahead of the position's chart. The screen groups them
  by the spots packages actually add: third and fourth receivers, two-
  and three-tight-end sets, the third corner, dime corners, three
  interior linemen and goal-line linebackers.
- **Returners.** A kick and a punt returner can be named; otherwise the
  fastest skill player returns, as before.
- **Snaps.** The offseason's playing-time ranks follow the pins, so a
  starter the club chose gets starter's snaps for development. The
  rollover drops pins for players who have left.
- **The screen.** Moving a player pins everyone down to him in the new
  order; Auto clears a position or package.

### Open, found while doing this
- **Fatigue and snap rotation (SPEC 5.5's third bullet) are not
  modelled.** Players carry a fatigue rating the sim never reads, and
  only running backs rotate, on a fixed 60/28/12 split.
- **Nobody plays out of position.** Pins only reorder players at their
  own position; a corner cannot be pinned at safety.
- **The two-back sets (21, 22, goal line) take their backs from the RB
  and FB charts,** with no package spot of their own on the screen.
- **The screen has only been compiled,** not run on a device.

## ADR-0xx — The game plan overrides the levers the play caller already reads

Context: SPEC 5.4 says you do not call plays; you set the tendencies
your coordinators call from, on a game plan screen. The play caller read
four tendencies off the scheme (pass rate, play action, blitz rate,
man/zone) and fixed numbers for the rest, and fourth-down aggression was
derived from each club's id.

Decision: a club carries a GamePlan whose levers are only the ones the
play caller already reads, so every slider does something at once
(7e0266e, screen 16700c3). The levers are pass rate, play action, deep
shots (split from play action, which they used to follow at 0.65), the
lean to the pass when trailing, the two-minute boost, blitz rate,
man/zone, how often to double the top receiver, and fourth-down
aggression. Each is an override: unset, it is the scheme's value or the
league's usual figure, so an untouched plan plays as a club with none.
With no plan set, two seeded ten-year dynasties and the calibration
report are unchanged apart from timings. AI clubs play their schemes as
before.

### Open, found while doing this
- **SPEC 5.4's full Tendencies are not here.** Missing: pass rate by
  down, distance and score as curves, red-zone and goal-line rates,
  screens, run direction, box rates, a rating threshold for doubling,
  and in-game adaptation. Several need new play-calling logic.
- **Tempo does nothing.** Schemes carry a tempo value that nothing
  reads; it would need clock and plays-per-game logic.
- **AI coordinators have no tendencies of their own.** Every AI club
  plays its scheme's values - the coach-tendencies piece.
- **The screen has only been compiled,** not run on a device.

## ADR-0xx — Coaches carry their own tendencies, drawn near their scheme

Context: every AI club called games straight off its scheme, so two clubs
running one scheme were indistinguishable, and fourth-down aggression was
derived from each club's id. SPEC 5.4 makes coordinators the game's
personality.

Decision (e10825d): a coach's tendencies are the game plan's levers for
his role, drawn near his scheme's values. Offensive coordinators get
pass rate and play action (sd 0.02 each), trailing lean (0.10) and the
two-minute boost (0.025). Defensive coordinators get blitz rate (0.025),
man/zone (0.04) and double teams (0.02). Head coaches get fourth-down
aggression around 0.53 (sd 0.06), the old id formula's average. Draws
use a stream split per coach, so nothing else in league generation
moves. A club plays from its own game plan, then its staff's
tendencies, then its schemes. The carousel's hires bring theirs. Saves
went to version 4: an older one gives each coach his from his own id.

How it was judged. calibrate used to play one league on one seed, and
single 4,000-game runs put one band or another just over its edge -
close games at twice these spreads, yards an attempt at these. With
calibrate taking --seed (273aef0), six leagues at 4,000 games each show
no band moving against the baseline: yards an attempt 7.537 against
7.532, close games 0.192 against 0.192, bands passing per league
17,17,17,18,16,17 against 17 in every one. Five seeds, ten-year runs:
development 0.42, ages 21-24 1.38, cap space 10.5%, champions 6.8,
against 0.42, 1.37, 10.4% and 7.8 before.

### Open, found while doing this
- **The engine averages 17 of 18 bands, not 18.** Across six leagues
  yards an attempt sits over its 7.50 ceiling (7.53) in the baseline in
  every one. The default league happens to sit just under, so every
  single-league "18 of 18" so far was that league's luck - including
  the band counts behind shelving scheme-built rosters, which deserve a
  second look with --seed. M11 starts from here.
- **Head coaches vary less on fourth down than before.** Their
  aggression spreads at sd 0.06, where the old id-based figure spread
  about 0.12.
- **No in-game adaptation.** SPEC 5.4 has coordinators shift within
  plus or minus 0.12 toward what the opponent does, by the head coach's
  adjustments rating.
- **Tendencies do not follow ratings.** A strong game planner is no more
  likely to go for it on fourth down than a weak one.

## ADR-0xx — Fatigue builds snap by snap, the tired rotate out, and the game is recalibrated for it

Context: SPEC 5.5's third bullet - fatigue per snap by position and
stamina, recovery between drives and at halftime, and backups getting
snaps by it. Players carried a fatigue rating nothing set, and only
running backs' carries rotated, on a fixed split.

Decision (21f4960): fatigue lives within a game. A scrimmage snap costs
each player on the field his position group's figure (line 3, front 15,
backs 13.5, receivers 6, linebackers 5, secondary 4.5, quarterback 1.5)
times 1.5 - stamina / 100; a snap on the sideline gives back 5, the
break between drives 10, halftime 40. At rotating positions - backs,
receivers, tight ends, the front seven and the secondary - a player
comes out at 40 and returns at 15. Quarterbacks and linemen never
rotate. Fatigue reaches effective ratings through the ratings group's
existing penalty (up to 15% at full fatigue). A club's package pins
still come first. Snap shares over 64 games: lead back 0.66 (second
0.29), starting edge 0.69 and tackle 0.73, WR1 and TE1 0.93, LB1 about
0.97, corners and safeties 0.98-0.99; quarterbacks and linemen 1.00.

Calibration. The penalty barely mattered - at 0.15, 0.08, 0.04 and 0
the game moved the same - rotation did: backups on the defensive front
added 0.15 yards a carry and took 0.008 off the sack rate. The
calibration had been set with starters on every snap, so rushing base
yards went 3.60 -> 3.45 and a pressure's sack chance 0.18 -> 0.21.
Passing had already run hot - across six leagues the baseline averaged
7.54 yards an attempt against a 7.50 ceiling - and quarterbacks who
never rotate facing defenses that do ran hotter still. Yards after the
catch went 0.43 -> 0.37, which alone moved it little, and base
completion 0.80 -> 0.79; 0.785 took points just under their floor.
Six leagues at 4,000 games: bands per league [18, 18, 17, 17, 17, 17] against [17, 17, 17, 18, 16, 17]; yards an
attempt 7.448 (7.537), completion 0.647 (0.647), yards a carry
4.207 (4.190), sack rate 0.073 (0.072), yards a game 350.833
(351.667), points 21.050 (21.100), home wins 0.567 (0.570). Five
seeds, ten-year runs: development 0.41, ages 21-24 1.37, cap space 10.8%, champions 8.0.

The calibration test moved from 260 games to 1,000. At 260 the
home-win rate on its league swung 0.48 to 0.58 on the draw alone -
about 0.39 of the band, past the test's 0.30 tolerance - while 2,000
games read 0.56 with or without fatigue. Same league, bands and
tolerance; at 1,000 games both the baseline and fatigue pass all 18.

### Open, found while doing this
- **The season's top passer has a long tail.** Over twelve seasons the
  leader averaged about 5,550 yards (about 5,470 before fatigue) and a
  season or two in twelve went just past 5,800; trimming league passing
  lowered the average but not the tail, and cost bands.
- **Depth players are much worse than starters** in generated rosters,
  which is why rotation cost the defense so much. Real clubs' rotation
  players are closer to their starters.
- **Rushing leaders fell** from about 1,900 yards to about 1,400, as the
  lead back now shares the ball.
- **Red-zone touchdown rate dipped** from 0.578 to 0.558, still inside
  its band.
- **Snaps are counted but not shown.** The box score has no snap counts.
- **Fatigue does not cause injuries.** SPEC ties durability under load
  to snap count; injuries ignore fatigue.
- **Tempo still does nothing.**
- **A tired player pinned to a package stays in.** Package pins outrank
  rotation.
- **The Arcade and Grinder presets set these values absolutely**
  (rushing base yards 3.8 and 3.0, completion 0.71 and 0.62, YAC 1.35
  and 0.82), so they now sit a little further from Realistic.

## ADR-0xx — Fatigue and wear cause injuries at NFL rates, rotation asks whether the backup is as good, and the leaders are recalibrated

Context: fatigue did not cause injuries (open from the last ADR), depth
players were far below starters so rotation mostly hurt, the season's
top passer averaged about 5,500 yards with seasons past 6,000, and the
top two rushers never reached 1,700 (asked for every season).

Decision (d629fb5, eca4218):
- **Injuries.** Each scrimmage snap risks an injury at the position's
  rate (the injuries group, scale 1.08), times 1 + 1.5 x fatigue, times
  1 + 0.6 x season wear, times the game's load, proneness and low injury
  resistance. Severity: 40% one game, 24% two, 12% three or four, 13%
  five to eight, the rest the season. The injured miss games; wear
  builds with snaps (more at low durability under load) and keeps 75%
  week to week; if a whole position is hurt the least hurt plays.
- **Rotation.** A tired player comes out only for a teammate who,
  fresh, is at least as good as he is tired, by scheme-adjusted
  overall. Generated depth at rotating positions sits a few points under
  the starters, and the draft counts a rotation player more than six
  points behind as a need.
- **Leaders.** A league-wide pass-rate lean of -0.04 (new gameFlow
  field), a lead back's carry share of 0.83 (0.95 for the top two), a
  25-carry cap per game for the lead back (new rushing field), back
  fatigue 13.5 -> 9, rushing base yards 3.45 -> 3.72, vision and
  break-tackle slopes 0.020/0.022 -> 0.025, completion weights
  0.62/0.55 -> 0.35/0.45 with base completion 0.81 and YAC 0.35, and
  red-zone compression 0.40 -> 0.32 (runs) and 1.45 -> 1.35 (coverage).
  Grinder's injury scale went 1.25 -> 1.35 to stay proportionally
  above Realistic.
- **Test bounds, with the user's approval:** rushing leader 900-2,600
  (was 2,300) and lead-back snap share 0.5-0.95 (was 0.8).

Measured. Twelve seasons: passing leader 4,918 on 657 attempts, range
4,671-5,178 (was 5,494, range 5,126-6,010); rushing leader 2,055 on 363
carries, range 1,757-2,496; second 1,888 on 349, lowest 1,740; both
over 1,700 in 12 of 12 (was 0). Injuries per club-season 24.1 (NFL
about 28), games missed 65.4 (adjusted games lost usually 70-90), 64%
costing two games or fewer (NFL 64%), 38% in the fourth quarter (about
41%). Per player-game, % (NFL): RB 4.8 (5.2), TE 4.6 (4.9), S 5.3
(4.7), CB 4.0 (4.4), LB 4.7 (4.3), DT 4.5 (4.3), DE 4.5 (3.9), OL 3.2
(3.4), QB 2.7 (2.5), WR 3.7 (4.0). Snap shares: lead back 0.85, second
0.16, edge 0.72, tackle 0.77, WR1 0.95, TE1 0.92, LB1 0.97, CB1 0.99.
Six leagues at 4,000 games (seeds 201-206): bands per league [17, 17,
16, 18, 17, 18] against [17, 17, 15, 18, 17, 17], all 18 in on
average; yards an attempt 7.270 (7.452), completion 0.637 (0.647),
yards a carry 4.502 (4.220), points 21.183 (21.283), yards a game
347.3 (353.8), red-zone TD rate 0.590 (0.565).

Sources: ProFootballLogic, NFL injury rate analysis
(profootballlogic.com/articles/nfl-injury-rate-analysis); NFL injury
data, 2023 season key takeaways (nfl.com/playerhealthandsafety);
CBS News, NFL injuries up in 2010; adjusted games lost
(ultimatenyg.wordpress.com, 2023; ftnfantasy.com, 2025); PMC9851848.

### Open, found while doing this
- **Fewer injuries in total than the NFL** (24 against 28) while the
  per-position rates match: special teams are not simulated, and the
  NFL's count includes them.
- **SPEC 5.9 is not complete:** no play-type risk (runs and sacks), no
  medical staff, no recurrence.
- **Points sit near their floor** (21.18 against 21.0); running more
  scores less.
- **Yards a carry 4.50** is above the NFL's recent 4.3-4.4, the price
  of 1,700-yard seconds.
- **The rushing leader's tail** reaches about 2,500, past the NFL
  record of 2,105; it follows from asking two backs for 1,700 a year.
- **Depth still thins over a dynasty:** ten years in, rotation players
  sit 10-12 points under starters however strongly the draft weighs
  them; talent supply, not need, sets that.

## ADR-0xx — Yards a carry comes down to the NFL's, and the clock pays for it

Context: the league ran 4.50 yards a carry against the NFL's recent
4.3-4.4 - an open item from the last ADR, and the price of asking two
backs for 1,700 yards a season. Base run yardage had been raised to 3.72
to hold points up when play calling leaned toward the run.

Decision: base yards 3.72 -> 3.58, with the slope that separates backs
raised to compensate where it belongs - vision and break-tackle
0.025 -> 0.032, the lead back's carry share 0.83 -> 0.86 and his cap 25
-> 27 a game. That keeps the bellcows while the league average falls.

Lowering it cost 0.4 points a game, and the two obvious ways to buy them
back were closed: the red-zone touchdown rate already sat at 0.59 against
a 0.60 ceiling, and field goals were being made 1.91 times a game on 2.20
tries, at or above NFL volume. So the points come from one more
possession's worth of clock - a completed pass runs 28 seconds off
instead of 29 - and base completion 0.81 -> 0.82.

The clock is the most sensitive dial in the table. Four seconds off the
run and completion runoffs put 42 yards and 2.6 points a game on every
team and lost four bands; one second off completions alone is worth about
0.2 points.

Measured, six leagues at 4,000 games (seeds 201-206), against the code it
replaces: yards a carry 4.412 (4.502), points 21.333 (21.183), yards a
game 350.3 (347.3), completion 0.643 (0.637), yards an attempt 7.353
(7.270), red-zone touchdowns 0.588 (0.590); bands per league
[18, 17, 16, 18, 17, 18] against [17, 17, 16, 18, 17, 18], all 18 in on
average. Twelve seasons: the top two rushers still clear 1,700 every
season, and the passing leader averages 5,057.

### Open, found while doing this
- **The passing leader went up**, 4,918 -> 5,057, because base completion
  paid for the points. Still inside what the NFL's leaders throw for, but
  further from the middle of it than it was.
- **The interception rate sits on its ceiling**, 0.028 against a
  0.02-0.028 band, where it read 0.027 before.

## ADR-0xx — A club sees what it believes, and pays scouts to believe it more precisely

Context: SPEC 4.6's ScoutingLens was a comment. Ratings were shown as
facts, the draft board drew a fresh random error every time a club looked
at a prospect, and nothing a club could do changed what it knew.

Decision:
- **The lens is real.** A club sees a point estimate and a band about
  twelve points wide at no confidence, closing as it sees more and never
  reaching certainty. The miss belongs to a club and a player together
  and is drawn from their two ids rather than stored, the way trait
  grades already were: no save field, and two clubs are wrong about a man
  differently and stay wrong. SPEC says store a bias at generation; the
  derived version is observably the same and cheaper, and the trait code
  had already set that precedent.
- **One confidence model** serves ratings and traits. A man on a club's
  own roster is never a mystery - he practises in front of the staff - so
  a new arrival reads 0.35-0.55 by scouting department and each year adds
  0.25: known by his second season, guesswork as a rookie. Traits keep
  their wider band and the 0.4, 0.7 and 0.9 thresholds.
- **Generated rosters carry years with the club**, drawn against a man's
  years in the league rather than the years left on his deal. They buy no
  scheme familiarity - that is still earned in the sim - only knowledge.
  Without them every club began unsure of its own roster.
- **Scouting is a standing club decision**, not a minigame: the
  department sets the budget, the focus sets the spread. Name a position
  or two and the club enters the draft sure about those men and guessing
  at the rest; name none and it knows a little about everybody. Every
  club honours its own focus, and an AI club points its scouts at its two
  biggest needs.
- **The draft room without a resumable offseason.** The run splits at the
  draft: runToDraft plays the phases before it and returns a pause, the
  pause shows the board whenever the club is on the clock, and finishing
  plays the draft out with the club's picks in it. The pause is in memory
  and never saved, because those phases are deterministic - a club that
  closes the app runs them again. Resumability, with the offseason state
  serialized and a save version bump, is the cleaner end state and is not
  needed yet.
- **The miss is half the band, not all of it.** At the full width the
  rating escaped the published band about a third of the time: the first
  draft run took a tight end the room had at 79-98 who was 66. At half,
  the rating sits inside the band about nineteen times in twenty, pinned
  by a test, and a club can still bust without the band being a lie.

Measured. Ten-year dynasties on three seeds: the lens alone changed
nothing (mean 69.7, 69.7, 69.6 against 69.5, 69.7, 69.6); focused
scouting changed nothing (69.6, 69.7, 69.7); the narrower miss lifts the
league about a quarter of a point (69.9, 70.0, 69.8) because clubs draft
a little better, which is what paying scouts is for. Club tenure reaches
scouting only: two of three seeds came out bit-identical. The split at
the draft is behaviour-preserving to the line.

### Open, found while doing this
- **The market is still omniscient.** Free agency, re-signing and trades
  read true ratings, so a club that cannot judge its own rookie prices a
  free agent perfectly.
- **Exposure is a draw, not a fact about the player.** SPEC wants a small
  school or big program factor on the prospect; this derives it from his
  id instead, so nothing in the UI can explain why he is unknown.
- **No combine or pro days.** SPEC 7 phase 8 has events that move
  confidence; the club's focus is all there is.
- **The room shows overall and fit, not traits.** A prospect's character
  is scouted in the engine and invisible in the draft room.
- **Existing saves keep their old tenure**, so their rosters read hazier
  than a new league's.

## ADR-0xx — A league remembers: careers, records, a hall, and the week's news

Context: SPEC 4 gave a player `careerStats` and SPEC 4.7 gave a league a
`history`, and neither existed. A season's stat lines were thrown away at
the rollover, so nothing in the game could say what a player had done or
who had won anything three years ago - which is most of what a dynasty
is for.

Decision:
- **Careers, season by season**, folded in at the start of the offseason
  and before anybody retires, so a man takes his last year with him. A
  year already recorded is replaced rather than added, so folding twice
  cannot double anybody's numbers.
- **A season record per year**: champion, every club's record, the year's
  awards, and who led the league in passing, rushing, receiving, sacks
  and interceptions, each kept with the player's name so a leader who
  retires is still on the record. The men who retire leave their careers
  in the league's history, which is the only place they survive.
- **Retention follows SPEC 9.2**: stat lines forever, standings and
  awards forever, no play data. Save version 5, migration in the same
  commit, with a test that a version-4 save loads and simply remembers
  nothing before the season it is on.
- **The hall of fame** votes three years after a man finishes, on what he
  did against what his position is asked to do, plus what the league said
  about him while he played. Up to three a year over a bar of fourteen
  leading seasons' worth. A guard is judged entirely on his hardware,
  which is the only record of what he was.
- **The week's news** is drawn from the week that was played - injuries,
  big afternoons, career marks turning over, hot seats - and kept for the
  season only. A league remembers its standings and its records, not its
  headlines.
- **A roster leaves as a spreadsheet** in the shape the game reads back
  (SPEC 9.4), to the phone's Downloads on Android 10 and up.

Measured. The divisors in the hall of fame were guessed first, and it
showed: eight of the first nineteen inductees were corners, because a
corner's 166 tackles and nine interceptions scored as nearly three
leading seasons at once while a tight end could not reach one however
good he was. Against measured leading seasons - 1,700 receiving for a
receiver, 1,100 for a tight end, 18 sacks for an edge, 125 tackles for a
linebacker, 166 for a corner - a leading season is worth about 1.0
everywhere, and fourteen years of dynasty then takes ten men from 264
eligible careers: three quarterbacks, two backs, a receiver, a tackle, a
linebacker and an edge.

The history screen also caught a calibration fault the bands cannot see.
The receiving leader read 1,302 on the record; over eight seasons he
averaged 1,550 against the NFL's 1,700, with the right target share and
a high catch rate but low yards a catch - he was being fed underneath,
because the deep-target table gave a first receiver 0.37 of them against
a second receiver's 0.29. At 0.44 he reads 1,629 on 163 targets. Six
leagues at 4,000 games: all 18 bands in on average and one league better
than before. The remaining 70 yards would cost a band, so they are left
alone. Those shares were literals inside the play caller, which SPEC 12
forbids, and are now in the tuning table.

### Open, found while doing this
- **Corners out-tackle linebackers**, 166 to 124, where the NFL has that
  the other way about. Tackle attribution leans on the secondary.
- ~~**The rushing record reads 2,583**~~ Fixed; see the rushing tail ADR.
- ~~**Box scores are not retained.**~~ Built; see the box score ADR below.
- ~~**There is no transactions ledger.**~~ Built; see the wire ADR below.
- ~~**News has no benchings and no contract disputes**~~ Both built; see
  the form and dispute ADRs below.
- ~~**The hub's news block and the export button have not been seen on a
  device.**~~ Both walked on a phone; see the device pass ADR below.

## ADR-0xx — Signing and cutting all year, and sixteen men who practise

**Context.** Free agency was ten days in the spring. A club that lost two
guards in October played the rest of the year short, and the practice
squad status existed on the model with nothing using it.

**Decision.** `Transactions` signs, releases, poaches and promotes at any
time; `PracticeSquads` gives every club sixteen.

- A man on the street signs for the league minimum on a one-year deal.
  Anyone worth more was paid in the spring.
- Refusals are sentences, not booleans: under contract elsewhere, retired,
  a full 53, no cap room, a full squad, all six veteran places taken, a
  third man at one position.
- A squad player is a free agent his club trains (CBA Article 33): no
  team on his record, PRACTICE_SQUAD as his status, and the club holding
  him on `Team.practiceSquad`. Any club may sign him to its 53, his own
  included, which is a promotion.
- Squads dissolve in the spring and are chosen again after the cut to 53,
  snaking through the league so the first club listed does not take the
  best sixteen. Where the street runs dry, undrafted camp bodies are
  generated. Saves from before squads (version 5) have theirs filled on
  load, seeded off the dynasty.

**Measured.** One league over three seasons: every squad 16; squads
formed in the offseason average 58–62 against 56–58 for the bottom eight
of the 53, which is close to how it goes in the NFL. The full engine
suite passes, the 30-season stability gate included.

### Open
- **Squad pay is not charged.** Roughly $230k a man counts against the
  real cap; under half a percent per club, and charging it means
  contracts for men who can leave any Tuesday.
- ~~**The AI never poaches.**~~ Built; see the poaching ADR below.
- **The best squad players are too good.** The top of the squads reaches
  the high 70s and low 80s, men the offseason left unsigned. That was true
  of the street before squads existed; squads only make it visible.
- **No three-week rule** for a poached player, and no elevations for game
  day.

## ADR-0xx — Injured reserve, the Tuesday wire, and legal on cut-down day

**Context.** With practice squads in, the league's clubs still never used
them: a man hurt for eight weeks sat on the 53 while deeper backups played.

**Decision.**
- Out four weeks or more (the NFL's minimum stay) goes on injured reserve
  at every club: still paid, not on the 53. IR does not dress unless the
  club has left nobody else at his position.
- The league's clubs fill the place at the position that most needs a
  body, from their own squad first and the street second, top the squad
  back to sixteen, and cut the stopgap when the man returns. The user's
  club decides for itself, except that a healed man walks back into an
  empty place, as he did before reserve existed. The Hub says when the
  user's 53 has open places.
- In-season signings cost the minimum prorated by the weeks left, one
  eighteenth a game (CBA Article 26), for every club. A street deal is one
  year of base salary with nothing guaranteed; it had been going through
  `Contract.of`, which gave a minimum man a 35% signing bonus and left
  dead money when he was cut.
- A second cap check on cut-down day. Free agency left every club legal,
  then rookie deals and template fills put five to nine clubs a year back
  over, by up to $31M, with nothing checking again. One or two a year
  started the season over the cap and could not sign anybody when
  reserve opened a place. The new pass restructures what it must and
  cuts to the 46 floor if it has to.

**Measured.** One league, one season: 7.2 players a club on reserve, the
league's clubs at 53 active, squads at 16. Thirty seasons: clubs starting
a season over the cap, 1-2 a year before and none after; no league club
ending a season below 50 active; six club-seasons starting at 46 after
the cut-down pass, filled during the season.

**Changed guard, with the user's agreement.** The 30-season test held
every club to the same roster size. With reserve, a club's active count
is what the 53 means; the league's clubs are now held to 46-53 active and
the test's own unmanaged club only to 53.

### Open
- The NFL puts around a dozen a club on reserve a season; the sim's 7 is
  the four-week-plus injuries only, with no preseason injuries.
- No designated-to-return limit, no practice-squad elevations.

## ADR-0xx — The transactions wire, kept forever

**Context.** SPEC 4.7 has an append-only ledger and 9.2 keeps it forever.
With reserve and in-season signings, clubs make hundreds of moves a year
that nothing recorded.

**Decision.** `League.transactions`: one compact line per move (year, the
game week it came before, kind, club, player, amount, years, other club).
Names and positions are copied onto the line because a retired man leaves
the player list. In season every move through `Transactions` and every
reserve placement is written; the spring's retirements, trades, releases,
signings and draft picks come from the lists the offseason already builds.
The cut to 53 and practice-squad formation are left off: a thousand camp
bodies a year would bury the moves anyone reads the wire for. Save
version 7, nothing to migrate.

**Measured.** About 1,500 lines a season, 22KB of compressed save; thirty
seasons is roughly 660KB on top of a 500KB save.

### Open
- The league's clubs refill their squads without a line on the wire, for
  the same reason as the cut to 53.
- The player screen does not yet show a man's own transactions.

## ADR-0xx — Safeties out-tackle corners

**Context.** Tackle credit still leaned on the secondary's corners: top 32
at each position averaged 112 at linebacker, 88 at corner and 74 at
safety over four seasons. The NFL has safeties well ahead of corners.

**Decision.** In the tuning table: the man in coverage makes 45% of
tackles after a catch, not 60%; safeties weigh 0.7 near the line (was
0.45) and 1.7 past it (1.5); corners 0.7 past it (0.9). Who is credited
has no effect on what happens on a play, so no calibration band can
move. The Hall of Fame's divisors, which are each position's leading
season, were re-measured: 140 at linebacker, 115 at safety, 95 at corner.

**Measured.** Four seasons, top-32 average: LB 108, S 91, CB 74, EDGE 45,
DT 47, which is the NFL's order and near its ranges. Hall of fame over
sixty seasons: corners 10 to 9, linebackers 9 to 13, safeties 8 to 11.

### Open
- ~~One tackler a play, no assists~~ Built; see the assists ADR below.

## ADR-0xx — Every game's box score, as SPEC 9.2 keeps them

**Context.** SPEC 9.2 is locked: box scores full for the last five
seasons, then team totals. The game kept the last game only.

**Decision.** `LeagueHistory.games`: every regular-season and playoff
game with its box score, filed as it is played. Each offseason drops
the player lines from seasons more than five back and keeps the team
totals. A box score does not say which side a player was on, and a man's
club today may not be the one he played for, so each game also keeps the
away side's player ids, resolved while everyone is still where he played.
The play log stays last-game-only (SPEC 9.2: current season, and the
game kept one). Playoff games carry their box to the archive and the
bracket drops it, so it is not saved twice. Save version 8, nothing to
migrate: games from before keep their scores only.

The schedule has a season picker and opens any archived game; the box
score shows both sides' leaders, and says so when only totals are left.

**Measured.** About 100KB of compressed save a full season, levelling at
about 500KB for five, and 25KB a season once compressed: thirty years
adds roughly 1.1MB.

## ADR-0xx — The rushing leader runs like the NFL's, and M8's rule goes

**Context.** M8 asked for the top two backs to clear 1,700 a year. The
only lever was volume, so the lead back took 86% of his club's carries:
404 in a median season, 444 at the top, at 5.7 a carry. The league
leader ran 2,290 in a median season and the record read 2,678 against a
real 2,105. Measured over eighteen league-seasons.

**Decision, with the user's agreement, replacing M8's rule.** The NFL's
figures: leader 1,459-2,027 over the last twenty years, second about
1,550, 300-370 carries, record 2,105. So the lead back's share drops
from 0.86 to 0.64 and the top two's from 0.95 to 0.90, his game cap from
27 carries to 23, and the elite back's edge per carry is compressed
(vision and break-tackle 0.032 to 0.027 a point, breakaway elusiveness
0.0011 to 0.0009). Spreading carries to worse backs cost 0.4 points a
game league-wide, paid back with the neutral carry at 3.63 yards instead
of 3.58, which holds yards a carry at the 4.40 the earlier ADR
calibrated.

**Measured.** Eighteen league-seasons: leader 1,425-2,065, median 1,719;
second 1,570; lead back 314 carries, 373 at the top. Bands on three
leagues at 4,000 games: 17 of 18 each, against 16, 17 and 16 before -
no band the baseline passed was lost, third down and one-score games
came back in, and the two leagues under the scoring floor were under it
before this change.

**Changed guard.** The season plausibility test allowed a leader up to
2,600; it now holds him to 1,200-2,300.

## ADR-0xx — Assisted tackles, so a leaderboard reads like one

**Context.** One credit a play. The NFL's leaderboards count combined
tackles - his own stops and the ones he helped on - so the sim's
linebacker leader read about 137 where a real one reads about 170.

**Decision.** A quarter of tackles have a second man in (tuning:
`assistShare`), chosen from the other ten by the same position weights,
and credited an assist. `StatLine.tackles` keeps its meaning - the man
who made the stop - so the hall of fame's divisors, the award scores and
production all read what they read before. `combinedTackles` is the two
together, and that is what the box score and a career sheet show.

**Measured.** Four seasons: 1,071 combined a club a season against the
NFL's 1,050; leaders 163-193 at linebacker (real 160-185), about 143 at
safety, 106 at corner, 76 at edge and 74 at tackle. Bands on one league
at 4,000 games: 17 of 18, as before; attribution cannot move an outcome.

### Open
- The NFL's split is 63% solo, 37% assists; this is 81/19, because the
  man who made the stop keeps his credit on an assisted play rather than
  both men reading as assists. The combined figure is the one that
  matches.

## ADR-0xx — Clubs sign men off each other's practice squads

**Context.** A squad player is a free agent his club happens to train, so
any club may sign him to its 53. Only the user's club did.

**Decision.** Filling a place opened by reserve, a club looks at its own
squad and the street first and reaches into another club's squad only for
a man who beats both by `ai.poachClearUpgrade` rating points: he costs a
53 place, and taking him leaves that club a hole. Eight points, swept
from five: at five the league signed 79 a season away, about 2.5 a club,
against the NFL's rough one to two, and the user's club lost five or six
a year.

**Measured.** Two leagues over two seasons each: 47-52 a season
league-wide, about 1.5 a club, and the user's club lost two or three -
which is the cost of leaving a good young player on the squad.

### Open
- Nothing stops a club signing a man away and cutting him the same month;
  the NFL's three-week guarantee for a poached squad player is not
  modelled.

## ADR-0xx — In-season form, and somebody to bench

**Context.** SPEC 10.1 wants benchings in the news, and nothing in the
sim could produce one: ratings do not move in season, so nobody played
his way onto the bench or out of a slump.

**Decision.** `Player.form`, -100 to 100, reset every season. It moves on
what a man did on Sunday against what his position is judged against - a
passer rating, yards a carry, yards a target, a defender's stops with
sacks and takeaways worth more - weighted by how much of the day he
actually had, and decays toward nothing in between. Positions a box score
does not measure carry no form: inventing one would bench a guard for a
game nobody watched.

Form is worth a few rating points and is read in exactly one place: the
copy of the player that dresses for the game, whose ratings are shifted
by it. So the depth chart that picks the eleven and every rating the play
resolution reads both see it, while the club's own records, its
valuations, its scouting and every draft board read his real ratings. A
slump cannot get a man cut or drop him down a board.

The news says when a club's best man at a position takes less than 40% of
the snaps somebody behind him takes.

**Measured.** One league over a season: form centres near nothing (mean
-1.2, sd 26, 5th to 95th percentile -47 to +49), and changes who starts
at 4.4% of starting places, 3.9 times a club a season. Three leagues:
points a game 20.13 to 20.08 with form on, yards a carry 4.407 to 4.388,
completion 64.3% to 64.5%; the passing leader eases from 5,163 to 4,788
and the rushing leader rises to 1,832, both still in range, because a
cold starter can lose his place. Benchings in the news: 15-24 a season
league-wide. Bands are untouched, and the standalone games they measure
carry no form.

**Also fixed.** Form moved the dice enough to surface the cap edge again:
one club-season in thirty finished at 43 active, under the 46 floor,
because a club at the cap cannot pay for replacements. A club below the
floor with no room now tears up the contract that saves the most per
point of what the man gives, up to two a week, which is what a real club
does rather than field 43.

### Open
- Contract disputes (SPEC 10.1) still have nothing behind them: no
  holdouts, no extension demands in season.
- Form is luck as much as anything, as it is in the NFL; it does not read
  a man's confidence, his coach, or the men blocking for him.

## ADR-0xx — Men who have noticed what they are paid

**Context.** SPEC 10.1 lists contract disputes and nothing produced one.
The offseason already knew who was underpaid (`PlayerIntent`); the season
did not.

**Decision, the user's rules.** A man with three accrued seasons behind
him, good enough to have leverage (74), whose market has moved past 1.8
times his cap hit, asks his club to fix it - weekly odds scaled by his
ego and damped by his loyalty, between weeks 3 and 15. Rookies on slotted
deals are underpaid by construction and never ask.

The league's own clubs answer the same week: they pay the market rate for
as long as his age says (four years under 28, three to 30, two after) if
they have the cap room, and refuse if they have not. The user's club is
asked and left to decide, on a Demands screen and flagged on the Hub.
Waiting costs him two morale a week down to a floor of 50, because what
sours a man past that is being told no, not being kept waiting.

Refused: eighteen morale, which is worth a little of every rating he has
through the morale term, and the spring finds him with less patience
(`REFUSED_NERVE` 0.95 on top of the money grievance, which
`MONEY_PATIENCE` damps hard - at 0.25 the refusal was invisible).

**Measured.** Ten leagues, a season each: 8-18 demands settled a season
league-wide and 0-3 refused, which is the volume of extension and holdout
stories a real season carries. Of ten refused men, four carried the
grievance into the spring and two asked for a trade. Prices come from the
same pricer for the headline, the screen and the deal, so a man is not
told he is worth $8.1M and then signed for $9.3M.

### Open
- No holdouts: a refused man plays on. The user asked for morale and a
  trade request, not missed games.
- ~~The club cannot offer anything but the market rate~~ Haggling and
  in-season restructures are built; incentives are not.

## ADR-0xx — What a device pass found

**Context.** The hub's news block, the roster export, the Demands screen,
History and the draft room had all been built and tested and never seen
on a phone.

**Walked on a Galaxy S-series**, a 2026 season played through to the
offseason. Working as built: the export writes a 13KB CSV of 52 men and
every rating column to Downloads and says so; Demands reads "Nobody is
asking" with the club's cap room; the draft room opens on the clock with
a board of 484 prospects, each a range rather than a number; the news
block files benchings.

**Four faults, fixed.**
- Half the hub's links sat off the right edge of the phone in a
  horizontally scrolling row, with nothing to say they were there and the
  scroll snapping back every time the hub reopened. Demands, Scouting,
  History, Tuning and Design were effectively undiscoverable. The row
  wraps now: all nine show at once.
- The news block took the five newest, and benchings are filed last, so
  a week's results and milestones were buried under four men losing
  their places. It now carries no more than two of any one kind.
- An injury of 25 weeks read "Out 25 weeks" where 30 read "Out for the
  season". Anything longer than the season has left, playoffs included,
  now reads as out for the season.
- History said "No seasons behind you yet" to a club that had just
  finished 10-7, because a season joins the record when the year turns
  over. It now says the year is not in the record yet and what to do
  about it.

**On driving the user's phone.** Twice during this work the screen
changed hands mid-pass - an ongoing media card, then another app - and
blind taps would have landed in the user's own apps. The driver checks
that the app owns the screen before every tap and brings it back to the
front rather than tapping into anything else. The save was copied off the
phone before each pass and restored byte for byte after, checksums
matched, and the CSV the export test wrote was deleted.

## ADR-0xx — Five slots and three autosaves, as SPEC 9.1 locks them

**Context.** SPEC 9.1 is locked on five user slots and a rolling autosave
keeping the last three, written on every phase advance. The game kept one
file, `dynasty.sav`. During this project an uninstall took a dynasty with
it, which is exactly what the locked line is there to prevent.

**Decision.** `slot-1..5.sav` are the user's, `auto-1..3.sav` are the
game's. A slot is written after every advance, as before; an autosave is
written whenever the phase turns over - the playoffs starting, the
offseason running, a new year - and the rotation overwrites whichever of
the three is oldest, so three phases of history are always behind you.

The single save older builds wrote is adopted as slot 1 on first run, once
and only into an empty slot: a dynasty carried over from before slots
existed is not something to lose. The start screen lists what is on the
phone by slot, club, year and week rather than offering one "load"
button, and a Saves screen behind the hub loads, copies, overwrites (with
a confirmation) and deletes, and restores an autosave into the slot being
played.

**Measured.** Unit tested: a slot keeps and describes what it was given;
the rotation keeps the three most recent and drops the oldest; the legacy
save is adopted once; an unreadable file is listed as unreadable rather
than hidden. End to end through the store: a week inside the season
writes no autosave, the playoffs turning over writes one, the offseason
another. On a phone: the legacy save became slot 1 and loaded, the Saves
screen reads "Indianapolis Speed, 2026 week 1" with the date, and a copy
into slot 2 carried play into that slot.

### Open
- Autosaves land three or four times a season, which is what a phase
  advance means. A bad trade in week 5 is not what they are for.
- No way to name a slot; it is club, year and week or nothing.

## ADR-0xx — Haggling, and what a man will take to stay

**Context.** A demand had two answers: the market rate or no. A club with
a player who likes it there should be able to get him for less, and an ego
should cost every dollar.

**Decision.** Every man has a reservation price, a share of what the market
says he is worth: 0.90 at the middle, up 0.10 for a full ego, down 0.14
for full loyalty, with a small quirk derived from his id so two men with
the same traits are not identical, floored at 0.74. It is not published.
A club offers 90% or 80% of the market and finds out: above his floor he
signs, and below it he turns it down, his agent names the figure he will
not go below, and the demand stays on the desk. A rejected offer costs him
five morale where being refused outright costs eighteen, and a man who
signs for a discount is a little less delighted about it than one paid in
full.

So the second offer is informed - the price of learning is one snub - and
a club that reads its own players right saves money on the men who want to
stay.

**Measured.** Tested: a proud man's floor is above a loyal one's; an offer
under the floor leaves the demand pending, costs morale and names the
figure; an offer at the floor signs for less than the market; and a club
without the cap room cannot offer at all.

### Open
- The league's own clubs still pay the market rate or refuse. They do not
  haggle, because a club that reads its own player wrong should lose money,
  and the AI has no read to get wrong yet.
- No incentives, no guarantees to trade against: the only lever is the
  annual figure.

## ADR-0xx — A season of the user's play-by-play

**Context.** SPEC 9.2 keeps play-by-play for the current season. The game
kept the log of the last game only, so a box score opened from the
schedule showed its totals and leaders and nothing of how it went.

**Decision.** An archived game carries its play-by-play when it is one of
the user's, regular season and playoffs, and the year turning over drops
every log along with the season. Every club's logs would be the letter of
9.2: about forty thousand plays a season, re-encoded with every weekly
save, for games nobody opens. The user's are the ones anyone reads.

**Measured.** A season's worth is 2,603 plays and 57KB of compressed save,
about 136ms added to an encode on a desktop JVM. Tested: every one of the
user's games keeps its log, nobody else's does, the bracket does not keep
a second copy of the playoff logs, and the year turning over clears them.
Save version 12, nothing to migrate.

### Open
- Other clubs' games open to their box score only.

## ADR-0xx — The user's club decides its own expiring contracts

**Context.** The draft was the only offseason decision the user made.
Re-signing and the franchise and transition tags were run for the user's
club by the same logic as everyone else's, so a dynasty's most important
calls about its own players were watched, not made.

**Decision, the user's choice of scope.** The offseason stops before
re-signing (`OffseasonEngine.runToContracts`), the way it already stopped
before the draft, and hands the user his expiring players: what the market
says each is worth, what he asks his own club for and for how long, and
what either tag would cost. For each: re-sign at his asking price, put
the franchise or the transition tag on him (one tag, as the CBA allows),
or let him go to market. Then re-signing and tags run for every other
club - skipping the user's - and free agency and the draft go on as
before.

Every price is the one the league's clubs are quoted: the asking price is
`Extensions.asking`, the term `MarketValue.termFor`, the tags the CBA's
top-five and top-ten averages at his position. And the screen starts from
what the club's own front office would do - the same logic on the same
random draws - so tapping straight through is the offseason the AI would
have run, and the user changes only what he disagrees with.

**Measured.** Tested: the user's expiring men are listed with prices; a
man re-signed stays and the men let go are not quietly re-signed by the
league's logic; only one tag is honoured and a franchise-tagged man stays;
leaving it to the league produces exactly the offseason it always did;
and taking the front office's advice keeps exactly the same men. The
30-season stability gate runs through the new pause and passes.

### Open
- Matching a transition-tagged man's offer is automatic when the club has
  the room; there is no "do you match?" for the user. If nobody bids he is
  a free agent - there is no tender, which the CBA has.
- Free-agency bidding is still the AI's for the user's club.

## ADR-0xx — Every way to write a deal, and which one to write

**Context.** The user asked for the game to show the best option on every
contract decision and to lay out all the signing and restructure options.
Each decision offered one fixed deal, and the offseason's advice was the
front office's own call.

**Decision.** `ContractOptions` writes every deal a man will sign: his
term and a year either side - a year shorter costs 5% more a year because
he gives up security, a year longer 3% less because he gains it - each in
three structures. Standard is the engine's usual deal. Cap-light puts the
minimum in salary this year and a bonus spread over the deal: least cap
now, most later, most dead money. Pay as you go is flat salary and no
bonus: more cap now and no dead money beyond the guarantee. They are
written out explicitly: this engine back-loads salary and spreads bonus
evenly, so a bigger bonus alone makes year one dearer, which the first
version of this did, labelled cap-light, until a test caught it. A
one-year deal is offered one way, since the bonus lands this year however
it is written. Restructures come in three sizes - a quarter, half, or all
of what can move.

The advice is a value judgement, not the front office's call. That call
comes from one GM's habits, and on a phone it told a club with $120M of
room to let a 24-year-old starting corner walk at his market rate. The
advice asks whether he starts for this club, whether his ask is within 8%
of his market, whether he is under the age a club pays through (31), and
whether it fits - down the list most valuable first, so the one tag goes
where it suits and the room runs out where it would. A veteran starter
gets a franchise tag if it costs no more than 115% of his ask; an
overpriced young starter gets the transition tag; cheap depth is kept up
to three minimums; the rest go to market. The deal is then written for no
longer than his prime and structured for the room: cap-light when tight,
pay as you go for a man near the age line, standard otherwise. The front
office's call is shown beside the advice where they differ.

In season, a demand's advice is the same deal logic at his price, or how
to make the room if nothing fits, with the haggling tip beside it. A
restructure's advice is to leave it when there is room - the money moves
into years he may not be worth it - and otherwise the smallest size that
makes the room comfortable.

**Measured.** Tested: the structures order as their labels say, cap now
and dead money both; a shorter deal costs more a year and a longer less; a
chosen deal is signed as chosen, in the offseason and in season; one tag
at most is advised and the advice fits the cap; a young starter at his
market rate is advised to re-sign; the demand advice pays when there is
room and says to restructure when there is not; and the restructure
advice says leave it with room to spare. On a phone, on copies of the
user's save: the corner's advice read re-sign for five years at $21.8M,
with "your front office would have let him go" beneath it.

## ADR-0xx — The moves are the user's, and the front office only if asked

**Context.** The user: "you should have the first option to make all the
moves yourself with the option to let the ai make the decisions for you."
The expiring contracts screen started from the advice with every decision
pre-set, so tapping through made the moves for him; the draft room had it
the right way round already, with "Let the scouts pick" beside his own
choice.

**Decision.** Every decision screen is manual first, with the AI as an
option the user takes rather than a default he has to undo.
- Expiring contracts start undecided. The advice sits beside each man
  with "Take the advice"; at the top, "Take all the advice" or "Let the
  front office decide", which hands the whole thing to the league's own
  logic exactly as it runs every other club. Moving on needs every man
  decided, or "Let the undecided go to market".
- A contract demand gains "Let the front office answer": the league's
  rule, pay the market rate if there is room and refuse if not.
- In-season roster moves stay the user's. "Let the front office fill
  injured places", on the Free agents screen, hands them over - filling
  places reserve opens, topping up the practice squad, cutting the
  stopgap - and can be switched back. It is saved with the dynasty.
- The draft room already worked this way and is unchanged.

**Measured.** Tested: the front office answers a demand by paying with
room and refusing without; a user's injured places wait for him with the
roster left to him and are filled when handed over. Save version 13.

### Open
- ~~Free-agency bidding is still the front office's~~ Built; see the
  free-agency ADR below.

## ADR-0xx — The user bids in free agency

**Context.** The last offseason decision the league still made for the
user was who to chase in free agency. He asked for the gap to be fixed:
his moves first, the front office only if asked.

**Decision.** The offseason stops a third time, after re-signing and tags
and before the ten days (`FreeAgencyPause`). The user sees the market -
every man's scouting read, what he is worth, what he opens asking, the
club he left - with advice on each: bid his market if he would start for
this club and it fits, one year if he is past the age a club pays
through, and pass on anyone who would sit, since the draft buys depth
cheaper. He puts standing offers on whoever he wants - under, at, or over
the market, for one to five years - or hands the whole thing to the front
office, which bids for him by the league's logic exactly as before.

A standing offer is bid every day the man is unsigned and the club can
still pay it, and each offer on the table counts against the room for the
others, so a club cannot spend its cap twice. The offers compete under the
auction's own rules: a man takes the most appealing package that clears
his asking price - money first, then winning and fit - and asks about
4.5% less each day he waits. So an offer at market can lose him to a
contender on the first day, and one under market can land on the seventh.
The draft room opens with word of what happened: who signed, who went
where and for how much, and who is still waiting.

**Measured.** Tested: the market is listed with worth and asking price
for every man; an offer of half again his market signs him, and one of
half his market never does; offers on twenty men at everything the club
has land one at most and never take it over the cap; handing it to the
front office reproduces the old offseason exactly; and making no offers
still leaves a full roster, filled with camp bodies.

### Open
- ~~The cut to 53 at camp is still made for the user by the league's logic.~~
  Built; see the camp ADR below.
- ~~No haggling in free agency~~ Built; see the next ADR.

## ADR-0xx — Talking to a free agent's agent before the market opens

**Context.** Free agency's offers stood for ten days, take it or leave
it. The user asked for haggling.

**Decision.** Before the ten days, the user may talk to any free agent's
agent. An offer at or above what he will take signs him on the spot,
before anyone else can bid; one under it is turned down, and his agent
names his floor. He hears two offers and no more - after the second no
he is done talking and goes to market, where a standing offer still
counts - so learning a floor is worth something and is not free.

His floor starts at 1.02 of his market, since the auction would pay him
about that; an ego adds up to 0.10, a star worth 12% of the cap adds
0.06 because stars test the market, loyalty takes up to 0.14 off but
only for the club he played for, a club that won 60% takes 0.05 off,
and a small quirk from his id keeps identical men apart. It never goes
below 0.90 or above 1.25. A pre-market signing guarantees 45% and is
recorded with the offseason's signings.

**Measured.** Tested: an offer over his floor signs him before the
market and he stays signed through the ten days; under it, his agent
names the floor, and after two offers he stops talking, even at his
floor; a loyal man coming home takes less than a proud stranger.

## ADR-0xx — The user makes his own cut to 53

**Context.** Camp was the last offseason decision the league made for the
user: after the draft, the fill signed undrafted camp bodies and the cut
took every roster to 53.

**Decision.** A fourth pause, after the draft (`CutdownPause`). The user
releases whoever he wants at the dead money his contract says and signs
off the street at a year of the minimum, and cannot leave camp outside
46-53. Suggestions are the league's fill and cut run on his roster alone,
without the fill's random tiebreak; "Let the front office fill and cut"
runs camp exactly as before. A position every club must field (QB, K, P,
LS) left empty is filled off the street for him, deterministically, or
with a camp body from a split of the offseason's random stream.

No new cut logic: `enforceRosterLimit` already leaves a roster within 53
alone, so the user's legal cut stands, and one over 53 is finished by the
league's logic as any club's is.

**The random stream.** The camp fill draws from one shared stream as it
walks the clubs, so leaving the user's club out of it changes the draws
for the clubs after him - their camp bodies can differ from the automatic
path's. That is the manual path only; the automatic path is unchanged and
a test pins it to the old offseason player for player. No RNG code
changed.

**Measured.** Tested: the front office's camp is the old offseason; the
suggestions make a legal 53 with every must-field position; a man cut is
gone at his dead money and a man signed stays; an empty kicker room is
filled; a camp left over 53 is cut to exactly 53. No calibration band can
move: the bands play standalone games, and the 30-season gate runs the
automatic path.

## ADR-0xx — One store for the process, so a rotation keeps the offseason

**Context.** The offseason's pauses - contracts, free agency, the draft
room, camp - live in memory by design (ADR on the contracts pause). The
store holding them was created per Activity, and Android rebuilds the
Activity on a rotation, a dark-mode switch or a font change. On a phone
that had turned on its side, the whole offseason in hand - contract calls,
offers, picks and the cut - went, and the hub read "Start the offseason".

**Decision.** `DynastyStore.forContext` returns one store for the process,
built on the application context. A rebuilt Activity finds the dynasty
already in hand and does not reload it from the save, and the screen the
user was on is kept with `rememberSaveable`. The limit is unchanged and
stated: if Android kills the process in the background, the pauses go,
and the offseason runs again from the save.

**Measured.** On a phone, the Activity was torn down and rebuilt in the
same process (same pid) with the user in camp; the hub read "Back to camp"
where it had read "Start the offseason".

## ADR-0xx — Headlines draw their wording from a stream of their own

**Context.** News headlines moved from Kotlin into `narrative/news.json`
with ten variants a story, so something random has to pick the variant.
Drawing it from a stream the sim already uses - the disputes stream, say -
would shift every later draw on that stream, and a change of wording would
change which veterans ask for money.

**Decision.** The variant is picked from splits kept for wording:
`headlines|year|week` for the week's news, `headlines|poached|year|week`
for practice-squad signings, and `split("headlines")` of the disputes
stream for dispute stories. A split is a pure function of its parent's seed
and its label, so adding these moved no existing stream.

**Measured.** A fingerprint of a full season and offseason on seed 91 -
results, stats, transactions, every player's state, and which stories were
filed about whom - hashed the same before and after
(`7efa2f187b3448553353cfbe3fe9941f`). No calibration band can have moved.

## ADR-0xx — Each game words its plays from a stream of its own

**Context.** The play-by-play moved from Kotlin into `narrative/plays.json`
with 8 or 9 ways of saying each line, so every snap now picks a variant.
Picking from the game's stream would shift every later draw in the game,
and every result in the league, whenever a template was added.

**Decision.** `GameSimulator` takes `split("narration")` of the game's
stream once, as it already does for injuries, and hands it to every snap
through `PlayContext.narration` and to kicks and punts. A snap simulated
on its own, with no game around it, words itself from
`split("narration")` of the stream it was given. That split is pure, so
the same seed replays the same line, and the play's own draws are
untouched.

**Measured.** A season and offseason on seed 91, serialized at every one
of its 20 states with its 34,649 lines of text removed, hashed
`4fe3b6a0624abc364b9478c7ad58453a` before and after. No calibration band
can have moved. A test plays 500 snaps twice with different narration
streams and finds them identical apart from their words.

**Found on the way.** The game log highlights turnovers by looking for
"intercept" and "fumble" in the line, and the old lines said "picks off"
and "is stripped", so no turnover was ever highlighted. Every turnover
template now uses those words, and a test holds them to it.

## ADR-0xx — Win probability lives with the recap, not in the tuning table

**Context.** SPEC 10.4's recap picks the plays with the biggest
win-probability swing, and there was no win-probability model. AGENTS
rule 4 puts simulation coefficients in `TuningTable`.

**Decision.** `engine/narrative/WinProbability` is a small closed-form
model (a normal final margin: score plus field position, spread by the
square root of the time left) with its four numbers as named constants in
the object. They are not simulation coefficients: the model reads a game's
log after the game is over and changes nothing the sim does, the same way
`NewsDesk`'s thresholds for a big game sit in `NewsDesk`. Putting them in
`TuningTable` would make them look tunable when changing them moves no
statistic. If the sim ever calls plays off win probability, the model
moves into the tuning table then.

**Consequences.** Recaps need only the play log, so no save change. They
exist for games whose plays are kept (the user's, this season, SPEC 9.2).

## ADR-0xx — A man the user's club lets go comes back only if he is willing

**Context.** A test promised that whoever the user lets walk is gone. It
held by luck: when the front office ran the user's free agency, nothing
stopped the late roster fill signing a let-go man back, and a change in
the market (cap carryover) made it happen. The user's rule: he can come
back if there is nobody better, he is still available, and he is willing.

**Decision.** The offseason records the men the user's club let go
(`OffseasonState.letGo`), whether the user decided or its front office
did - so taking the front office's suggestions is still the offseason it
would have run, as another test pins. The auction drops that club's bids
for an unwilling man, the fill passes over him, and before the market his
agent will not talk. Willing is trait-only: loyalty at least matches ego.
Price is left to the rules that already set it, so a loyal man still
gives his old club the hometown discount SPEC 8.3 describes. The rule was
put to Jev, which chose applying it to the user's club whoever decides
(0.98) over user decisions only or every club.

**Consequences.** The 31 league clubs are unchanged, so no calibration
band moves. The rule lives in `Extensions.willingToReturn`.

## ADR-0xx — Cap carryover is written onto the league the offseason works from

**Context.** SPEC 8.1 lists carryover of unused cap room, and
`TeamFinances.carryover` existed but was never set. About forty places
work out a club's room, most of them inside the offseason, which spends
next year's cap long before the new year's books are written at the end.

**Decision.** `CapManagement.carryForward` reads each club's room from
the books as the season closed and writes it, times
`ai.capCarryoverShare`, onto the clubs of the league the offseason runs
on, before any phase counts money. Every room calculation then reads it
from the club, and `CapManagement.spaceFor` takes it as a parameter with
no default, so a caller cannot forget it and the compiler lists them all.
The new year's books keep it, so in-season room counts it too. It is not
folded into dead money, which grows through the spring and is halved at
the new year.

**Measured.** 15 seasons each on seeds 91 and 7, before and after: win
spread 2.89 to 2.83 and 2.61 to 2.62, champions 9 and 12 either way,
average room at season's end $31M to $37M and $35M to $40M, free-agent
spending $3.21B to $3.58B and $3.14B to $3.50B a year. A club that does
not spend banks room: the most any club held by year 15 rose to $211M
and $257M. The in-game calibration bands are per-game and do not read the
cap; the 30-season stability test passes.

**Consequences.** Save version 15; old saves carry nothing until their
next offseason. Found on the way: the roster fill could sign back a man
the user let go (see the ADR before this one).

## ADR-0xx — The user sets a match ceiling for a transition tag before the market opens

**Context.** A transition-tagged man's club may match any offer sheet.
Every club matched automatically whenever it had the room - the user's
included, against the rule that he makes his own club's calls. The
auction runs its ten days in one go, so there is no moment mid-auction to
ask him.

**Decision.** Before free agency the user sets, for each of his tagged
men, how far he would match: up to his market, 10% over, 25% over, or
never, with a recommended ceiling. `FreeAgency.run` takes the ceilings
(`matchUpTo`) and matches the user's man only at or under his. Handed to
the front office, the club matches whatever fits, like every other. Put
to Jev against a plain match/let-go toggle and against pausing the
auction at each offer sheet: it chose the ceiling (0.91). A toggle
commits him to an offer he has not seen; a pause means rebuilding the
auction into resumable days.

**Consequences.** The league's clubs are unchanged, so no calibration
band moves. The presets are advice, so they are constants in
`FreeAgencyPause`, not tuning fields.

## ADR-0xx — A roster file's coaches replace the generated men, who leave the league

**Context.** A roster file can now name a club's staff (SPEC 9.4). The
generated coach a real one replaces could stay in the league out of work -
where the carousel would hire him the first time a club fires its coach -
or leave it. Real staffs also do not match the game's slots one to one: one
defensive line coach often coaches the edge and the interior, and a head
coach may call his own plays.

**Decision.** A replaced generated coach is removed from the league. A
person the file names in several slots is one coach, holding every job's
tendency levers. What the file leaves out of a coach - ratings, age,
contract - comes from the generated man he replaced, so a staff given only
names rates on the curve the sim was calibrated on (mean 65). A slot the
file leaves empty keeps its generated coach, with a note, as a short
position keeps generated players.

**Consequences.** A league from a file with full staffs has no invented
coaches until the carousel hires its first. Development reads a position
coach per group, so a coach in two groups develops both at his rating; no
calibration band moves from the import itself.

A head coach's side of the ball is his scheme's: a defensive scheme makes
him a defensive head coach. No new field is needed, because the coach model
already has one scheme and the catalog knows each scheme's side, so there's
no save-format change. The carousel reads a defensive head coach against
the defense's fit, gives his scheme to the defense through a coordinator
from his tree, and hires the best offensive coordinator it can find. A
club always runs its coordinators' schemes. Generated head coaches stay on
offense, so a generated league plays exactly as before. (First released
with the head coach carrying the club's offense instead; changed once
the carousel could hire around a defensive one.)

## ADR-0xx — Tied games go to overtime: the playoffs' rules and the regular season's

**Context.** The engine had no overtime. A playoff game that ended level
was replayed from kickoff, up to three times, and then went to the home
club by three. A simmed game could live with that, but a game the user had
just called could not: its result would be a different game.

**Decision.** Playoff games (`GameSimulator(overtime = Overtime.PLAYOFFS)`) play
overtime under the NFL's postseason rules: fifteen-minute periods, both
clubs get the ball once, then sudden death. Overtime runs only after
regulation and only then draws from the game's stream, so no game decided
in four quarters changes. The period length and timeouts are the rules,
not tuning, so they are constants rather than `TuningTable` fields. Each
overtime period opens with its own toss and kickoff. The NFL carries play
over from the first period to the second, but tracking that would need a
change of ends that nothing else in the sim models.

The regular season plays the NFL's overtime too: one ten-minute period
under the same possession rules (the NFL adopted them for the regular
season in 2025), and a tie if it is still level. The two rule sets are the
`Overtime` enum rather than a flag, and the calibration harness plays
regular-season overtime so the bands measure the games the league plays.

**Consequences.** A game decided in four quarters is unchanged. A game
level after four quarters is now settled in overtime: in the playoffs
instead of by a replay, and in the regular season it is usually no longer
a tie. Regular-season points, plays and close-game shares move slightly
with it; the commit adding regular-season overtime lists the bands that
moved. The old replay rule is kept only as a safety valve past ten playoff
overtime periods.

## ADR-0xx — Close games come from how coordinators finish them, not from the clock

**Context.** Regular-season overtime moved "games decided by 3 or less" out
of its band (0.17 against 0.18-0.26), because ties had been counting as
close games. Measured over 8,000 games, the gap sat almost entirely at
exactly three points: 8.5% of games against about 15% in the NFL. The
margin with five minutes left was almost the same as the final margin, so
the end of the game hardly moved results.

**Decision.** Coordinators now finish games the way NFL coaches do: a
leading offence kneels when it can run out the clock, a kick that ties or
wins goes up on any down when the clock is out, a club within a field goal
late kicks instead of going for it, and a defence two scores up in the
fourth quarter plays prevent. Only the prevent threshold is a new tuning
field (`gameFlow.preventLead`). The rest are rules, and take their timing
from the existing clock tuning.

Late-game clock management (the two-minute warning, a trailing offence's
hurry-up, the trailing club's timeouts) was tried first and set aside. It
added about three plays per team per game, pushing plays and yards out of
their bands, and over 8,000 games moved the close-game share by nothing
measurable (0.174 to 0.173). SPEC 5.10 still describes it as the target.

**Consequences.** Close games are 0.21 over 2,000 games, and all 18 bands
pass. Points per team rise from 21.3 to 21.8 and yards per attempt from
7.31 to 7.37, both well inside their bands. The tuning table gained a
field, so saves go to version 20 with nothing to migrate.

## ADR-0xx — Late-game clock management, paired with a slower pace between snaps

**Context.** SPEC 5.10 always called for the two-minute warning, a
trailing offence's hurry-up and timeouts, but the engine had none of them.
When they were tried while tuning close games, they added about three plays
a team to every game and pushed plays and yards out of their bands, so they
were set aside (the ADR above).

**Decision.** Build them (`sim.ClockManagement`) and pay for them where the
time went. The base runoff between snaps rises from 31 to 34 seconds after
a run, and from 28 to 31 after a completion. That's nearer the NFL's
running-clock pace, and it gives back the plays the late-game clock adds.
A chasing club spends timeouts only on a running clock, and never on a
snap the two-minute warning stops anyway. The victory formation now
counts the defence's timeouts.

**Consequences.** All 18 bands pass over 2,000 games. Plays per team are
65.5 -> 64.9, yards 352 -> 349, points 21.8 -> 21.6 and close games 0.21 ->
0.20. Every game's clock changes, so no game plays out as it did before,
and golden runs move. Old saves take the new pace unless their user moved
a runoff slider.

Out of bounds followed on the same terms: its own random stream, so the
plays don't move, only the clock. The pace slows once more, to 35 and 32
seconds, to pay for it. Over 2,000 games, plays per team are 64.9 -> 65.4,
yards 349 -> 351, points 21.6 -> 21.8, and close games 0.20 -> 0.23: going
out of bounds late is how a chasing club gets one more snap, and one more
chance to draw level.

## ADR-0xx — Weather, and a fair-weather baseline

**Context.** SPEC 5.10 described weather (by stadium, month and region,
affecting deep passing, kicking and fumbles), but the engine had none.

**Decision.** Weather is data, not code: a climate region on each stadium
in `teams.json`, and a table by region and month in `climate.json`. Each
game draws its weather once from its own stream, so the plays don't move
and only the conditions do. A dome draws nothing and plays exactly as a
game did before weather existed. The effects are `TuningTable.weather`
fields.

The calibration bands are NFL numbers, and the NFL plays in its weather.
So the fair-weather baseline rises to meet them: base completion 0.82 ->
0.83 and base fumble rate 0.0125 -> 0.0115. With weather at the old
baseline, completion read 0.63 and fumbles per carry 0.013, at the edges
of their bands.

**Consequences.** All 18 bands pass over 2,000 games at the same readings
as before weather: completion 0.64, fumbles 0.012, yards per attempt 7.36,
points 21.5, close games 0.23. Cold, windy and wet games now play
differently from warm ones, and domes differ from open stadiums. Old
saves get their stadiums' regions back by migration.

## ADR-0xx — In-game adaptation shifts tendencies, not outcomes

**Context.** SPEC 5.4 called for coordinators who adapt during a game, within
±0.12 scaled by the head coach's adjustments rating, so that a predictable
coordinator gets punished. Nothing did; the rating only mattered when the
carousel judged a coach.

**Decision.** Adaptation moves what a coordinator calls, never what a play
does: the defence's blitz rate and the chance of a man more or fewer in the
box, and the offence's pass rate, each from what the other side has shown.
The punishment then comes from the play resolution already in the sim, a
loaded box against the run and pressure against the pass, rather than from
a bonus invented for it. The reads (a neutral pass share of 0.57, the gains)
are tuning fields. The offence's read of the box was set at 1.0: at 1.5,
third-down conversion read 0.417, near the top of its band.

**Consequences.** Over 2,000 games, points per team are 21.5 -> 21.7,
third-down conversion 0.411 -> 0.415, and close games 0.23 -> 0.24. All 18
bands pass. A run-heavy offence gains fewer yards a carry against a staff
rated 100 than against one rated 0 (AdaptationTest), and the adjustments
rating now matters every Sunday.

## ADR-0xx — The user's trades are answered by the league's own trade logic

**Context.** Computer clubs traded with each other in the offseason
(ContenderTrades), but the user could not trade at all. SPEC 8.4 has AI
clubs evaluating offers; the user asked for trades in the season (to a
deadline) and in the offseason (so draft picks can move).

**Decision.** One desk (`season.TradeDesk`) works on a book: who is where,
the picks, the dead money, the draft order and the roster limit. That lets
the same rules serve the season, where the book comes from the league, and
the draft room, where it comes from the offseason's state. The other club
values the deal exactly as a computer club values a star trade: its own
timeline, the same pick chart, and the same seller's margin. So the user
can't win trades the league's own clubs would refuse each other. The
offseason window is the draft room before the first pick. After that, a
traded pick could be one the user had already made.

**Consequences.** A trade can't leave either club over its roster limit
or push a club with room over the cap. When an offer falls short, the
screen says so in picks ("about a round 4 pick short"), not in the value
units. Computer clubs don't yet bring offers to the user, and don't trade
with each other in the season.

## ADR-0xx — Banter is drawn from the conversation, not from the sim's stream

**Context.** Trade answers and contract replies were bare facts ("Boston
want more for it", "his agent says he will not go below $4.0M"). The user
asked for GMs and agents to talk. A trade answer is recomputed every time
the trade screen redraws, and a contract offer is a pure function of the
league. Neither has a place in the dynasty's random stream.

**Decision.** `narrative.Banter` picks a line from `narrative/banter.json`
with a stream of its own: the league seed split by the line's key, the
speaker, and the offer. The same offer gets the same answer on every
redraw, the sim's streams are untouched, and nothing is saved. The facts
stay in the note, with the quote after them, so a reader who skips the
flavour misses nothing. Agents are a fixed pool of 40 generated names,
chosen by player id, and not a new field on the player, so the save format
does not change. A GM's tone comes from his aggression, the trait that
already sets how hard his club bids.

**Consequences.** No calibration band can move: the lines are chosen after
the outcome is settled and from a separate stream. Computer clubs' own
haggling writes quotes that nobody reads, a few string builds a week.

## ADR-0xx — Clubs call with offers computed each week, not stored

**Context.** Only the user started trades. SPEC 8.4 has computer clubs
proposing too, and the trades ADR left "computer clubs bringing offers to
the user" open.

**Decision.** `season.TradeOffers` works out the week's calls from the
dynasty as it stands. Which clubs call comes from the dynasty seed split by
season and week, a stream nothing else reads. A call is a deal the calling
club would take at the trade desk, so taking it is making it there, with no
second set of rules. The club offers the most it would still take, and
calls only when that is worth at least the man to the user's own club. A
call is therefore never a lowball, and one comes only where the two
timelines disagree enough for both to gain. Calls are not saved. The same
dynasty gives the same calls, and a trade made changes the dynasty, so the
calls are worked out again. A call the user turns down is hidden in memory
for the session.

**Consequences.** No sim change: until the user takes a call, nothing moves.
The save changes only for the three new tuning fields (step 25, nothing to
move). Across three test leagues, about 16 calls come before a deadline,
most of them player for player. They are mostly for linemen, whose value to
a club with a hole is highest. Working out a week's calls takes about 60 ms
on a laptop, so the app does it off the main thread.

## ADR-0xx — Deadline deals reuse the offseason's contender trades

**Context.** Computer clubs traded with each other only in the offseason, so
a season's rosters moved only for injuries and signings. The trades ADR left
in-season trades between computer clubs open.

**Decision.** At the deadline, before week 10's games, `DeadlineDeals` runs
ContenderTrades on the season's active rosters. It uses the standings so
far, and values next year's picks toward next year's draft.
ContenderTrades gains three optional parameters:
- a roster limit, so a deal leaves both clubs within the 53;
- a club it never deals for (the user's);
- the draft year that picks are valued toward.

The offseason calls it as before, with none of them, so the offseason is
unchanged. Running once, at the deadline, keeps it the event it is in the
NFL rather than a weekly churn.

**Consequences.** The sim moves for the clubs that trade: week 10 on is
played with the new rosters. GameCalibration plays single games on a
generated league and never passes a deadline, so no calibration band can
move. A deadline brings 0 to 2 deals across the test leagues. NewsKind
gains TRADE, which is save step 26, nothing to move.

## ADR-0xx — The trade block raises the chance of a call, and never lowers the price

**Context.** SPEC 8.4's Trades screen listed a trade block as not yet built.
Clubs already call with offers (TradeOffers).

**Decision.** The block is a set of the user's player ids saved on the
dynasty. It does two things:
- A club that a blocked man would help rolls against a higher call chance.
- It needs him only to be an upgrade, not a clear one.

The offer itself is built by the same rules as any call, so the club still
pays at least what he is worth to the user's club. Saying a man is
available tells the league to call; it doesn't tell it he's cheap.
Membership is filtered by who is still on the user's club at the time it's
read. That way a trade or a release never leaves a stale entry to clean up
in the save.

**Consequences.** Save step 27 (nothing to move), version 28, for the field
and two tuning values. With nobody on the block, the calls are exactly what
they were: every club rolls one number either way.

## ADR-0xx — A holdout is a demand raised at camp

**Context.** SPEC 10.4 lists the holdout as a storyline beat. The game
already has in-season contract demands, with their case, their answers,
their waiting cost and their Demands screen, and spring wishes where a man
says he wants paying.

**Decision.** A holdout is that same demand, raised as the new season opens
by a man who said in the spring he wanted paying, has the demand's case,
and has the ego to stay away. It goes through the same raising and
answering code: the in-season demand path was extracted into
`ContractDisputes.raise`, which both now use. What makes it a holdout
rather than a demand is the camp he missed: a form and morale cost.
Form is the existing Sunday modifier that wears off with play, so a
holdout costs a few points early in the season and nothing permanent. He
reports for week 1 whatever happens. The game has no games-missed
holdouts, because a man sitting out regular-season games would need
roster and pay rules the sim doesn't have.

**Consequences.** One to four a year across five test leagues, 2.4 on
average. (First reported as one or two: that count matched "camp" in the
headlines, and two of the eight holdout headlines don't contain the
lowercase word. Counted by the form a holdout loses, it is 1-4.) It moves the
sim for those men's early weeks. GameCalibration's single games never pass
through an offseason, so no calibration band can move. The new season no
longer opens with empty news: camp's holdouts and answers are week 1's
news. Two tests that asserted the empty opening were changed to assert
what they meant: nothing from last season carries over. Three tuning
values make save step 29 (nothing to move), version 30.

## ADR-0xx — Calibration measures every per-game band, and the ball comes out on passes too

**Context.** A pre-launch review saw a game with almost twice as many runs as passes. `GameCalibration` turned out to measure 18 metrics, but not five of SPEC 13.2's locked per-game bands: rushes, pass attempts, passing touchdowns, sacks and fumbles lost per team. Four were out of band.

**Decision.**
- **Measure the bands.** The five are measured as real bands, not diagnostics, so `CalibrationTest` guards them like the rest.
- **Rebalance run and pass.** The calling and passing values were retuned to pass more, and shorter. The values are in CALIBRATION.md pass 4.
- **Add the missing fumbles.** Lost fumbles were low because only carries fumbled. Tuning carries up would have made them fumble at twice the NFL's rate (they already did, to make up the total). Instead, the sim gained the two missing kinds: strip-sacks and fumbles after a catch.
- **Count carry fumbles apart.** A box score's `rushFumblesLost` keeps carry fumbles separate, so the per-carry band still measures carries.
- **Stop the clock on turnovers.** Any turnover stops the clock, as a change of possession does in the NFL. The new pass fumbles had let the clock run, and the team that lost the ball spent a timeout.

**Consequences.**
- **Every saved league plays differently:** a save stores only the tuning its user moved, so old saves take the new defaults. A box score gains a field. Save step 33, version 34.
- **The band set is wider:** 23 bands pass on the reference league. One league with a pass-heavy mix of schemes misses pass attempts by 7% of the band, inside the test's 30% tolerance.
- **Passing leaders run about 100 yards higher** on average.
- **Field goals still too common:** attempts run high and points sit low, so field-goal range is where too many drives end. That is the next calibration pass.

## ADR-0xx — Long plays come after the catch, and coaches go for it in easy range

**Context.**
- Field-goal attempts ran about 2.4 a team; recent NFL seasons run about 1.9–2.0.
- The sim had almost no long plays: 0.03 of 40+ yards a team per game, and 5% of touchdowns from 20+ yards out. Yards after the catch averaged under a yard.
- Coaches kicked 94% of fourth downs inside the opponent's 40.

**Decision.**
- **A catch can break open.** Rated on the receiver's elusiveness and speed against the secondary's tackling, it adds a long, exponential run. Throws are a little shorter (`airYardsScale` 0.92) to pay for it.
  - Shortening throws further was tried and rejected: every yard taken from the air cost passing touchdowns and points before it bought enough long plays.
- **Runs break open less often, and further.** Breakaway base 0.026, mean 20 yards.
- **Fourth and short in easy range is no longer an automatic kick.** Inside the 38 with under 5 to go, a coach adds 0.25 to his chance of going for it, except late in the fourth within a kick.
- **The calibration measures long plays.** It reports three diagnostics: plays of 20+ and 40+, and the share of long touchdowns.

**Consequences.**
- **Every saved league plays differently:** a save stores only the tuning its user moved. Save step 34, version 35.
- **Field goals come close to the NFL; long plays move but stay short of it.** Field-goal attempts go from 2.42 to 2.09, within about 0.1–0.2 of the NFL. 40+ plays go from 0.03 to 0.23 a game, and long touchdowns from 5% to 18%. Passing touchdowns and points rise within their bands. One pass-heavy league goes 4 yards over its yards-per-game band, inside the test's tolerance.
- **The rest of the gap is in ordinary gains.** Ordinary gains are too uniform for more 20-yard plays. Single changes that were tried (CALIBRATION.md pass 5) either barely moved them or broke third downs or total yards, so a fix has to rebalance the concept mix and completion by depth together.

## ADR-0xx — A pass's shape is tuned as a whole, by search

**Context.**
- Pass 5 left long plays at about 60% of the NFL's 20+ and 40% of its 40+.
- Measured by concept, the sim threw mostly mid-range routes, almost no screens, completed deep balls too often, and varied a route's depth by only ±2 yards.
- Pass 5 tried four single changes. Each either did nothing or broke third downs or total yards.

**Decision.**
- **Two new levers:**
  - **Screens:** called at a set rate on early downs and third and long. Before, they could only arise from a depth pool that never reached them.
  - **A one-sided depth tail:** a throw goes further than its route between the twenties, never shorter, so third-down throws still reach the sticks.
- **First-down depth moves into the tuning table.** It was a literal in `PlayCaller`.
- **Thirteen values are tuned together by random search** against a loss made from the bands and NFL targets. They are not hand-tuned one at a time: the levers interact. Every long play adds yards, and those have to come off the ordinary plays.

**Consequences.**
- **Long plays:** plays of 20+ go from 2.0 to 2.8 a game, 40+ from 0.23 to 0.34, and long touchdowns reach the NFL's 25%.
- **Small costs:**
  - field goals 2.09 → 2.16;
  - third downs 0.385 → 0.378, still in band.
- **No headroom left:** the search levelled off below the NFL's long-play rates within the yards band. Further progress needs a different structure, not more tuning.
- **Saves:** every saved league takes the new defaults. Save step 35, version 36.

## ADR-0xx — A fumble at the goal line is recovered at the one

**Context.** A carry's fumble keeps up to three of the carry's yards, so
one inside the three could reach the goal line. The game applied the goal
lines before the turnover, which scored a touchdown for the club that lost
the ball. A fumble behind its own goal line was a safety in the same way.
The box score counted the fumble lost while no drive ended in one.

**Decision.** The turnover is checked first. The defence takes the ball at
the spot, clamped to the field of play as every turnover spot already was,
so a fumble that reaches either goal line is the defence's ball at the one.
The NFL's touchback for a fumble into the opponent's end zone, and the
defensive touchdown behind the offense's own goal line, would need return
and recovery logic the sim doesn't have. Ruling it the defence's ball at
the one is the smallest correct-in-kind fix.

**Consequences.** Every lost fumble ends a drive as FUMBLE
(`FumbleDriveTest`). All 23 SPEC 13.2 bands still pass on leagues 2026 and
7. Points per team move 22.48 -> 22.45 and touchdown drives 2.441 -> 2.436;
nothing else moves more than 0.006.

## ADR-0xx — About 30–40 stars, from the first season to the tenth

**Context.** Over a ten-year dynasty the count of players rated 90+ went from about 14 to about 60 and stayed there, while the average starter held still. Progression's growth didn't depend on a player's current rating, and the yearly noise carried a crowd of 88s over 90 a point at a time. Nothing in the spec set a density for the top, and neither end was obviously right: about 14 is sparse beside a sports-game scale, and about 60 is crowded. The drift between them was the problem: a dynasty's stars got four times as common as it went on.

**Decision.** Meet in the middle, at about 30–40 at 90+ throughout:
- **Progression tapers a year's rise near the top,** the noise's included, from 80 overall down to nothing at 99. Breakouts stay whole: they are the stories. Tapering only the age curve was tried first and did little, because at peak age the curve is almost nothing.
- **The generator spreads players wider around their slot's target** (4.5 rather than 3.0), so a new league starts at about 30 rather than about 13. The league mean is unchanged.

**Consequences.**
- **The top holds:** 28–42 at 90+ across ten years in four test leagues.
- **The league mean eases** about a point over a decade (0.3 before), inside StabilityTest's thirty-year bounds.
- **The first season** has more stars and a few more weak players at the bottom of rosters. The per-game bands hold.
- **DemandFloorTest's fixture** now finds its case, a star whose market dips after a snub, instead of naming a club, so tuning the generator doesn't break it.
- **Saves:** existing leagues keep their rosters and take the new progression from their next offseason. Save step 38, version 39.

## ADR-0xx — The editor reads true ratings

**Context.** The user asked to edit player attributes, as a setting chosen when a dynasty starts. Everything the player sees about ratings goes through the ScoutingLens (SPEC 4.6, AGENTS.md rule 7): a club knows its own men as its scouts do, and other clubs' men less well. An editor can't work that way. To set a rating to 85 you have to see that it's 70.

**Decision.**
- **One exception:** the player editor shows and sets true ratings and traits.
- **Nowhere else:** the roster, the player card, the draft and the editor's own finder (which shows no ratings at all) still read through the scouts. The user chose this over showing true ratings everywhere while editing is on.
- **Off unless chosen:** editing is chosen on the club picker and can be switched in Settings, so a dynasty played straight never meets it.

**Consequences.**
- **A known exception:** a user with editing on can learn any man's true ratings by opening him in the editor. That's the point of the feature, and it's opt-in.
- **Rule 7 is unchanged for every other screen.** AGENTS.md notes the exception.
- **Saves:** a dynasty saved before this reads with editing off. Save step 41, version 42.


## ADR-0xx — The user's staff is the user's, in a spring window

**Context.** The user asked to hire and fire coaches and general managers, from a pool. The carousel already fired and hired head coaches for every club, the user's included, and every club's general manager was fixed for the life of the league. The offseason runs as a deterministic chain whose pauses live in memory (SPEC 7), so anything the user decides has to be saved before that chain starts, or replayed into it.

**Decision.**
- **A window, not a pause:** the user changes the staff after the last playoff game and before starting the offseason. Every change is saved as it is made, so the offseason's deterministic re-runs start from the staff the user chose. The carousel runs after the window. Running it at the end of the season instead would have moved the awards and the cap carry-forward, which read the league as the season finished. *Superseded in part:* the window now previews that carousel, which is deterministic, so the pool holds the men the league lets go this spring too. Hiring one waits for the offseason (`PendingHire`, save version 46), and the carousel keeps him off other clubs' shortlists until then. General managers followed in save version 49 (`pendingGm`), with the owners previewed after the coaching carousel.
- **Nobody else fires the user's coaches.** The carousel still keeps his head coach's seat, as advice, and extends a contract that runs out.
- **Fresh candidates are virtual:** drawn from the seed, the year and the job, with ids of 0 or below, and they enter the league only when hired. Putting them in the league every spring would have filled the save with men nobody hired, and would have given the carousel's rehire look a different pool.
- **Fire, then hire.** Hiring needs an open job. A job left open is filled by the front office when the offseason starts, keeping the club's schemes where it can.
- **Owners fire general managers too** (`GmCarousel`), on two losing seasons after two in the chair, and hire by lot. A general manager's style isn't a rating, so there's no "best" one to hire. Hiring by fit would have drifted the whole league to one style.

**Consequences.**
- **The user's club plays differently** in any run that reaches an offseason: its coach is never fired. AI clubs' general managers now change, which moves the money and roster metrics a little; the game's calibration bands hold (commit message).
- **Coordinators' and the special teams coordinator's ratings still do nothing in the sim.** The pool ranks coordinators by ratings overall, but what they bring is their scheme and tendencies. Making those ratings count is a separate change.
- **Saves:** `League.gmPool` and `GmProfile.since` read as empty and 0 from an old save, which is true of it. Save step 42, version 43.

## ADR-0xx — Every coach rating does something, measured from the mean

**Context.** Coaches had six ratings and the sim read two: development and adjustments. With the user hiring from a pool that shows all six, a coordinator's "game plan" or a head coach's "evaluation" read as a reason to hire him and did nothing. The user chose to give the other ratings jobs.

**Decision.**
- **One job each, where the rating's name points** (SPEC 4.7's table): discipline on flags, a coordinator's game plan on his side's plays, the special teams coordinator's on returns, motivation on how long a slump lasts, and evaluation on the club's scouting.
- **Measured from the league's coaching mean (65),** as an edge or a shift, never as a new baseline. A league of average staffs plays exactly as it was calibrated, and only the gap between two clubs shows.
- **No new randomness.** Each effect is a deterministic term on something the sim already rolls, so with its coefficient at zero a game plays exactly as before, and the calibration A/B compares the same draws.
- **Small.** A coordinator at 100 against an average one is a fraction of a yard a snap; the range of a staff is a nudge, not a roster.

**Consequences.**
- **The AI's carousel reads a candidate the same way** (`Staffing.worth`), since the change after this one: a head coach by everything but a game plan, a coordinator by his game plan. It was a change of its own because it moves the league's coaching over a dynasty (CALIBRATION.md pass 16).
- **The pool shows the rating the job uses:** a head coach's other five together, a coordinator's game plan, a position coach's development.
- **Saves:** the new coefficients take their defaults in an old save. Save step 44, version 45.

## ADR-0xx — Coordinators are promoted away under the NFL's rules

**Context.** The user asked for clubs to be able to hire another club's coordinator and promote him, under the real NFL rules. The NFL's anti-tampering policy never lets a club block an assistant's promotion to head coach. Since 2020 it doesn't let a club block a promotion to coordinator either. A sideways move, or a head coach under contract leaving, is the employer's to refuse. The interview calendar matters only during the playoffs, and all of this game's hiring happens after the Super Bowl.

**Decision.**
- **Coordinator to head coach, both ways.** AI clubs look at a few other clubs' coordinators for every head coaching job. The user can promote any club's coordinator, his own included. The user's coordinators can be taken the same way: the rule doesn't care whose staff it is.
- **No sideways moves.** Nobody hires another club's coordinator as a coordinator, or another club's head coach.
- **Through the spring market.** The window previews the carousel (`Staffing.market`), so it shows the user who is about to be promoted away and lets him hire a replacement before the offseason starts. That hire is pending, and a pending hire can carry a fresh candidate. The user's own picks are reserved from the carousel, so the user acts first. Without that, an AI club would always win a contest the user can see coming.
- **Position coach to coordinator** followed later (#136): a club filling a coordinator's job looks at a few other clubs' position coaches as well, and the club that loses one fills his job from the generator's spread, so the cascade stops there.

**Consequences.**
- **Where head coaches come from:** 55% of new head coaches are promoted coordinators, against about 60-70% in recent NFL cycles, with a club looking at nine coordinators (CALIBRATION.md pass 17). Their old clubs replace them from the same candidates as everyone, so coordinators keep their game plans level across the league.
- **Head coaches are better developers** (69 against 67 after ten seasons), because a coordinator is chosen on ratings he has, not drawn below the mean. The league's talent barely moves: a top-22 mean of 77.33 against 77.47.
- **A club's shortlist is drawn man by man** (this club, this coach), not shuffled from a shared stream. One man more or less on the market then changes a club's choice only if he is the man it wanted. Without that, letting a head coach go reshuffled every club's spring, and an agreement the user made for a job a promotion would open could vanish when the promotion did.
- **The user can lose a coordinator** he didn't choose to, with no way to refuse, as in the NFL. The window says so before the offseason starts.
- **Saves:** an agreement can carry a fresh candidate, a change can name the club its new coach was promoted from, and the report lists promotions. All read as before from an old save. Save step 46, version 47.

## ADR-0xx — Coaches age and retire

**Context.** Only head coaches and coaches out of work aged. A league's coordinators and position coaches stayed the age they were generated for its whole life, and nobody in a job ever retired: a head coach who kept winning coached at 80, and the Staff screen showed the same assistants at the same ages for decades.

**Decision.**
- **Everyone ages each spring.** A man in a job retires at an age of his own, drawn once from 66 to 72, so a staff turns over gradually and the same man always retires at the same age.
- **Retirees are replaced from the generator's spread** for position coaches and the special teams coordinator, not from the carousel's below-mean candidates or by picking the best. Either of those would drift player development over a long dynasty, down or up. Coordinators are replaced as when one is promoted away (best of three on game plan), which keeps the two sides of the ball level.
- **The user's club fills its own,** through the spring window like any other opening.

**Consequences.**
- **A long dynasty's staffs turn over,** and the user's window shows retirements coming. Measured effects are in CALIBRATION.md pass 18.
- **Saves:** ages were always saved; old saves start counting from their next spring. Save step 47, version 48.

## ADR-0xx — Coaches have careers, and a young hire starts young

**Context.** Coaches aged and retired, but their ratings never moved. The SPEC's open question - do coaches have career arcs? - was answered yes, with the decline very slow.

**Decision.**
- **One curve by age,** as an offset from a coach's prime: up 1.2 a rating a year to 45, level to 58, down 0.4 a year after, plus a point of noise a year of his own (drawn for him by name, so it is the same in the window's preview and the offseason).
- **A new coach is drawn where his age puts him on it.** Otherwise every young hire, drawn at the old flat mean, would grow past the man he replaced, and the league's coaching - and the player development and in-game edges that read it - would climb dynasty by dynasty. `careerPeakLift` sets a prime above the old flat mean by as much as the young and old fall below it, so the league holds.
- **The generated league is left as it was** (every age at the flat mean): changing it would move the per-game bands, which read the first season's coaches. Its young coaches grow and its old ones slip from the first spring, which settles within a few seasons.
- **Position coaches can become coordinators,** at the AI's clubs and the user's, read with a discount for never having run a side so they don't crowd out every outside candidate.

**Consequences.** Measured in CALIBRATION.md pass 19. Save step 50, version 51.


## ADR-0xx — A new league is generated with a street

**Context.** A phone pass found Free agents empty all through a new dynasty's first season. The generator made rosters and practice squads but nobody unsigned; the street only appeared once an offseason had left its undrafted rookies and unsigned veterans on it. The league's clubs fill reserve places "own squad first, the street second" (SPEC 6.1), so in season one they had only squads, and so did the user.

**Decision.**
- **Generate the street an offseason would leave:** `ai.freeAgentPool` men (260), as a new league's last step.
- **Made as squad camp bodies are** (`squadCampOverall`, `squadCampSpread`, `squadCampAgeBias`), which is what the street holds after an offseason: undrafted-rookie ratings and ages. No new tuning fields.
- **Positions follow the 53-man template,** not the offseason street's mix, which leans on whatever the draft class over-produced. A club that needs a long snapper in week 3 can find one.
- **After the squads, from a stream of their own,** so every roster and practice squad is exactly what the seed made before. Squads are not re-chosen from the new street: that would change every new league's squads for no gain.
- **Existing saves are left alone.** A save still in its first season gets its street at its first offseason, as before; no migration adds players to a save.

**Consequences.** A new league holds 260 more players (about 40 KB). Clubs' first-season reserve signings come off the street when its man is the better one. The per-game bands play generated rosters, so they don't move; the first season's effects are in the PR.

## ADR-0xx — A new league chooses its squads from camp cuts

**Context.** A new league's practice squads were generated camp bodies (median overall 55). Every later season chooses its squads from the men the cut to 53 leaves (median 59). So a club's first-season squad was its weakest ever, and level with the new street, which made clubs sign off the street far more in season one than in any other (CALIBRATION.md pass 20). The street ADR above kept squads as they were so rosters and squads stayed exactly as the seed made them. This reverses that for squads.

**Decision.**
- **Generate camp cuts, not a street:** a squad place for every club plus `ai.freeAgentPool`, then let `PracticeSquads.fill` choose the squads from them exactly as an offseason does. Whoever is left is the street.
- **Made as the offseason's camp bodies are** (`ai.campBody` 55 plus up to `campBodySpread` 7, at `squadCampAgeBias` ages): the existing fields for the men camps sign off the street. No new tuning fields, so no save-format step.
- **Rosters are generated first and are unchanged.** Every new league's squads and street change.

**Consequences.** Squads are chosen the same way every season, and the first season's reserve moves look like later seasons'. Measured in CALIBRATION.md pass 21.

## ADR-0xx — Game-day inactives

**Context.** Every healthy man on the 53 dressed. The NFL dresses 47, or 48 with eight offensive linemen (CBA Article 25), so depth past that never played. The SPEC's last open rules question asked whether inactives would be fun or admin.

**Decision.**
- **The rule as written:** 47, or 48 with eight linemen; hurt men sit first.
- **Scratches by relative depth:** the man deepest at his position for how deep a roster runs there (the generator's template), lower-rated first on a tie. Floors: two quarterbacks, a kicker, a punter, a long snapper; never the eighth lineman, since losing him costs the 48th place too.
- **Admin only if wanted:** the front office picks by default. The user names scratches on the depth chart by swapping one man for another, which names the whole set (`DepthPins.inactive`). A named man who is hurt, gone or would break a floor is skipped, and the front office fills the rest.
- **Not modelled:** practice-squad elevations on game day, and the emergency third quarterback.
- **Calibration dresses the same way,** so the per-game bands measure the games the season plays.

**Consequences.** Measured in CALIBRATION.md pass 22: no band moves. Saves: `DepthPins` gains a defaulted field; save step 52, version 53.

## ADR-0xx — Practice-squad call-ups

**Context.** With game-day inactives in, a club short of fit men played short: its squad could only reach the field by a permanent promotion. The NFL lets a club call up two squad men for a game, each three times a season (the "standard elevation"), and they return to the squad afterwards.

**Decision.**
- **Two a game, three a season each,** counted on the player (`Player.elevations`) and cleared each spring when squads dissolve, and for a replayed season. Postseason games don't add to the count.
- **Called up only when short:** a position below what a game needs, then an eighth lineman (who earns the 48th place), then the position furthest short of a roster's depth while the club has fewer fit men than it may dress. A healthy club calls nobody up.
- **Within the 47 or 48,** and never the man scratched to make room.
- **One function decides who dresses** (`WeekRunner.dressed`), for the game and for the count after it, so the count is of the men who really dressed. On game day a called-up man carries his club's id so the game knows his side; the league keeps him on the squad.
- **The user may name call-ups** (`DepthPins.callUp`), which dress whether or not the club is short, pushing his deepest men out.

**Consequences.** Measured in CALIBRATION.md pass 23. Saves: `Player` and `DepthPins` gain defaulted fields; save step 53, version 54.

## ADR-0xx — Special teams play as units

**Context.** Returns read only the returner's speed and the coordinators; the other ten men on a kick did nothing, the kicker's leg did not touch kickoffs, and the snap and hold - in SPEC 5.10's field-goal formula - were never read. So no club had a reason to dress or call up a special teamer.

**Decision.**
- **Six roles, each read from the ratings a coach looks for** (coverage, gunner, return blocker, jammer, protector, rusher), weighted in the tuning table. Units are picked per kick from the men dressed and not hurt that game.
- **Mostly backups:** starters play coverage and returns only when better by a margin (15 points, measured to put about four starters on kick coverage and one on kick return). Protection and the field-goal rush take the best.
- **Matchups against a league-average unit:** each effect reads two units as points off a new league's average for that unit, so an average matchup plays exactly as before and the league's returns, touchbacks and kicks hold. The base field-goal accuracy rises by the share blocks now take (0.72 to 0.726), and the punt-return mean falls by what clamping a wider spread at zero added (6.5 to 6.1).
- **No snapping rating:** the snapper's and holder's overall at their positions stands in. The rating list stays locked.
- **Units name men, not grades, on screen:** a unit's strength comes from true ratings, which the user sees only through scouting.
- **Special teams give game day its reason:** scratches keep the better special teamer of two as deep, and clubs call up squad men who would cover kicks clearly better than their weakest.

**Consequences.** Measured in CALIBRATION.md pass 24. Saves: `DepthPins` and the tuning table gain defaulted fields; save step 54, version 55.

## ADR-0xx — Coverage tackles are their own stat

**Context.** With special teams played as units, a returned kick needs someone to stop it, and the box score had no returns at all.

**Decision.** Count kick and punt returns and their yards for the returner, and give each return's tackle to a man on the coverage unit, weighted toward the better cover men. Keep coverage tackles in their own field (`StatLine.specialTeamsTackles`) rather than in `tackles`: awards, the Hall of Fame, form and contract pricing all read `tackles` as a defender's work, and a linebacker's value shouldn't jump because he covers kicks. The tackler comes from a stream of the return's own, so counting changes no game (300 games hash the same as before).

**Consequences.** Save step 55, version 56. Seasons already played have no returns counted.

## ADR-0xx — Clubs keep their starters off returns

**Context.** A club's returner was its fastest receiver, back or corner, so its first receiver returned almost every kick. Real clubs rarely risk a top starter on returns.

**Decision.** A starter returns only when better at it than the backups by `returnerStarterPenalty` (8) for each place he starts above the last starter at his position. A flat margin couldn't tell a club's star from its third receiver: at 15 the first receiver still returned a quarter of kicks, and at 20 the returners slowed enough to cost a yard a return. Re-centre the speed each return formula measures from, now in the tuning table, so the league's returns average what they did. A pinned returner is still the club's choice.

**Consequences.** Measured in CALIBRATION.md pass 28. Save step 56, version 57.

## ADR-0xx — The coach re-pins the depth chart during a called game

**Context.** A starter who is hurt or losing his matchups could only be benched between games. The live game runs on a snapshot of the dynasty, so a chart change made mid-game never reached the simulator.

**Decision.** The play caller (`SnapCaller.depthPins()`) hands the simulator the coach's current pins before each snap; when they differ from the side's, that side is rebuilt with them and its cached units dropped, so the change plays from the next snap. Only pins move mid-game: inactives and call-ups were set before kickoff and take effect next game, and men hurt today still sit whatever the chart says. A caller that never changes its pins plays the same game as no caller. After the game the store keeps the new pins, trimmed to men still on the club. Each play's log also names who was hurt on it, so the screen reads injuries from the play rather than guessing from text.

**Consequences.** Save step 60, version 61. No league numbers move: simulated games have no caller.

## ADR-0xx — Kickoffs are lines of the play-by-play

**Context.** A kickoff's return was worked out and credited to the box score, but its line was thrown away, so the game screen never showed a kickoff. Punt lines named the returner only on a return of more than 12 yards, and said nothing of a fair catch.

**Decision.** Log every kickoff the game plays as its own line (`PlayKind.KICKOFF`), worded from the narrative file. The line belongs to the *receiving* club, with the state the kick leaves: first and ten at the return spot. A kickoff logged as the kicking club's would, after a field goal, have read in the calibration's long-play count as a gain on the same drive. The field's banner, after a kickoff, looks at the play before it to say why it was kicked. The watch view's board looks past a kickoff to the next snap, so it shows the ball where the return left it, as it did before.

A score as the half or the game runs out, or one that decides overtime, still makes the sim work out a kickoff. That leaves every draw where it was, but the kickoff isn't logged. Otherwise a phantom third-quarter kickoff would come before the real halftime one. The overtime and field-position tests caught that.

A punt names the man who fielded it, returned or fair caught. The words come from the game's narration stream, so no kick moves: 300 games' scores, box scores and injuries hash the same as before.

**Consequences.** Save step 61, version 62. Games already played have no kickoff lines.

## ADR-0xx — Training camp comes before the cut to 53

**Context.** The offseason's development ran after every club had cut to 53, so a club, the user's included, cut the man it had signed rather than the one camp showed. Nothing happened at camp but the cut, and nobody was ever hurt there.

**Decision.** After the draft, run camp before any cut. Development happens there, and a man on a club's roster risks an injury that costs games: `campRate` (2%), raised by proneness and lowered by resistance as on a snap, on the season's spread of how long. The spread is now one function both share (`Injury.gamesOut`). Camp's injuries come from a stream of their own. The turn of the year, which heals everyone, keeps a camp injury, since camp is this season's. The user's cut screen opens on what camp showed: each position group's move in his staff's read, its riser and fallers, and who was hurt. That keeps the true ratings behind the scouting lens.

**Consequences.** Moving development ahead of the fill and the cuts changes the offseason's draws, so leagues play out differently from the same seed. Four leagues over eight seasons, against main: spread of wins after the first season 2.906 against 2.883, rostered talent 72.43 against 72.33, points a game 22.74 against 22.56, about 0.8 men a club starting the season hurt from camp. No calibration band moves. Undrafted men signed after camp don't develop until next year. Tuning gains `injuries.campRate`; save step 62, version 63.

## ADR-0xx — Clubs cut and trade at camp on what it showed

**Context.** The only cuts after camp were the cut to 53 by roster count. A veteran camp had passed by, or one who had gone backwards, stayed on his deal unless he happened to be the worst man at his position. The spring's value cuts judge a man before camp, and only by this year's cap.

**Decision.** After camp, each club makes up to two moves on veterans paid at least 1.5 times the minimum, for three reasons judged by its GM's patience: regression, being passed by, or next year's cap. A move must save money this year, and at least $750k counting half of next year's. Before cutting a man who would start elsewhere, a club trades him to the club he'd most help, for its latest pick next year. The user's club gets the same plan as suggestions only, each with its reason, and none are made for him; the front office makes them if he hands it the cut. A first pass with the spring's thresholds made almost no moves, because most backups earn the minimum. Dropping the bar to the minimum made every minimum backup "overpaid", since a backup's market worth is under the minimum. So money moves weigh only men paid well over it.

**Consequences.** Four leagues: 3-8 camp cuts and 7-14 camp trades a league. Over eight seasons against #24: spread of wins after the first season 2.869 against 2.906, talent 72.53 against 72.43, points a game 22.51 against 22.74; no calibration band moves. Tuning gains seven `ai.camp*` fields; save step 63, version 64.
## ADR-0xx — Paid once: $2.99 at launch, then $4.99

**Context.** Monetization was the SPEC's last open question. Google Play never lets a free app become paid, so the choice had to be made before the first release. In-app purchases could be added later, but only with a billing library, the internet permission, and new privacy and data-safety answers.

**Decision.** A one-time price: $2.99 for the first two weeks after launch, then $4.99. No ads, no in-app purchases, no accounts. The app stays as it is: no billing code, no network, no permissions. The price lives in Play Console, not in the listing text.

**Consequences.** Fewer installs than a free app, in exchange for revenue from the first sale and the "buy once, no strings" promise the listing makes. A Play payments profile is needed before the price can be set. The two-week change to $4.99 is a manual step in Play Console (RELEASE.md, *Pricing*). Anyone who bought at $2.99 keeps the app.

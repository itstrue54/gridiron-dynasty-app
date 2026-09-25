# Bring your own rosters

Gridiron Dynasty ships with a fictional league. If you want to play with real
players - an official NFL roster, last season's, your fantasy league - you
supply the file yourself. The game never includes or downloads real names or
ratings.

## The quick way

1. On the title screen, tap **Save a roster template**. It lands in your
   phone's Downloads as `gridiron-dynasty-rosters.json`.
2. Edit it (any text editor, or paste your data in): one entry per club, each
   with its players. Delete the example club.
3. Back on the title screen, tap **Start with my own rosters** and pick your
   file.
4. The club picker shows what was imported. Choose your club and play.

## The file

```json
{
  "teams": [
    {
      "abbrev": "KC",
      "city": "Kansas City",
      "nickname": "Chiefs",
      "conference": "AFC",
      "division": "West",
      "players": [
        { "name": "First Last", "position": "QB", "number": 15, "age": 29, "overall": 95 },
        { "name": "First Last", "position": "WR", "ratings": { "spd": 94, "cth": 88 } }
      ]
    }
  ]
}
```

- **Teams:** up to 32. `conference` is AFC or NFC, `division` East, North,
  South or West. Clubs you leave out stay fictional, so one team is fine.
- **Players:** only `name` and `position` are required. Positions: QB RB FB
  WR TE LT LG C RG RT EDGE DT LB CB S K P - and DE, OLB, HB, FS, SS and the
  like are understood.
- **How good:** give `overall` (40-99) and a player is built to that level,
  or give `ratings` codes (0-99) for exact numbers: `spd acc str agi awr prc
  thp tas tam tad cth srr mrr drr rls rbk pbk tak pow mcv zcv kpw kac` and
  more. Anything you leave out is filled in sensibly.
- **Optional:** `number`, `age`, `college`, `height` ("6-2"), `weight`,
  `archetype`, `dev`.
- **Roster size:** up to 53 per club go on the roster, the next 16 on the
  practice squad. List 53 or more and that is the club's real roster: the
  game adds nobody (no fullback if you list none). List fewer and the
  positions you leave short are filled with made-up players so the club
  can take the field.

## Coaches and front office

Any club can also carry its general manager, coaching staff and schemes:

```json
"gm": { "name": "First Last", "aggression": 0.6, "winNow": 0.8, "loyalty": 0.5, "risk": 0.4 },
"offenseScheme": "OFF_WEST_COAST",
"defenseScheme": "DEF_43_OVER",
"staff": {
  "headCoach": { "name": "First Last", "age": 58, "contractYears": 3,
                 "ratings": { "development": 80, "gameplan": 85, "adjustments": 75,
                              "discipline": 70, "motivation": 90, "evaluation": 65 } },
  "offensiveCoordinator": "First Last",
  "defensiveCoordinator": { "name": "First Last", "tendencies": { "blitzRate": 0.35 } },
  "specialTeamsCoordinator": "First Last",
  "positionCoaches": { "QB": "First Last", "DT": "First Last", "EDGE": "First Last" }
}
```

- **Slots:** head coach, three coordinators, and a coach for each of QB RB WR
  TE OL EDGE DT LB CB S ST. The same person can fill more than one (a
  defensive line coach who also coaches the edge). A slot you leave out keeps
  a made-up coach, and the import tells you which.
- **A coach** can be just a name. Ratings (0-100) you leave out, and age,
  contract and `hotSeat` (0-100, how close he is to being fired), come from
  the made-up coach he replaces.
- **Schemes:** OFF_WIDE_ZONE, OFF_GAP_POWER, OFF_AIR_RAID, OFF_WEST_COAST,
  OFF_SPREAD_OPTION, OFF_VERTICAL, OFF_RUN_HEAVY_PRO; DEF_43_OVER,
  DEF_43_UNDER, DEF_34_TWO_GAP, DEF_34_ONE_GAP, DEF_425_NICKEL, DEF_TAMPA_2,
  DEF_COVER3_MATCH, DEF_MAN_BLITZ, DEF_335_MULTIPLE. Leave them out and the
  club runs its coordinators' schemes.
- **GM tendencies** (0-1): how much he spends on one player, how all-in he
  is, how hard he keeps his own, and how much risk he takes. You are the GM
  of the club you pick; the others' tendencies drive their moves.

A flat list also works - `{ "players": [ ... ] }` or just `[ ... ]` - with a
`"team": "KC"` on each player. So does a CSV with the same column names.

## If something is off

The club picker lists what the import did: how many players were read, where
each club was placed, any rows skipped (an unknown position, say), and any
fields the game did not understand. Fix the file and import it again - the
same file always builds the same league.

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
  practice squad. Positions you leave short are filled for you.

A flat list also works - `{ "players": [ ... ] }` or just `[ ... ]` - with a
`"team": "KC"` on each player. So does a CSV with the same column names.

## If something is off

The club picker lists what the import did: how many players were read, where
each club was placed, any rows skipped (an unknown position, say), and any
fields the game did not understand. Fix the file and import it again - the
same file always builds the same league.

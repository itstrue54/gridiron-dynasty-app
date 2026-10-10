# Testing before production

The release build has never been played: every check so far ran a debug
build. The Demands crash (fixed in #26) came from a screen opened in a state
nobody had tried. This is the plan that finds the next one before a buyer does.

## 1. Internal test: the release build, on your phone (day 1)

1. Build the signed bundle (`RELEASE.md` step 3) and upload it to **Testing ->
   Internal testing**. Add yourself as a tester.
2. Install it from the opt-in link. It installs as **Gridiron Dynasty**,
   beside the debug build ("Gridiron Dynasty (debug)"), with its own saves.
3. Play the **full loop** on it, start to finish - the tour below - before
   inviting anyone.
4. Read **Quality -> Pre-launch report** a few hours after the upload. Google
   runs the app on a spread of real phones, old and new, and lists crashes,
   ANRs and screens that broke. Fix anything it lists.

## 2. Closed test: 12 testers for 14 days (if Play asks)

A personal developer account created after November 2023 must run a closed
test with **at least 12 testers opted in for 14 days in a row** before it can
apply for production. Play Console says so on the Dashboard if it applies.

1. **Testing -> Closed testing**: create a track, add the testers' Google
   account emails (or a Google Group), and upload the same bundle.
2. The app is paid: give each tester a **promo code** (`RELEASE.md`,
   *Pricing*) so they install it free.
3. Testers must stay opted in for the 14 days. Ask each to play at least a
   season and an offseason, and to send what they find (below).
4. After 14 days, Dashboard -> **Apply for production**. Google asks what the
   test found and what changed; keep notes as you go.

## 3. The tour: every screen, including the ones nobody opens

Play it from a **new dynasty** (not a save from the debug build). Tick each.

**First minutes**
- [ ] New dynasty: pick a club; the welcome card; the glossary.
- [ ] Hub: every link opens and comes back with Back.

**The season, weeks 1-10**
- [ ] Play a week; watch the last game play by play; box score.
- [ ] Call a game yourself: Last play box, possession banner, a touchdown,
      a turnover, a kickoff after a score, a punt.
- [ ] In a called game: Depth chart and Roster panels, a depth change, Back.
- [ ] Game plan; depth chart; roster; a player card; restructure a contract.
- [ ] **Demands** (if anyone asks): each answer - pay, 90%, refuse.
- [ ] **Trades**: calls, build a deal with each club's roster, hold a man for
      his card, put a man on the block, make a trade.
- [ ] **Free agents**: sign, sign to the squad, sign one off another squad,
      release (with and without dead money), See his card.
- [ ] Injured reserve: a man goes on it and comes back.

**Late season and playoffs**
- [ ] Pass the trade deadline; Trades says it is closed.
- [ ] Playoffs, or the season end if missed; standings, news, history.

**The offseason, every stop**
- [ ] Staff: fire and hire a coach; promote a coordinator; the GM.
- [ ] Expiring contracts: re-sign, tag, let go, See his card.
- [ ] Free agency: offers, talk to an agent, transition tags.
- [ ] Draft room: a trade before the first pick, a scouting report, draft
      from it, let the scouts finish.
- [ ] Training camp: the report, a camp injury, camp's calls (take a trade,
      make a cut), sign off the street, set the 53.
- [ ] The new season's hub; the offseason report.

**The edges**
- [ ] Rotate the phone; large font size (Settings -> Display); dark mode.
- [ ] Leave mid-game and come back; kill the app mid-offseason and reopen.
- [ ] Saves: save to a slot, load it, delete it; autosaves after a week.
- [ ] Settings: theme, tuning presets, player editing on and off.
- [ ] Export a roster; share a season summary.

## 4. What a tester sends

For anything wrong: **what screen, what you tapped, what happened, what you
expected**, and a screenshot. For a crash, also the time it happened: the
crash log on the phone carries the time, and Play Console's **Android
vitals -> Crashes** shows the stack.

## 5. Ready for production when

- [ ] The tour is ticked on the release build.
- [ ] The pre-launch report lists no crashes.
- [ ] No crash reported in the last 7 days of the closed test.
- [ ] Screenshots retaken on the build that ships (`listing.md`).

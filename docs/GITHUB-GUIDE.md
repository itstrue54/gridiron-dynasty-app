# GitHub for the Dynasty Sim — a working guide

**For:** Peter · Android Studio on a laptop · GitHub account exists, barely used
**Goal:** stop losing work, always be able to get back to "when it worked," and turn the spec into a tracked build plan.

---

## Part 0 — The mental model (read this once, it makes everything else obvious)

Git is a **save-state system for your project folder.** That's it.

You already understand this from franchise mode. Think of it this way:

| Franchise mode | Git / GitHub |
|---|---|
| Saving your dynasty before a risky trade | **commit** — a snapshot of every file, with a note |
| Your list of saves, in order | **history** (the log) |
| Loading an earlier save because the trade blew up | **checkout / revert** |
| Starting a "what if" file to test a rebuild without touching your real dynasty | **branch** |
| Deciding the what-if was good and making it your real file | **merge** |
| Cloud saves so a dead hard drive doesn't end your dynasty | **push to GitHub** (the remote) |
| Your dynasty's news log | **commit messages** |

Three facts that clear up 90% of the confusion:

1. **Git and GitHub are different things.** Git is the save system running on your laptop. GitHub is a website that stores a copy. Git works fine with no internet; GitHub is the backup + the project-management layer.
2. **Committing is local. Pushing is what sends it to GitHub.** You can commit 20 times offline and push once.
3. **Nothing is ever really lost once it's committed.** Almost every "I broke everything" scenario is recoverable in one command. That is the entire point.

The payoff for a solo dev on a project this size: **you can be reckless.** You can let an AI rewrite the whole sim engine, discover it's worse, and be back to yesterday's version in ten seconds. Without git you would be afraid to touch working code — and fear is what kills hobby projects.

---

## Part 1 — Vocabulary (the only 12 words you need)

| Word | What it actually means |
|---|---|
| **repository (repo)** | Your project folder, with history attached |
| **commit** | One saved snapshot + a message |
| **stage** | Choosing *which* changed files go into the next commit |
| **branch** | A parallel line of saves. `main` is the default one |
| **main** | Your known-good line. Keep it working |
| **remote** | The copy on GitHub. Nicknamed `origin` |
| **push** | Send your commits up to GitHub |
| **pull** | Bring GitHub's commits down to your laptop |
| **clone** | Download a repo for the first time |
| **merge** | Fold one branch's work into another |
| **pull request (PR)** | A proposal to merge, with a review page. Useful even solo |
| **issue** | A tracked to-do item on GitHub |
| **tag / release** | A permanent bookmark on a commit, e.g. `v0.6-m6-playable` |

---

## Part 2 — One-time setup (about 30 minutes)

### 2.1 Confirm git is installed

Android Studio bundles git detection but not always git itself.

- **Windows:** install Git for Windows from git-scm.com. Take all defaults.
- **Linux (Zorin/Ubuntu):** `sudo apt install git`
- Then in Android Studio: **Settings → Version Control → Git → "Test"**. It should print a version number.

### 2.2 Tell git who you are

Open a terminal (Android Studio has one at the bottom: **View → Tool Windows → Terminal**):

```bash
git config --global user.name "Peter"
git config --global user.email "59238283+itstrue54@users.noreply.github.com"
git config --global init.defaultBranch main
git config --global pull.rebase false
```

That email should match your GitHub account, or your commits won't be linked to your profile.

### 2.3 Connect Android Studio to GitHub

Easiest path — skip tokens entirely:

**Settings → Version Control → GitHub → `+` → Log In via GitHub…**

It opens a browser, you authorize, done. Android Studio now handles authentication for you forever. (The alternative is generating a Personal Access Token and pasting it, which also works but is one more thing to lose.)

### 2.4 Create the project and put it on GitHub

**Order matters — create the Android project first, then hand it to git.**

1. Android Studio → **New Project → Empty Activity (Compose)**. Name it `gridiron`. Package `com.gridiron`. Minimum SDK 26. Language Kotlin.
2. Let it finish the first Gradle sync. Run it once on the emulator so you know the baseline works.
3. Replace the generated `.gitignore` with the one in this bundle (`gitignore-android.txt` → save as `.gitignore` at the project root). **Do this before your first commit.** Committing build output once is annoying to undo.
4. **Git → GitHub → Share Project on GitHub.**
   - Repository name: `gridiron`
   - **Private** (you can flip it public later; you can't un-publish something people already cloned)
   - It will show you the file list for the first commit. Glance at it — if you see `build/`, `.gradle/`, or `local.properties` in there, your `.gitignore` isn't being picked up. Fix it before continuing.
   - Commit message: `Initial commit: Android Studio project skeleton`
5. Open github.com and confirm the repo is there.

### 2.5 Add the docs

Copy `SPEC.md` into `docs/SPEC.md`, create `docs/DECISIONS.md`, and add the `README.md` from this bundle. Commit them:

```
docs: add technical specification v1.0
```

Your spec now has version history. When you change the design in month four, you'll be able to see exactly what you used to think and why you changed your mind.

---

## Part 3 — The daily loop (this is 95% of what you'll ever do)

In Android Studio, three keyboard shortcuts cover almost everything:

| Action | Windows/Linux | What it does |
|---|---|---|
| **Commit** | `Ctrl+K` | Opens the commit panel, shows your changes, lets you write a message |
| **Push** | `Ctrl+Shift+K` | Sends commits to GitHub |
| **Update/Pull** | `Ctrl+T` | Pulls down anything new from GitHub |

**A normal work session looks like:**

1. `Ctrl+T` — pull first. (Matters once you're on two machines. Harmless habit now.)
2. Write code. Run tests.
3. Something works → `Ctrl+K`, write a message, commit. **Commit at every "that works" moment**, not at the end of the night.
4. Repeat 2–3 several times.
5. End of session → `Ctrl+Shift+K` to push.

**How big should a commit be?** One idea. "Added the pass-rush matchup calculation" is a commit. "Worked on the engine for four hours" is not. The test: could you write the message in one line without the word "and"? If not, split it.

**The commit panel is also your review step.** Before you commit, the diff view shows exactly what changed — red removed, green added. Read it. You will catch a stray `println` or a half-deleted function about once a week. That thirty seconds is the highest-value habit in this guide.

### 3.1 Commit message format

Use a prefix. It makes the history scannable a year from now:

```
feat: add scheme fit modifier to effectiveRating
fix:  clock no longer runs after an incomplete pass
test: add calibration bands for rushing metrics
docs: update SPEC §5.7 outcome sampling
refactor: extract PenaltyChecker from simPlay
chore: bump AGP to 9.4.0
tune: lower INT rate coefficient to 0.019
```

If the commit needs explaining, write a blank line and then a paragraph. Explain **why**, not what — the diff already says what.

```
tune: lower INT rate coefficient to 0.019

Calibration run over 1000 seasons was producing 3.4% INT rate against a
target band of 2.0-2.8%. Traced to K_THROW being too sensitive to pressure.
Calibration report updated in docs/CALIBRATION.md.
```

---

## Part 4 — Branches (start using these at M2)

For the first week, committing straight to `main` is fine. Once the sim engine exists and you care about not breaking it, switch to branches.

**The rule: `main` always builds and always passes tests. Everything experimental happens on a branch.**

In Android Studio, the branch control is in the **bottom-right status bar** (or **Git → Branches**).

**Creating one:** Git → Branches → **New Branch** → name it `feat/pass-rush-matchup`. You're now on it; `main` is frozen exactly as it was.

**Naming convention:**

```
feat/scheme-fit-modifier      new capability
fix/clock-runoff-incomplete   bug fix
tune/injury-rates             calibration work
exp/spatial-run-blocking      experiment you may throw away
```

**Finishing one:** push the branch (`Ctrl+Shift+K`), then on github.com click **Compare & pull request**, then **Merge**. Then locally: switch to `main`, `Ctrl+T` to pull the merge down, delete the branch.

**"Why a pull request if I'm the only one?"** Three real reasons, none of them ceremony:

1. It gives you a **full diff of the whole feature** in a readable web page. Reviewing 400 lines in one view catches things you'd never see across 12 commits.
2. It runs your CI (Part 7) before the code touches `main`.
3. It leaves a **written record of why** the feature exists, linked to its issue.

**"Can I just throw the branch away?"** Yes. If `exp/spatial-run-blocking` turns out to be a bad idea, switch back to `main` and delete the branch. Nothing on `main` was ever touched. This is exactly why you'd try the risky refactor at all.

---

## Part 5 — The recovery playbook

Bookmark this section. This is the part that saves your project.

### "I broke a file and want it back to the last commit"
Commit panel → right-click the file → **Rollback**. Or: `git restore path/to/File.kt`

### "I broke everything since my last commit, nuke it all"
```bash
git restore .
```
Your last commit is back. Uncommitted changes are gone (that's the point).

### "My last commit was bad. Undo it but keep the code so I can fix it"
```bash
git reset --soft HEAD~1
```
The commit disappears; your changes sit in the staging area, ready to redo properly.

### "My last commit was bad. Delete it and the code"
```bash
git reset --hard HEAD~1
```
Only do this if you have not pushed yet.

### "I already pushed a bad commit"
Don't rewrite pushed history. Make an *undo commit* instead:
```bash
git revert <commit-hash>
```
This creates a new commit that reverses the bad one. Honest, safe, leaves the record intact.

### "The sim worked three days ago and I don't know what I broke"
This is the single best reason to use git.
```bash
git log --oneline          # find the commit from three days ago
git checkout <hash>        # look at the project exactly as it was
```
You are now in "detached HEAD" — a read-only look at the past. Run it, confirm it worked. Then:
```bash
git checkout main          # come back to the present
```
Now diff the two to find your bug:
```bash
git diff <good-hash> HEAD -- engine/
```

Better still, let git find it for you:
```bash
git bisect start
git bisect bad                    # current version is broken
git bisect good <old-good-hash>   # this one worked
# git checks out a midpoint; you test; then:
git bisect good   (or)   git bisect bad
# repeat ~5 times and git names the exact commit that broke it
git bisect reset
```
On a 200-commit history that's 8 tests instead of reading 200 diffs. For a simulation engine where a bug shows up as "scoring is now 31 points a game," this is close to magic.

### "I want to grab one good commit from an abandoned branch"
```bash
git cherry-pick <hash>
```

### "I need to switch branches but I'm mid-change and not ready to commit"
```bash
git stash          # pockets your changes
git switch main
# ...do the thing...
git switch -       # back to your branch
git stash pop      # changes come back
```

### "I committed something I shouldn't have — a keystore, a password"
Stop. Don't just delete it in a new commit; it stays in history. If it's already pushed, **rotate/regenerate the secret first**, then rewrite history (`git filter-repo`) or, for a young solo repo, delete the GitHub repo and re-push a clean one. Prevention is the `.gitignore` in this bundle.

### The safety net behind the safety net
Android Studio keeps its own **Local History** (right-click a file → **Local History → Show History**) covering changes you never committed at all. It has saved plenty of people who did a `git reset --hard` too enthusiastically.

---

## Part 6 — Turning the spec into a tracked build

This is where GitHub stops being backup and starts making the project *easier*.

### 6.1 Milestones

On github.com: **Issues → Milestones → New milestone.** Create one per row of SPEC §14:

```
M0 Project skeleton
M1 Domain model
M2 Play engine v0
M3 Game engine
M4 Calibration pass 1
M5 Season
M6 Minimum playable app     ← the one that matters
M7 Offseason
M8 Depth & scheme
M9 Scouting & traits
M10 Narrative & polish
M11 Calibration pass 2
M12 v1.0 release
```

Each milestone shows a completion bar. On a project this size, seeing "M2: 7 of 11 done" is most of what keeps you going on a Tuesday night.

### 6.2 Issues

An issue = one unit of work you could finish in a sitting. `ISSUES-SEED.md` in this bundle has ~60 ready to paste in for M0–M4.

Write them with a **definition of done**, because future-you has forgotten the context:

```markdown
Title: Implement gap-scheme bonus lookup table

Body:
Per SPEC §5.6, run-blocking advantage needs a bonus/penalty from the
interaction of offensive run concept and defensive front.

Done when:
- [ ] GapSchemeTable loads from data/schemes.json, not hardcoded
- [ ] Covers: inside zone, outside zone, power, counter, duo, trap
      vs 4-3 over/under, 3-4 two-gap/one-gap, 4-2-5, 3-3-5
- [ ] Values exposed in TuningTable
- [ ] Unit test: outside zone vs 3-4 two-gap returns a penalty;
      power vs light box returns a bonus
- [ ] YPC calibration band still passes (4.1-4.6)

Labels: engine, M3
```

### 6.3 Labels

Keep it short: `engine`, `ui`, `data`, `ai-gm`, `calibration`, `bug`, `research`, `blocked`, `good-first-session` (small things for a low-energy night — this one is genuinely useful for solo hobby projects).

### 6.4 Projects board

**Projects → New project → Board.** Columns: `Backlog → Next up → In progress → Blocked → Done`. Add all issues. Filter by milestone. Drag as you go.

Two settings worth turning on: the built-in **automation** so issues move to Done when closed, and the **milestone** field as a board group.

### 6.5 Linking commits to issues

Put `#12` in a commit message and GitHub links them automatically. Put `Closes #12` and merging it **closes the issue for you**:

```
feat: add gap-scheme bonus lookup table

Closes #12
```

This is the small thing that makes the whole system self-maintaining. Do it and the board stays accurate with zero extra work.

---

## Part 7 — CI: let GitHub run your tests

`ci.yml` in this bundle goes in `.github/workflows/ci.yml`. Once committed, GitHub runs it on every push and PR: builds the project, runs the engine unit tests, and greps `:engine` for the banned nondeterminism calls from SPEC §13.4.

Why this matters more than it sounds for a solo project:

- **You will forget to run tests.** The robot won't.
- A red ✗ on a pull request stops a broken merge into `main`, which is the whole reason `main` stays trustworthy.
- Later, a nightly job can run the 1,000-season calibration (SPEC §13.3) while you sleep and post the report. That's a genuinely expensive computation you get for free.

Start with just build + unit tests. Add the calibration job at M4.

---

## Part 8 — Using GitHub with AI assistance

This is the part that changes how fast this project goes, and it's the reason I'd have pushed you toward git even if you hadn't asked.

**The core habit: commit before you let an AI touch anything.**

A clean working tree before an AI edit means:

1. `git diff` afterwards shows you **exactly** what it changed — every line, nothing hidden. Review that diff the same way you'd read the commit panel. AI-written code is usually good and occasionally confidently wrong; the diff is where you catch the second kind.
2. If it went badly, `git restore .` and you've lost nothing but a few minutes.
3. You can let it attempt something ambitious — "restructure the offseason phase machine" — because the downside is bounded.

**The workflow:**

```
1. Commit your current work (clean tree)
2. Create a branch:  exp/ai-offseason-refactor
3. Let the AI work
4. ./gradlew test           ← tests are the contract, not vibes
5. Read the diff
6. Good?  commit, PR, merge.   Bad?  switch to main, delete branch.
```

**Two files that make an AI dramatically more useful on this repo:**

- **`docs/SPEC.md`** — point any assistant at it before asking for a feature. Most bad AI output on a project like this comes from the model not knowing your architecture and inventing a different one. The spec fixes that in one paste.
- **`AGENTS.md`** (or `CLAUDE.md`) at the repo root — a short file of house rules that gets read automatically by most coding agents:

```markdown
# House rules
- :engine is pure Kotlin/JVM. Never import android.* there.
- All randomness comes from the injected Rng. Never call Math.random(),
  java.util.Random, System.currentTimeMillis(), or UUID.randomUUID().
- All sim coefficients live in TuningTable, never as literals in functions.
- Model classes are immutable data classes with val only.
- Every engine change needs a unit test. Run: ./gradlew :engine:test
- Update docs/SPEC.md in the same commit when behavior changes.
- Commit style: feat: / fix: / test: / docs: / tune: / refactor: / chore:
```

That file is maybe fifteen minutes of writing and it pays for itself the first day.

---

## Part 9 — Releases

At each milestone, tag it:

```bash
git tag -a v0.6-m6-playable -m "M6: first playable season on device"
git push origin v0.6-m6-playable
```

On github.com, **Releases → Draft a new release**, pick the tag, write what changed, and attach the APK. You now have a permanent, downloadable copy of every version that ever worked — which matters a lot the first time a save-file migration goes wrong and you need to reproduce the old format.

---

## Part 10 — Cheat sheet

| I want to… | Terminal | Android Studio |
|---|---|---|
| See what I've changed | `git status` / `git diff` | Commit panel (`Ctrl+K`) |
| Save a snapshot | `git add . && git commit -m "msg"` | `Ctrl+K` |
| Send to GitHub | `git push` | `Ctrl+Shift+K` |
| Get from GitHub | `git pull` | `Ctrl+T` |
| See history | `git log --oneline --graph` | Git tool window (`Alt+9`) |
| New branch | `git switch -c feat/thing` | Status bar → New Branch |
| Change branch | `git switch main` | Status bar → click branch |
| Throw away my changes | `git restore .` | Right-click → Rollback |
| Undo last commit, keep code | `git reset --soft HEAD~1` | Git log → right-click → Undo Commit |
| Undo a pushed commit | `git revert <hash>` | Git log → right-click → Revert |
| Look at an old version | `git checkout <hash>` | Git log → right-click → Checkout |
| Find which commit broke it | `git bisect start` | — (terminal only) |
| Pocket changes temporarily | `git stash` / `git stash pop` | Git → Uncommitted Changes → Stash |
| Tag a version | `git tag -a v0.6 -m "msg"` | Git log → right-click → New Tag |

---

## Part 11 — Mistakes to skip

1. **Committing build output.** Get `.gitignore` right before commit #1. Symptom: a commit with 4,000 changed files.
2. **Committing `local.properties`.** It contains machine-specific SDK paths and breaks the build on any other machine.
3. **Committing your release keystore or `keystore.properties`.** Never. If you lose the keystore you can never update the app on Play; if you leak it, someone else can sign as you. Back it up somewhere private and off-git.
4. **Giant commits.** "End of week dump, 3,000 lines" is useless to bisect and impossible to review.
5. **Vague messages.** `update`, `fix`, `stuff`, `asdf`. In six months these are worthless. Thirty seconds of writing beats an hour of archaeology.
6. **Working for days without pushing.** Commits on your laptop only are not a backup.
7. **Being afraid of the terminal.** The Android Studio UI covers the daily loop, but `bisect`, `stash`, and `reflog` are terminal-only and they're the ones that rescue you.
8. **Not committing before an AI edit.** Then you can't tell what changed, and can't cleanly undo it.

---

## Part 12 — Your first hour, in order

- [ ] Install/verify git; set `user.name` and `user.email`
- [ ] Log Android Studio into GitHub (**Settings → Version Control → GitHub**)
- [ ] Create the `gridiron` Android project; run it once on the emulator
- [ ] Drop in `.gitignore` **before** the first commit
- [ ] **Git → GitHub → Share Project on GitHub** (private)
- [ ] Add `docs/SPEC.md`, `README.md`, `AGENTS.md`; commit; push
- [ ] Add `.github/workflows/ci.yml`; commit; push; watch it go green in the **Actions** tab
- [ ] Create milestones M0–M12
- [ ] Paste in the M0 and M1 issues from `ISSUES-SEED.md`
- [ ] Create the Projects board, add the M0 issues, drag one to **In progress**
- [ ] Make a trivial change, commit it with `Closes #1`, push, and watch the issue close itself

That last step is the one that makes it click.

---

*Practice the recovery playbook (Part 5) on purpose, once, on day one. Break a file, restore it. Make a junk commit, reset it. Five minutes of deliberately breaking things, while nothing is at stake, is what turns git from a source of anxiety into the thing that lets you build fearlessly.*

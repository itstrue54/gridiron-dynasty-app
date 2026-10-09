# Releasing to Google Play

Everything here that needs a password or an account is yours to do. Nothing
secret is ever committed: `keystore.properties` and keystores are gitignored.

## 1. Create an upload key (once, and keep it safe)

```bash
keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Keep `upload-key.jks` and its passwords somewhere backed up and private. Play
App Signing holds the real signing key; this upload key can be reset through
Play support if lost, but it is a slow process.

## 2. Point the build at it

Create `keystore.properties` in the repository root (it is gitignored):

```properties
storeFile=upload-key.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

## 3. Build the signed bundle

```bash
./gradlew :app:bundleRelease
```

The bundle is `app/build/outputs/bundle/release/app-release.aab`. Without
`keystore.properties` the same command builds an unsigned bundle, which Play
will not accept.

Before each release, raise `versionCode` in `app/build.gradle.kts` (Play
rejects a code it has seen) and set `versionName`.

## 4. In Play Console

1. Create the app. Default language English, **Paid**, no ads. A paid app
   can later be made free, but a free one can never be made paid - and a
   paid app made free cannot go back.
2. **App content**
   - Privacy policy: https://itstrue54.github.io/gridiron-dynasty/ (see
     *Hosting the privacy policy* below).
   - Contact email: amfootballsimtext@gmail.com.
   - Data safety: **no data collected, no data shared.** The app declares no
     permissions and makes no network requests. Android's own backup may copy
     the saves and settings to the player's Google account
     (`res/xml/data_extraction_rules.xml`), but that goes to the player, not
     to the developer. Check the form's help text on backups when you fill it
     in.
   - Ads: none. Target audience: 13+ (a sports management game; no content
     concerns, but it is not designed for children).
   - Content rating: complete the questionnaire. No violence beyond sport, no
     user interaction, no purchases - expect Everyone / PEGI 3.
3. **Store listing**: copy from `listing.md`. Assets needed:
   - app icon: `assets/icon-512.png`
   - feature graphic: `assets/feature-graphic-1024x500.png`
   - phone screenshots: the eight `store-*.png` files in
     `assets/screenshots/` (see `listing.md`)

   Both images are drawn by `assets/draw-assets.py`, which reuses the
   launcher icon's mark. Run `python3 assets/draw-assets.py assets` to
   redraw them if the palette changes.
4. **Testing** -> Internal testing: upload the `.aab`, add yourself as a
   tester, install from the opt-in link, and play a season through before
   promoting to production.

## Pricing

Paid, once: **$2.99 for a two-week launch, then $4.99** (DECISIONS.md, *Paid
once*). Nothing in the app changes with the price: there is no billing code.

1. **Payments profile first.** Play Console -> Setup -> Payments profile: a
   merchant account with your tax details. Play will not let a paid app be
   priced until it exists, and it can take a few days to verify.
2. **Set the launch price.** Monetize -> App pricing: $2.99 (USD), and let
   Play convert it for the other countries - or set them yourself.
3. **Put the change in your calendar** for two weeks after the production
   release goes live. On that day set the price to $4.99 the same way. It
   reaches the store within a few hours; anyone who already bought keeps the
   app. (Play's paid-app *sales* tool can't be used for this: a sale lowers a
   price, and you are raising one.)
4. **Testers and reviewers.** A paid app on a closed test may ask testers to
   buy it. Give them **promo codes** instead (Monetize -> Promo codes, up to
   500 a quarter): a code installs the app free.
5. **The listing never names the price.** Play shows it on the button, and a
   price in the description goes stale the day it changes.

## Before each release: feature freeze

From the first upload to Internal testing until production, change nothing
but fixes. Every new feature since October has changed the save format, and
each one is another thing the closed test has to prove. New features wait
for 1.0.1.

## Hosting the privacy policy

The policy is served from a small public repository of its own,
[itstrue54/gridiron-dynasty](https://github.com/itstrue54/gridiron-dynasty),
by GitHub Pages:

**https://itstrue54.github.io/gridiron-dynasty/**

That is the URL for Play Console. The page there is `site/index.html`. To
change the policy, change `privacy-policy.md` and `site/index.html` together,
update the date, and copy `site/index.html` over `index.html` in that
repository.

## Save compatibility

`data/src/test/kotlin/com/nflsim/data/M6SaveTest.kt` loads a real save from
the first playable build and plays it on through an offseason. Keep it passing:
a release that breaks old saves breaks every dynasty in progress.

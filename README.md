# Ground Control

**The mobile spec cockpit for [Mothership](https://github.com/atomikpanda/mothership).**

Ground Control is where rough intent becomes agent-ready specs — reviewed, refined, and
approved from your phone — then handed to coding agents to execute. It's the
human-in-the-loop surface for the Mothership workflow: you steer at the **spec** level
(upstream of the code), and agents do the implementation downstream.

## Why specs, not PRs

Reviewing a *spec* is higher-leverage than reviewing a *PR*: catching a wrong assumption
before any code is written saves the entire downstream loop. Ground Control puts that
review loop on your phone, talking to a running [`mship serve`](https://github.com/atomikpanda/mothership)
over your tailnet.

## The loop

```
Brainstorm → Spec Draft → Spec Review → Implementation Plan → Dispatch → Agent Work → Decision Queue
```

Ground Control owns the human touchpoints — reviewing and approving specs, answering open
questions, and triaging the decision queue — all backed by the structured `mship spec`
model exposed over `mship serve`.

### Daily review

- Capture opens in Home's selected workspace. A fresh all-workspace capture requires
  an explicit destination; a removed destination never silently switches elsewhere.
- Capture drafts persist their text, mode, and chosen destination in app-private
  storage, separately for each Home capture context. Returning restores the draft
  without sending it. Confirmed creation or **Discard draft** clears only that context.
  Retrying an unchanged Brainstorm after an uncertain failure reuses its submission key.
- Spec review starts with the workspace, lifecycle, and remaining acceptance criteria
  and open questions. Section links jump into the original proposal and review content;
  unavailable review data is not presented as zero remaining work.
- Home questions open a focused decision view with the supplied choices and free-text
  controls. It checks for a replaced or resolved prompt before submitting, shows a
  response receipt, and preserves Home's workspace selection when you go Back.
- Queue reconciles decisions against later human replies, even when a stale summary
  still marks them as needing a decision. Merely viewing a prompt does not answer it.
  Spec questions are reconciled per prompt and acknowledged server revision; later
  revisions and changed prompts remain actionable. An identical reopened prompt
  without a newer server revision is indistinguishable from a stale response.
- Expected offline workspaces use neutral availability text. Re-pairing and
  other actionable failures stay visible without blocking healthy workspaces.
- Projects preserves each workspace's identity and opens that workspace directly.
- Done shows available repository and pull-request metadata. Mothership retains
  this metadata before task cleanup; previously deleted metadata is not reconstructed.
- Commit evidence opens the repository's canonical commit page when its repository
  is unambiguous.
- Task phase summaries show the current stage and its position without pulsing.
  Missing phase data stays unavailable unless dispatch or completion is confirmed;
  missing activity data is labeled explicitly.
  The work console puts blockers and pending input before tasks, journal, and chat.
  Latest activity considers every linked task; tasks with unknown timestamps remain
  explicit, and pending decisions link to the available response controls.
- Task and Console journals use colored event markers and labeled metadata badges:
  green for passing tests, red for failures, amber for mixed results or warnings,
  and cyan for open questions. Question blocks stay distinct, and badges wrap with
  larger text. Journal prose is never interpreted as a status.
  Friendly dates are stacked above local times, including seconds and timezone,
  using the device locale and 12/24-hour preference. Invalid timestamps stay visible.
- Spec readiness chips and review actions wrap on narrow screens and at larger
  text sizes instead of squeezing labels or hiding the spec content.

## Status

**Android first** (Kotlin + Jetpack Compose); iOS (Swift + SwiftUI) follows.

- [`android/`](android/) — the Android app (in development)
- [`ios/`](ios/) — iOS app (planned)

Part of the Mothership family — coordinated via
[`mship-workspace`](https://github.com/atomikpanda/mship-workspace).

## Android releases

Every successful merge to `main` publishes `ground-control-v0.1.N.apk` under
[GitHub Releases](https://github.com/atomikpanda/ground-control/releases). In
[Obtainium](https://obtainium.imranr.dev/), add
`https://github.com/atomikpanda/ground-control` as the source URL; it selects the APK asset
from the latest release.

Android updates must use the same signing certificate. Back up the local key at
`~/.mothership/keys/ground-control-release.jks` and its credentials at
`~/.mothership/keys/ground-control-release.env`; both files must be mode `0600`.
`ANDROID_KEYSTORE_BASE64` is the single-line base64 JKS. The
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, and `ANDROID_KEY_ALIAS` secrets hold
the store password, key password, and alias.

To generate and inspect a recoverable release key, generate restricted password values in your
local shell, then run:

```bash
set -euo pipefail
install -d -m 700 "$HOME/.mothership/keys"
ANDROID_KEYSTORE_PASSWORD="$(openssl rand -hex 32)"
ANDROID_KEY_PASSWORD="$(openssl rand -hex 32)"
keytool -genkeypair -v \
  -storetype JKS \
  -keystore "$HOME/.mothership/keys/ground-control-release.jks" \
  -alias ground-control \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Ground Control, O=Atomik Panda" \
  -storepass "$ANDROID_KEYSTORE_PASSWORD" \
  -keypass "$ANDROID_KEY_PASSWORD"
(
  umask 077
  cat > "$HOME/.mothership/keys/ground-control-release.env" <<EOF
ANDROID_KEYSTORE_PASSWORD=$ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_PASSWORD=$ANDROID_KEY_PASSWORD
ANDROID_KEY_ALIAS=ground-control
EOF
)
chmod 600 "$HOME/.mothership/keys/ground-control-release.jks" \
  "$HOME/.mothership/keys/ground-control-release.env"
keytool -list -v \
  -keystore "$HOME/.mothership/keys/ground-control-release.jks" \
  -storepass "$ANDROID_KEYSTORE_PASSWORD"
gh secret set -f "$HOME/.mothership/keys/ground-control-release.env"
base64 -w 0 "$HOME/.mothership/keys/ground-control-release.jks" |
  gh secret set ANDROID_KEYSTORE_BASE64
```

Private key material and credentials must never be committed, logged, sent to pull-request
jobs, or copied into agent context.

## License

MIT — see [LICENSE](LICENSE).

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

## Status

Early scaffold. **Android first** (Kotlin + Jetpack Compose); iOS (Swift + SwiftUI) follows.

- [`android/`](android/) — the Android app (in development)
- [`ios/`](ios/) — iOS app (planned)

Part of the Mothership family — coordinated via
[`mship-workspace`](https://github.com/atomikpanda/mship-workspace).

## License

MIT — see [LICENSE](LICENSE).

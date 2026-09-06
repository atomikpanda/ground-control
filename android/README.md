# Ground Control — Android

Native Android app built with Kotlin and Jetpack Compose. The `app/` module contains
the UI, view models, workspace HTTP clients, and app-private preferences and capture drafts.

See the [daily review workflows](../README.md#daily-review) for capture, spec review,
decisions, and live work status. Build, lint, test, and capture tasks live in the
[root Taskfile](../Taskfile.yml).

From a Mothership workspace with a configured run host, build remotely with
`mship build --remote=<role> --repos ground-control --task <task-slug>`.

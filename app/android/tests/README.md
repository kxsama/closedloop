# Android monitoring regressions

The checks compile the production `Engine.kt` and `MonitorCadence.kt` on a JVM.
They cover idle scheduling, near-term starts, midnight, screen-off reminders,
effective-time accounting across lock/unlock and sleep, missed-plan settlement,
escalation, cooldown recovery, late arrival and completion.

v0.12 also covers first-tick expiration when opening 566 minutes late, preserving
tomorrow's recurring schedule, deep-copy plan editing, per-day edit cancellation,
preserving customized days, and unobserved time when monitoring resumes.

The explicit stop flag, disabled boot component, activity/keyboard layout and OEM
autostart behavior still require Android runtime testing; JVM checks do not prove
those platform behaviors.

v0.13 has 43 checks in total. Additional checks cover grace intervals counted
once, backward wall-clock changes, original plan snapshots, live work taking
precedence over disk checkpoints, cold restart gaps, changed app associations,
pause/resume, cross-day recovery without inventing unobserved days, and update
size/SHA-256/APK structure validation. Network failover, notification delivery and
background activity transitions still require Android runtime testing.

Prerequisites: JDK 17+, Python 3, and Kotlin compiler 2.0.21 already in a Gradle
dependency cache. From `app/android`:

```text
python tests/run_regression.py --cache <Gradle user home>/caches/modules-2/files-2.1
```

No external test dependencies are downloaded. These checks do not simulate Android
broadcast delivery, OEM process management or battery consumption. Those require
phone testing, especially reboot without opening the app, a screen-off scheduled
start, unlock during an active plan, and a day without an active plan.

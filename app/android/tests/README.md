# Android monitoring regressions

The checks compile the production `Engine.kt` and `MonitorCadence.kt` on a JVM.
They cover idle scheduling, near-term starts, midnight, screen-off reminders,
effective-time accounting across lock/unlock and sleep, missed-plan settlement,
escalation, cooldown recovery, late arrival and completion.

Prerequisites: JDK 17+, Python 3, and Kotlin compiler 2.0.21 already in a Gradle
dependency cache. From `app/android`:

```text
python tests/run_regression.py --cache <Gradle user home>/caches/modules-2/files-2.1
```

No external test dependencies are downloaded. These checks do not simulate Android
broadcast delivery, OEM process management or battery consumption. Those require
phone testing, especially reboot without opening the app, a screen-off scheduled
start, unlock during an active plan, and a day without an active plan.

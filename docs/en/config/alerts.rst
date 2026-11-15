Robot alerts
============

**Config → Robot alerts** sends a notification when something goes wrong:

* **Lost contact with the robot.**
* **Diagnostics in ERROR or STALE**, and optionally **also warnings (WARN)**. The same alert is
  repeated at most once a minute.
* **Lifecycle nodes lost, finalized or in error.**

The network is checked every 3 seconds. Alerts are off until you turn them on, here or with the
switch at the top of the Alerts tab. They work while the app is open, and in the background if
:doc:`background` is on. **Send a test notification** checks that notifications reach you;
Android 13 and newer ask for permission the first time.

Your own rules on any topic (a value crossing a threshold, a topic that stops) are in the
:doc:`Alerts tab <../tabs/alerts>`, which also keeps the history of every alert.

On the PC
---------

Alerts are desktop notifications (with ``notify-send``) while VizMini2 is open. Their switches are
at the top of the Alerts tab: **Notifications on this PC**, **When contact with the robot is lost**
and **When a diagnostic turns ERROR**, all on by default. Your topic rules work the same as on the
phone.

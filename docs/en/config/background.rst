Background connection
=====================

**Config → Keep connection alive in background** (phone only). While the app is in the background
its node stays on the network, with a notification that has a **Stop keeping alive** button. Robot
alerts keep working.

Tabs are paused anyway: **teleop never publishes in the background**, so the robot can't keep
moving because you switched apps.

On the PC there is no such setting: the app stays connected while its window is open.

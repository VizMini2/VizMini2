Alerts
======

.. clip:: alerts

Get a notification when something goes wrong on the robot, even with the app in the background.

* **Rules** on any topic: a field above or below a threshold (``/battery_state percentage < 0.2``,
  a temperature, an error code) or no messages for a number of seconds (``/scan`` stopped). Pick
  the topic and the field from the network, or type them; each rule is a warning or an error.
* Each rule shows whether it is firing now and the last value. A rule notifies when it starts to
  hold and again when it recovers, not on every message.
* **Latest notices** keeps the history of every alert: your rules, diagnostics turning *ERROR* and
  losing contact with the robot.

On the phone the switch at the top turns alert notifications on (they are off until you do); with
the *connection service* (Configuration) they keep coming with the app in the background. On the PC
they are desktop notifications while VizMini2 is open.

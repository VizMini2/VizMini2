Get alerted when something goes wrong
=====================================

Goal: a notification when the battery runs low, the laser stops publishing, a temperature goes
too high or the robot drops off the network, even with the app in the background (phone) or behind
other windows (PC).

.. shot:: pc_alerts
   :align: center
   :caption: Three rules: one is firing (the temperature), the other two are fine.

1. Add an **Alerts** tab with **+**. At the top are the general switches: on the phone,
   *Alert notifications on this phone* (they also need the background connection,
   :doc:`../config/background`, to work with the app closed); on the PC, notifications, lost
   contact and diagnostics in ERROR, all on by default.
2. Tap **Add rule**. For a low battery: **Topic** ``/battery_state``, **Value**, **Field**
   ``percentage``, the comparison ``<`` and **Threshold** ``0.2``; **Warning** or **Error**.
   The field can be any number in the message, also inside arrays (``status[0].level``).
3. Add another for a sensor that stops: **Topic** ``/scan``, **No messages**, **Seconds without
   messages** ``3``.
4. Each rule shows its live value (*now 0.81*) and a dot: green when fine, orange or red when it
   fires, grey when nobody is watching. Switch a rule off with its switch; **Edit** changes or
   deletes it.
5. When a rule fires you get a notification (and another when it recovers); **Latest notices**
   keeps the history, with lost contact and diagnostics too.

Rules are watched by the phone's connection service or, on the PC, while VizMini2 is open. More in
:doc:`../tabs/alerts` and :doc:`../config/alerts`.

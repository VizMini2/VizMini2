Indicators
==========

.. clip:: indicators

Live values from any topic, as numbers, gauges, bars, lights, text or tables. Each indicator
subscribes to a topic and shows **one field** of the message, or every field in a table.

Tap **Add indicator**, type the topic or use **Browse the network…**, pick the message type, how to
show it and the **field**. The field is written as in ``ros2 topic echo --field``: ``data``,
``battery.percentage``, ``ranges[0]``, ``status[0].message``… Suggestions include the first element
of each array; the index can be changed by hand. Times (``header.stamp``) are shown in seconds.

Paths also take:

* **negative indices**, counted from the end: ``poses[-1].pose.position.x`` is the last pose;
* **filters** in braces after an array, which take the first element that matches:
  ``status{name=="motor"}.level``, ``detections{id==3}.score``;
* **filters** at the start, which use only the messages that match: ``{mode==2}.speed``. The
  indicator keeps its last value while messages don't match.

A filter compares a field with ``==``, ``!=``, ``<``, ``<=``, ``>`` or ``>=`` and a number,
``true``/``false`` or a text (with or without quotes).

How to show it
--------------

* **Number**: the value in large type, with its unit.
* **Gauge**: an arc from *minimum* to *maximum* with a needle.
* **Bar**: a horizontal bar from *minimum* to *maximum*.
* **Light**: turns on when the value is true (or non-zero, or a non-empty text), when it is greater
  than or equal to a threshold, less than or equal, or equal to a number or a text. You choose the
  colors for on and off (for example, red when the battery drops below 30 %).
* **Text**: a string, a mode, a status message…
* **Table**: every field of the message, or of a submessage (``pose.pose.position``). Arrays are
  summarized with their first elements and how many there are.

Options
-------

* A **name** (optional: otherwise, the topic and the field).
* **Unit** and **decimals** (empty: depends on the field type; integers have none).
* **Reception**: *Best effort* works with any publisher; *Reliable* asks for every message to be
  delivered (it doesn't connect to best-effort publishers); *Transient local* also gets the last
  value of latched topics (like ``/map`` or ``/robot_description``).
* **Mark as stale after** some seconds: if the topic stops publishing, the card is dimmed and says
  how long it has been without data. With 0 it is never marked.
* A color for the value, the gauge or the bar.

The cards fill as many columns as the width allows; texts and tables take a whole row. Each
indicator's ⋮ menu has *Edit*, *Duplicate*, *Move earlier*, *Move later* and *Delete*.

Indicators work great as :doc:`dashboard` widgets: in edit mode on the phone, the widget's **+** adds
another one, and an empty widget adds one when you tap it. Demo mode includes an
*Indicators* tab with the simulated robot's battery, speed and position.

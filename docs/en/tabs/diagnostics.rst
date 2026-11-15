Diagnostics
===========

.. clip:: diagnostics

Four views in one tab; choose which one to start with when you create it and switch at the top.
Every view has *Pause*, *Resume* and *Clear*, and a status line with what has arrived.

Log
   ``/rosout`` with a minimum level (*DEBUG*, *INFO*, *WARN*, *ERROR*, *FATAL*; *INFO* and above by
   default), a filter by node or text, and the last 2000 lines. The **nodes** button shows only the
   nodes you pick. Tap a line to see it whole, with its exact time, source file, line and function;
   from there you can **copy** it or, on the phone, **share the log** (what the filters leave) as
   text.

Diagnostics
   ``/diagnostics`` like ``rqt_robot_monitor``: the worst level first, with a count per level.
   Entries with no news for 10 seconds turn *STALE*. Tap an entry to unfold its values and
   hardware ID.

Echo
   The live messages of any topic. Type the topic and tap ✓ (well-known topics fill in the type),
   pick the type with **Type…** from a list of common ones and the QoS with **QoS**: *Best effort*
   (default) receives from any publisher, *Reliable*, or *Transient local* to get the last message
   kept by the publisher (for example ``/map``). For other types, use :doc:`message` or
   :doc:`topics`.

Plot
   Up to four numeric fields of a topic, chosen with **Fields**, over the last 30 seconds. Messages
   with no numbers (maps, images, point clouds) can't be plotted. For more, use a :doc:`plot` tab.

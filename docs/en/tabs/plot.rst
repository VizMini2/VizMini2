Plot
====

.. clip:: plot

Plots of any numeric field: several panels with the same time axis, series from
different topics, XY plots and the data of a recorded bag.

Panels and series
-----------------

A tab has up to three panels (⋮ menu → *Add panel*) with up to six series each. In each panel,
**Add series** asks for the
topic (it suggests the ones on the network, or the ones in the open bag), the message type and the
**field**, written as in :doc:`indicators`: ``twist.twist.linear.x``, ``ranges[-1]``,
``status{name==motor}.level``… Each series
has a name, a color and a transform:

* **Derivative**: how much it changes per second (of a position, the speed).
* **Moving average** of the last *N* samples, to smooth it.
* **Integral**: the running sum over time (of a speed, the distance).
* **Scale** and **offset**: value × scale + offset (57.2958 turns radians into degrees).

Formulas
--------

Choose **Formula** instead of *Topic field* to compute a series from others.
Each series goes in braces with its name in the legend (tap it in the dialog to
insert it):

* ``{cmd} - {odom}``: the error between a command and the measure;
* ``hypot({vx}, {vy})``: the speed from its components;
* ``deg(atan2({y}, {x}))``, ``{speed} * 3.6``…

You can use ``+ - * / % ^``, parentheses, ``pi``, ``e`` and ``abs sqrt exp log log10 sin cos tan
asin acos atan atan2 hypot min max floor ceil round sign deg rad``. The value is computed whenever
the first series in the formula has a new sample, with the latest value of the others. A formula
can use another formula, and it keeps working if you rename a series it uses. Transforms, scale
and offset apply to formulas too.

Lua scripts
-----------

For what a formula can't do (keeping a state, conditions, loops), choose **Script (Lua)**.
Pick the **input series** (the first one sets the time), write the
**global variables** (run once) and the body of ``function(time, value, v1, v2…)``: ``value`` is
the first input, ``v1``, ``v2``… the latest value of the others and ``time`` is in seconds. Return
a number, or ``nil`` to skip that sample. For example, the distance travelled from a speed with a
reset when it stops::

   -- globals
   dist = 0
   last = nil

   -- function
   if last ~= nil then dist = dist + value * (time - last) end
   last = time
   if math.abs(value) < 0.01 then dist = 0 end
   return dist

Scripts run with ``math``, ``string`` and ``table``, without files or the system.

Panels
------

Tap a series to edit or remove it. From the panel's ⋮ menu, **Show as XY (x against y)** shows the first
series (x) against the second (y) (an XY panel holds those two series), for example the trajectory in ``/odom``. With **Same scale on x and y**
the trajectory is not distorted. **Show over time** goes back to a normal panel, and **Remove panel**
deletes it.

**Show as state transitions** draws each series as a
strip that changes color whenever its value changes, with the value written on it: a mode, a
navigation status, a battery state… In these panels a series can also be a **text** field
(``status.message``, ``mode``): each different text gets its own color.

**Show as spectrum (FFT)** draws the amplitude of each frequency in what the time window holds (the
legend says the peak), useful to find vibrations; **Show as histogram** draws how the values are
distributed, with the mean and the standard deviation in the legend. In XY panels, **Points instead
of a line** draws a scatter plot.

Moving through time
-------------------

* **Pinch** to change the time window (also with the duration button, from 5 s to 15 min).
* **Drag** right to go back: the plot stops following new data. The pause button does the same and
  the play button follows the data again.
* **Tap** an instant to place the cursor: each panel's legend shows the value of every series at
  that moment. **Double tap** removes the cursor and follows the data again.
* **Touch and hold** to place a second cursor and **measure**: the area between both is shaded and
  the legend shows the time between them (Δt) and, for each series, its value at the second cursor
  and how much it changed (Δ).

On the PC, the mouse wheel changes the time window, dragging moves through time, a click places the
cursor, holding the button down places the second one and a double click removes them.

**Statistics** (⋮ menu) adds below each panel over time the minimum, maximum, mean, standard
deviation (σ), rate (Hz) and number of samples of each series, for what is visible or, with two
cursors, for what is between them. **Clear data** empties the live plots.

The time of each sample is when it arrived. With **Time from header.stamp** (⋮
menu) it is the ``header.stamp`` of each message instead (for messages without a header, still when
it arrived). That's the moment the data was measured, and it lines up topics that arrive with
different delays. If the robot runs on simulated time the window follows the latest data, not the
device's clock. In bags it replaces the time when each message was recorded.

Bags
----

In the source button (*Live*) choose one of the bags in the :doc:`bags` tabs. It is read whole
without publishing anything, using each message's time in the bag, and a timeline appears at the
bottom to move the cursor. The play button goes through it in real time.

Long bags don't need to fit in memory: series with more than 150 000 messages are first shown
reduced (the minimum and maximum of each stretch, which is what you see with a wide window, and the
source button says *overview*). When you zoom in, only the visible stretch is read again at full
resolution.

**Import CSV…** in the same button opens a CSV file as a source (on the PC, with the system's file
dialog): the column called ``time`` (or ``timestamp``, ``stamp``, ``t``, ``__time``; otherwise the
first one) is the time in seconds and the rest are series. Add them with ``csv`` as the topic and the
column name as the field.

Other sources: UDP, MQTT and ZeroMQ
-----------------------------------

A series can also read JSON messages that don't come from ROS.
Write the source as the topic, and the field as a path in the JSON (``speed``,
``battery.pct``, ``motors[-1].current``, filters too):

* ``udp://0.0.0.0:9870``: datagrams with one JSON each, sent to the phone or PC on that port;
* ``mqtt://BROKER:1883/robot/#``: an MQTT broker and the topic (wildcards allowed), with
  ``mqtt://user:password@BROKER/…`` if it asks for them and ``mqtts://`` (port 8883) for TLS,
  checked against the system's certificates; a payload that is a bare number is read as ``value``;
* ``zmq://HOST:9872``: a ZeroMQ PUB socket (``zmq://HOST:9872/prefix`` to subscribe to a prefix
  only; with several parts, the last one is the JSON).

They reconnect by themselves, and a problem shows up in the legend of the series.

Export
------

*Export CSV* in the ⋮ menu saves every series to a CSV (a time column and one per series, with the
last value of each at every instant). The phone shares it; the PC saves it in *~/Documents* and says
where. A reduced bag is read again to export it at full resolution.

Plot tabs can also be exchanged with other plotting tools: their plot layouts (plots, XY plots and Lua
custom series) are imported as Plot tabs, and a Plot tab is exported as a plot layout. See
:doc:`../config/import-export`.

The tab can also be added to a :doc:`dashboard`; dropping a topic or a field on a Plot widget adds a
series. Demo mode includes one with the simulated robot's speeds and trajectory.

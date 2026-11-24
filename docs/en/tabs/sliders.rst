Sliders
=======

.. clip:: sliders

Sliders that publish a number: a speed, an angle, a brightness, a setpoint… Each slider moves
**one numeric field** of any message (``data`` of ``std_msgs/Float64``, ``linear.x`` of a
``Twist``…) and sends the other fields with fixed values, the same way as the :doc:`buttons` tab.

Tap **Add slider**, type the topic or use **Browse the network…** (it only lists topics whose type
has a numeric field), choose the message type and the **field** the slider moves. Below, fill in
the other fields of the message if they need a value other than the default.

Options
-------

* **Minimum**, **maximum**, **step** (0 = continuous) and **initial value**. Integer fields are
  rounded to whole numbers and the range must fit in their type (for example 0–255 for ``uint8``).
  For ``linear.*`` and ``angular.*`` fields a speed range around 0 is suggested.
* **When to publish**:

  * *While dragging*: at the rate you choose (1–50 Hz, 10 by default, also when your finger stays
    still) and once more when you let go. Right for speeds, which most robots stop when they stop
    receiving.
  * *Only when released*: a single message with the final value. Right for setpoints.

* **Return to the initial value when released** (only while dragging): letting go publishes the
  initial value, so with 0 the robot stops. It also happens if you leave the tab while dragging.
* A color for the track and the thumb.

Tap the big number (or *Set exact value* in the menu) to type a value: it is adjusted to the range
and the step and published once. Each slider's ⋮ menu also has *Edit*, *Duplicate*, *Move earlier*,
*Move later* and *Delete*. On the PC, drag the slider with the mouse.

The tab never publishes while it is hidden, and sliders can also be added to a :doc:`dashboard`.
Demo mode includes a *Sliders* tab that drives the simulated robot with *Forward* and
*Turn*.

Message types
-------------

Any message type bundled with the app can be used (the same list as in :doc:`buttons`), and
:ref:`your own types <custom-types>`.

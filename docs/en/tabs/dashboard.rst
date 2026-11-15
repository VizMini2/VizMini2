Dashboard
=========

.. clip:: dashboard

Your own screen, built from other tabs as widgets: a 3D view, several cameras, a joystick,
buttons, diagnostics… all at once. The phone and the PC use the same dashboard.

* Tap **Edit**, then **Widget**, and choose any tab type (except another dashboard). Tap **Done** to
  use the widgets again.
* Widgets stay exactly where you drop them, and they can overlap: the one you moved last is on top,
  and a widget that covers another is drawn see-through with a colored border (a joystick over the
  3D view, a small camera in a corner…). The board is 12 cells wide at 100 % zoom and grows to the
  right and down as you place widgets. The smallest widget is 3 × 2 cells.
* In edit mode, drag a widget to move it, drag the round button at its bottom-right corner to resize
  it, or tap the red trash button to remove it. **Undo** (the arrow in the bar) reverts the last move,
  resize or removal; a removed widget comes back with its settings.
* On the phone, in edit mode, Buttons, Sliders and Indicators widgets also show a **+** to add a
  button, slider or indicator to them (inside a dashboard they hide their own "Add" bar). An empty
  one also adds when you tap it.
* **Zoom**: pinch with two fingers to zoom the board and drag with two fingers to move around it
  (two-finger gestures on a joystick belong to the joystick). The percentage in the bar takes it back
  to 100 %. The zoom is remembered for each dashboard on each device.
* The pause button in the bar freezes every widget at once (messages keep arriving and the latest is
  shown on resume). While a bag plays from the :doc:`bags` tab, its time bar appears here and
  pausing, changing the speed or jumping moves all the widgets together.

Each widget keeps its own settings, just like a normal tab.

On the PC, zoom with :kbd:`Ctrl` + wheel and use :kbd:`Ctrl+0` to go back to 100 %, :kbd:`Ctrl+E` to
enter or leave edit mode, :kbd:`Esc` to leave it and :kbd:`Ctrl+Z` to undo. Right click a widget for
*Bring to front*, *Edit* and *Remove widget* without entering edit mode.

Dragging topics in
------------------

Drag a topic from a Topics widget (on the phone, long-press it first), or a field of its message, and
drop it on the dashboard:

* on an empty spot, it becomes a new widget there: an image becomes a Camera, something RViz draws a
  3D view, diagnostics a Diagnostics widget and a number a Plot;
* on an RViz widget it adds a display, and on a Plot widget a series (if you dropped a whole topic,
  the series editor opens to choose the field).

Ready-made dashboards
---------------------

The **+** screen has *Ready-made dashboards* that add a dashboard with its widgets already placed and
configured, using the topics found on the network when there are any: **Navigation** (3D with map,
laser, plan and footprint, joystick, speed and diagnostics), **Camera robot**, **Monitoring**
(diagnostics, plots, indicators, node graph and TF tree), **Manipulation (MoveIt)** and
**Showcase** (almost everything the app can do, made for ``tools/fake_robot.py``). On the phone, demo
mode also includes a *Demo dashboard*.

Panel layouts (``.json``) can be imported as a dashboard, and a dashboard can be exported as a panel
layout: see :doc:`../config/import-export`.

.. container:: vm-clear

   See :doc:`../tutorials/dashboard`.

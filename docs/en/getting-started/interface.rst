The interface
=============

This page is about the phone. On the PC the same pieces are in a sidebar: see
:doc:`desktop`.

.. shot:: phone_tab_menu
   :caption: Long press on a tab: rename, duplicate, share, delete or export it.

* **Bottom bar:** *Home* and *Config* are always there. Every tab you create appears next to them,
  and **+** adds a new one. Long-press a tab to rename, duplicate, share, delete or export it.
  **Share** sends the tab (a dashboard with its widgets) as a ``.json`` file; whoever gets it adds
  it with **+** → *Import a tab, an RViz file or a layout*. **Export as…** (an RViz 2 file, a panel
  layout or a plot layout) appears when the tab has something those files can hold (:doc:`../config/import-export`).
* **Top bar:** the name of the current tab, the record button (:ref:`recording-video`) and the
  connection badge (*Domain N*). Tap the badge to open Config.
* **Home:** the connection status; what is on the network (topics, services, actions, nodes and
  lifecycle nodes); shortcuts to the node graph and the TF tree; your workspace; the optional
  account card (:doc:`account`); and cards for the quick tour, this documentation, support, the
  changelog, contact and privacy.
* **Config:** network, background connection, robot profiles, alerts and language.

.. raw:: html

   <div class="vm-clear"></div>

Adding a tab
------------

.. shot:: phone_add_tab
   :caption: The "+" screen: import, ready-made dashboards and the tab types.

Tap **+**. At the top, *Import a tab, an RViz file or a layout…* adds a shared tab or
turns another tool's configuration into tabs. Then come the ready-made dashboards (*Navigation*,
*Camera robot*, *Monitoring*, *Showcase*, *Manipulation (MoveIt)*), which create a dashboard
with its widgets in one go. Below, the tab types are grouped under *Visualize*, *Control*,
*Inspect the network* and *Record*. Tap one to watch a short video and read what it does, give it a name (optional) and tap
**Create**. Some types ask for one setting first, such as the camera topic or the velocity topic.

Quick tour
----------

Home → **Quick tour** walks you through the main screens in a minute.

.. raw:: html

   <div class="vm-clear"></div>

.. _recording-video:

Recording a video
-----------------

To show how the robot behaves, record what is on the screen: the record button at the top of the
phone (Android asks for permission each time; the notification has *Stop*) saves an MP4 to
*Movies/VizMini2*. On the PC, **Record video** in the sidebar (or :kbd:`Ctrl+Shift+R`) records the
window, 3D view included, to *~/Videos/VizMini2* (*Videos\\VizMini2* on Windows; it needs
``ffmpeg``).


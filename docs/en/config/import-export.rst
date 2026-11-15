Import and export
=================

VizMini2 reads and writes the configuration files of the usual ROS tools, so you can bring your
views over and take them back.

**Import:** **+** (*Add* on the PC) → *Import a tab, an RViz file or a layout…*. The format is
recognized by its content:

* **RViz 2** (``.rviz``): a new RViz tab with its displays (33 types, from Grid and TF to
  MarkerArray, Camera, Trajectory and PlanningScene), fixed frame, background, view and the topics of
  the tools. From an RViz tab, its menu also imports into that tab.
* **Panel layouts** (``.json``): the layouts that web-based ROS visualization tools export, and the
  ones their SDKs generate. They become a dashboard with a widget per panel, placed as in the layout.
  3D → RViz (its visible topics as displays), Plot and State Transitions → Plot (message paths, XY,
  ``@derivative``…), Image → Camera, Raw Messages → Message, Teleop, Gauge and Indicator →
  Indicators, Publish → a button with the same message, Map → GPS, Diagnostics, Log, Parameters,
  Topic Graph and Transform Tree. A Tab panel brings all its tabs: the open one in the dashboard and
  each of the others as a dashboard of its own (*layout · tab*).
* **Plot layouts** (``.xml``): their plots, XY plots and **Lua custom series** (the same
  ``function(time, value, v1…)`` as here) as Plot tabs, or a dashboard of them.
* VizMini2's own shared tabs (``.vizmini2.json``).

What has no equivalent is listed before adding, so nothing is lost silently.

**Export:** in a tab's menu (long press on the phone, right click in the sidebar on the PC),
*Export as…*, depending on what the tab has: an RViz tab as an RViz 2 file (``.rviz``); any tab or
dashboard as a panel layout (``.json``); Plot tabs (or a dashboard's plots) as a plot layout
(``.xml``), which subscribes to the right topics when it is opened.

.. note::
   Plot layouts do not say each topic's message type, and their curve names mix topic and field
   (``/odom/pose/pose/position/x``). VizMini2 uses the topics the layout subscribes to and those on
   the network to tell them apart; open the file with the robot on the network, or check the notes
   after importing.

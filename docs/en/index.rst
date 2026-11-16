VizMini2
========

**ROS 2 in your pocket, and on your desk.** VizMini2 lets you see, drive and inspect ROS 2 robots
from an **Android phone** or a **Linux PC**: a 3D view like RViz, live cameras and video, joysticks,
your own buttons for services and actions, MoveIt and ros2_control, maps for Nav2, plots, alerts,
node parameters, lifecycle nodes, bags and much more. It talks to your robots directly over your
network (DDS), with no bridge or server on the robot (Zenoh and foxglove_bridge work too).

No account needed. Not sure yet? **Try the demo** on the phone: a simulated robot that runs only
inside it, with everything working.

.. clip:: dashboard
   :align: center
   :caption: A dashboard with the 3D view, camera, joystick and diagnostics.

Phone or PC
-----------

Both apps have the same tabs and settings, and your tabs, profiles and layouts move freely between
them. The manual covers both: where they differ (touch or mouse, gamepad or keyboard, share sheet
or file dialog), each page says so.

.. raw:: html

   <div class="vm-grid">
     <div class="vm-card vm-entry"><b>On the phone (Android 8.0+)</b>Install the APK, try the demo robot and connect to yours. <a href="getting-started/install.html">Install on Android</a></div>
     <div class="vm-card vm-entry"><b>On the PC (Ubuntu 20.04+)</b>A <code>.deb</code> or <code>ros2 run vizmini2 vizmini2</code>: a window with a sidebar, keyboard shortcuts and drag and drop. <a href="getting-started/desktop.html">The PC app</a></div>
   </div>

What it can do
--------------

.. raw:: html

   <div class="vm-grid">
     <div class="vm-card"><b>See</b>3D view with 34 display types (RViz's and NavMap's multi-floor maps), cameras and H.264/H.265 video, the TF tree, the node graph, GPS maps and your own dashboards. <a href="tabs/rviz.html">RViz tab</a></div>
     <div class="vm-card"><b>Drive</b>On-screen joysticks, a gamepad or the keyboard, with safety stops; Nav2 goals and waypoints from the map. <a href="tabs/teleop.html">Teleop</a></div>
     <div class="vm-card"><b>Move arms</b>Plan and execute with MoveIt, and switch or command ros2_control controllers. <a href="tabs/moveit.html">MoveIt</a> · <a href="tabs/controllers.html">Controllers</a></div>
     <div class="vm-card"><b>Act</b>Buttons and sliders that publish, call services or send action goals. <a href="tabs/buttons.html">Buttons</a></div>
     <div class="vm-card"><b>Maps</b>Save maps, drive SLAM Toolbox, clear costmaps and draw keepout and speed zones for Nav2. <a href="tabs/maps.html">Maps</a></div>
     <div class="vm-card"><b>Plot and watch</b>Plots with formulas and Lua, gauges, and alerts when a value crosses a limit or a topic goes silent. <a href="tabs/plot.html">Plot</a> · <a href="tabs/alerts.html">Alerts</a></div>
     <div class="vm-card"><b>Inspect</b>Nodes and parameters, topics with echo and rate, services, actions and lifecycle nodes. <a href="tabs/nodes.html">Nodes</a></div>
     <div class="vm-card"><b>Record</b>MCAP bags compatible with ros2 bag, and screen videos to show what happened. <a href="tabs/bags.html">Bags</a></div>
     <div class="vm-card"><b>Share and migrate</b>Profiles as files or QR codes; tabs to and from RViz 2 files and layouts of other tools. <a href="config/import-export.html">Import and export</a></div>
   </div>

Where to start
--------------

* New here? :doc:`getting-started/install` (phone) or :doc:`getting-started/desktop` (PC), then
  :doc:`getting-started/demo` and :doc:`getting-started/connect`.
* Coming from RViz or another visualization tool? :doc:`tutorials/import-configs`.
* Looking for a specific screen? Every tab is described under **Tabs** in the menu, with a video.
* Something not working? See :doc:`help/troubleshooting` or :doc:`help/contact`.
* Want to build it or help? :doc:`developers/index`.

.. toctree::
   :maxdepth: 2
   :caption: Getting started
   :hidden:

   getting-started/install
   getting-started/desktop
   getting-started/account
   getting-started/demo
   getting-started/connect
   getting-started/interface

.. toctree::
   :maxdepth: 2
   :caption: Tutorials
   :hidden:

   tutorials/drive-demo
   tutorials/visualize-robot
   tutorials/nav2
   tutorials/dashboard
   tutorials/buttons
   tutorials/record-bag
   tutorials/lifecycle-params
   tutorials/moveit
   tutorials/controllers
   tutorials/maps-zones
   tutorials/alerts
   tutorials/import-configs

.. toctree::
   :maxdepth: 2
   :caption: Tabs
   :hidden:

   tabs/visualize
   tabs/control
   tabs/inspect
   tabs/record

.. toctree::
   :maxdepth: 2
   :caption: Configuration
   :hidden:

   config/network
   config/profiles
   config/import-export
   config/alerts
   config/background
   config/language

.. toctree::
   :maxdepth: 2
   :caption: Account
   :hidden:

   accounts/delete

.. toctree::
   :maxdepth: 2
   :caption: Developers
   :hidden:

   developers/index

.. toctree::
   :maxdepth: 2
   :caption: Help
   :hidden:

   help/troubleshooting
   help/faq
   help/requirements
   help/limitations
   help/contact
   help/privacy
   changelog

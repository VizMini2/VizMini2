Bags
====

.. clip:: bags

Record topics to MCAP files and play them back.

Record
------

Tap **Record**, choose the topics (or **All**) and tap **Record**. While it records you see the
time, the messages and the size, topic by topic. Tap **Stop** when you're done. Recording and
playback go on if you switch to another tab.

Files are saved as ``vizmini2_<date>_<time>.mcap`` (uncompressed, ROS 2 profile) and open in
``ros2 bag`` and in any tool that reads MCAP. Only topics whose type the app knows can be recorded; the others are not
listed.

Play
----

Tap **Play** on a recording to publish its messages again at 0.5×, 1× or 2×, with loop and pause.
Drag the progress bar to jump to any instant, also while paused. Jumping forward republishes the
last message of latched topics (``/tf_static``, ``/map``, ``/robot_description``…) so the 3D view
keeps the map and the robot. Topics whose type the app doesn't know are skipped.

With **Play only on this phone**, the bag goes straight to the app's tabs, without DDS (and the
connection switches to an isolated one, so nothing from the network gets mixed in): the 3D view,
cameras, plots, indicators… all see the same bag at the same moment, and nothing goes to the network
or reaches the robot. Messages are delivered as they are in the bag, so even large clouds and images
arrive whole, topics of types the app doesn't bundle are not skipped, and plots use the bag's time.
While it plays on the phone, a **timeline** appears above the tabs, on every tab: pause, move to any
moment or stop from there. Your connection comes back when playback stops.

Dashboards also show a playback bar while any bag plays, with pause, a slider to jump, the speed
(tap it to go through 0.25× to 4×) and stop. To plot a bag without publishing anything at all, open
it in a :doc:`plot` tab.

Import and share
----------------

**Import** opens ``.mcap`` files, uncompressed or compressed with lz4 or zstd (the default of many
ROS 2 setups). ``.db3`` (SQLite) bags are not supported. **Share** sends a recording to another app
(email, Drive…), and **Delete** removes it.

On the PC the recordings are in ``~/.local/share/vizmini2/bags``: **Import** opens a file dialog and
**Share** shows where the file is. The *Play only on this phone* option works the same there, and the
playback bar stays under whichever tab is open.

.. container:: vm-clear

   See :doc:`../tutorials/record-bag`.

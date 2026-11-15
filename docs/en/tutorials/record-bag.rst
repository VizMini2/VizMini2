Record and replay a bag
=======================

Goal: record what the robot sees and look at it later on a computer.

1. Tap **+ → Bags**.
2. Tap **Record**, choose the topics (or **All**) and tap **Record** again. The card shows the
   time, the number of messages, the size and the messages per topic.
3. Tap **Stop**. The recording appears in the list.
4. **Share** it to your computer and open it with any tool that reads MCAP, or with::

      ros2 bag info vizmini2_2026-10-09_12-30-05.mcap
      ros2 bag play vizmini2_2026-10-09_12-30-05.mcap

5. Or tap **Play** to publish it again from the phone (0.5×, 1× or 2×, with loop), for example to
   test RViz displays without the robot. With **Play only on this phone** nothing goes to the
   network: only the app's own tabs see it.

Only topics whose type the app knows are recorded. **Import** adds MCAP bags recorded elsewhere
(for example with ``ros2 bag record -s mcap``) to play them.

On the PC
---------

The same steps work. Bags are saved in ``~/.local/share/vizmini2/bags``; **Share** shows where the
file is, so you can open it right there with ``ros2 bag``.

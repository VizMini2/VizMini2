Behavior tree
=============

.. clip:: behavior_tree

Watch a behavior tree run live, like Groot2's monitor mode: which node is running, what succeeded
and what failed. The tree is drawn top-down. Each node is a box with a stripe for its category
(control, decorator, action, condition, subtree), its name and its type. The border shows its state:

* **orange**: running;
* **green**: success;
* **red**: failure;
* faded green or red: the node is idle again, and that is how it ended last time. You can see
  which branch just ran even after the tree has restarted.

Drag to move, pinch to zoom, double tap (or the frame button) to fit the whole tree. Tap a node to
see its type, its state and its ports (the attributes in the XML). Tap a **SubTree** to fold or
unfold it. The book button shows the latest state changes instead, newest first, and the
**blackboard** button shows the values in the tree's blackboards (Groot2 only), refreshed twice a
second.

On the PC, drag with the mouse to move, zoom with the wheel and double click to fit the tree.

**Breakpoints** (Groot2 only): select a node and tap **Breakpoint here**. When the tree is about to
run that node it stops (the node gets a red dashed border and the top line says where it is
stopped) until you tap **Continue**; it stops there again on the next pass until you remove the
breakpoint. While there are breakpoints, state changes are worked out from the polled states
instead of recorded. Leaving the tab or changing the source lets the tree go on and removes them,
so the robot is never left stopped.

Tap the source at the top to choose where the tree comes from.

Groot2 (BehaviorTree.CPP 4)
---------------------------

The robot's program must create a ``BT::Groot2Publisher`` for its tree, the same one Groot2 uses:

.. code-block:: cpp

   auto tree = factory.createTree("MainTree");
   BT::Groot2Publisher publisher(tree, 1667);  // 1667 is the default port

Write the robot's IP (or ``IP:port`` if it isn't 1667). The first time, the address of the
connection settings is suggested: the bridge's host, or the first discovery peer. The tab asks for
the whole tree once, with its subtrees, and then for the state of every node ten times a second. If
the program loads another tree, it is fetched again. If the connection drops, the tab keeps
retrying.

This is a plain TCP connection (ZeroMQ) to the robot, not ROS: it doesn't use the domain or the
bridge, so the phone or PC must reach that port. Groot2Publisher serves one viewer at a time, so close
Groot2 if it is connected.

Nav2 log topic
--------------

Nav2's ``bt_navigator`` doesn't run a Groot2Publisher, but it publishes every state change on
``/behavior_tree_log`` (``nav2_msgs/BehaviorTreeLog``). With this source the tab subscribes to
that topic (over DDS or the bridge, like any other). The log doesn't include the shape of the tree,
so the nodes are listed in a column as they appear, in tree order, each with its state.

To see the real tree, **load its XML** in the same dialog (the ``.xml`` that bt_navigator runs, such
as ``navigate_to_pose_w_replanning_and_recovery.xml``; on the PC, with the system's file dialog): it
is drawn like with Groot2, and each change in the log lights up its node. On **Humble**, tick the option: its log has no node ids, so each
change goes to the first node with that name.

Lifecycle nodes and parameters
==============================

Goal: bring up a lifecycle node and tune a parameter, without a terminal.

Lifecycle
---------

1. Tap **+ → Lifecycle**, keep **Add the N lifecycle node(s) found now** checked and tap **Create**
   (in the demo: ``/camera_driver``). Later, **Add node** adds more.
2. Each node shows its state. Tap **Configure**, then **Activate**. The state
   changes live, even when another program (like Nav2's lifecycle manager) changes it.
3. **Deactivate** pauses the node; **Cleanup** takes it back to *unconfigured*; **Shutdown**
   finalizes it (after you confirm).

Parameters
----------

1. Tap **+ → Nodes** and tap a node (in the demo: ``/demo_robot``).
2. The inspector lists its parameters with their type, value and description.
3. Tap one to change it. The node may reject a value (out of range, read-only…): the app shows the
   reason it gives.

A **Parameters** tab shows the same for the node you choose.

In the demo, set ``max_linear_speed`` to ``0.2`` and drive: the robot is slower right away.

On the PC it works the same, clicking instead of tapping (**Add** in the side bar is the **+**).

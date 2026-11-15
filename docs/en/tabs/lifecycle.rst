Lifecycle
=========

.. clip:: lifecycle

See and change the state of managed (lifecycle) nodes, like Nav2's servers or your drivers.

* When you create the tab it offers to add the lifecycle nodes it finds; you can also add them
  later with **Add node**, from the network or by name.
* Each node shows its live state (*unconfigured*, *inactive*, *active*, *finalized*) and buttons
  for the transitions it allows right now (configure, activate, deactivate, cleanup, shutdown…).
  Shutdown asks for confirmation first, since a *finalized* node has to be restarted on the robot
  before it can be used again.
* The state updates as soon as the node changes (also when someone else changes it) and is read
  again every 5 seconds. **Refresh** on each node, or **Refresh all**, reads it now.
* Each node also has *Parameters & info*, which opens its inspector (:doc:`nodes`), and **×** to
  remove it from the tab.

It works with nodes on Fast DDS or Cyclone DDS, over Zenoh and through foxglove_bridge (see
:doc:`../config/network`). On the PC, *Parameters & info* opens the node's inspector inside the tab
(the arrow at the top goes back).

.. container:: vm-clear

   See :doc:`../tutorials/lifecycle-params`.

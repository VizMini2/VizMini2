Nodes
=====

.. clip:: nodes

All the nodes on the network, like ``ros2 node list``, with their parameters and a filter.

* Each node shows how many topics it publishes and subscribes to, and its services. Lifecycle nodes
  are marked, and each node shows the DDS it runs on (*Fast DDS*, *Cyclone DDS*, *Zenoh*…), also in the
  inspector's title.
* Tap a node to open the **node inspector**: its parameters, and what it publishes, subscribes to
  and serves, with their types. On the phone, tap a topic there to open it.
* Tap a parameter to see its description and change it. Read-only parameters can't be changed.
  Arrays are written as ``[1, 2, 3]``.

Parameters work with nodes on Fast DDS or Cyclone DDS, over Zenoh and through foxglove_bridge (see
:doc:`../config/network`). On the PC the inspector opens inside the tab; the arrow at the top goes
back to the list.

.. container:: vm-clear

   See :doc:`../tutorials/lifecycle-params`.

Parameters
==========

.. clip:: parameters

The parameters of one node, at hand to change them. Tap the node
at the top to choose one on the network. Each parameter shows its value, its type and whether it is
read-only; tap it to see its description and change it (arrays are written as ``[1, 2, 3]``). The
filter box keeps only the parameters whose name contains what you type, and it is remembered.

It is the same editor as in the node inspector (:doc:`nodes`), but always open on the same node: put
it in a :doc:`dashboard` next to a :doc:`plot` to tune a controller while you watch it. **Refresh**
reads the values again, and changes made by anyone else (``ros2 param set``, the node itself,
another app) show up at once, from ``/parameter_events``.

It works with nodes on Fast DDS or Cyclone DDS, over Zenoh and through foxglove_bridge (see
:doc:`../config/network`). If the bridge doesn't offer the node's parameter services, the app uses
the bridge's own parameter support, which gives no descriptions.

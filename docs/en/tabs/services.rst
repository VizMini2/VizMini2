Services
========

.. clip:: services

All the services on the network, like ``ros2 service list``; those with no server are marked. Tap
one to call it:

1. Fill in the **Request** form (one field per request field).
2. Tap **Call**.
3. The **Response** appears with *Answered in N ms*. With no answer in 10 seconds the call fails.

Calls work with servers on Fast DDS (``rmw_fastrtps_cpp``) and Cyclone DDS
(``rmw_cyclonedds_cpp``), even mixed on the same network, over Zenoh and through foxglove_bridge.
If a service doesn't answer, see *Service format* in :doc:`../config/network`. The service type has
to be bundled with the app, or defined by you: when it isn't, *Define the type…* asks for it
(:ref:`your own types <custom-types>`).

To call the same service often, make a button for it in the :doc:`buttons` tab. On the PC the call
screen opens inside the tab; the arrow at the top goes back to the list.

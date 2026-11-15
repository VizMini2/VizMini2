Connect to your robot
=====================

VizMini2 is a ROS 2 node on your phone or PC. It joins the DDS network like any other node, so it
sees your robot's topics, services and actions directly: nothing to install on the robot.

Before you start
----------------

* The robot runs ROS 2 (Foxy or newer) with **Fast DDS** (``rmw_fastrtps_cpp``, the default) or
  **Cyclone DDS** (``rmw_cyclonedds_cpp``). Topics also work with other DDS implementations. For
  **Zenoh** (``rmw_zenoh_cpp``) or any other middleware, see *Without DDS* below.
* The phone and the robot are on the same network.
* You know the robot's ``ROS_DOMAIN_ID`` (0 if nobody changed it).

Steps
-----

1. Open **Config** in the bottom bar and, under **Network**, set **Domain ID** to the robot's
   ``ROS_DOMAIN_ID``. Not sure which one? **Find robots** lists the domains with nodes on the
   network: tap one to connect to it.
2. Tap **Apply and reconnect**.
3. Go to **Home**: *ROS 2 connection* should say **Connected**, and *On the network* should list
   the robot's topics and nodes.

If *On the network* only shows this phone
-----------------------------------------

Many Wi-Fi networks (offices, universities, guest and mesh networks) block the multicast traffic
DDS uses to discover other computers. Then:

1. In **Config → Network → Discovery peers**, write the robot's IP address (several, separated by
   commas). It works like ``ROS_STATIC_PEERS``.
2. Tap **Apply and reconnect**.
3. Tap **Network check** to see what the phone sees and what to try next.

On large networks, over 4G or through a VPN, use a **Discovery Server** instead (the robot must use
the same one). See :doc:`../config/network` and :doc:`../help/troubleshooting`.

Without DDS
-----------

* Robot with **Zenoh**: write its Zenoh router (``tcp/ROBOT_IP:7447``) in **Config → Network →
  Zenoh router**.
* Any middleware, or a robot behind a router or on the internet: start
  ``foxglove_bridge`` on the robot and write ``ws://ROBOT_IP:8765`` in **Config → Network →
  foxglove_bridge**.

Then tap **Apply and reconnect**. Details in :doc:`../config/network`.

On the PC
---------

The same settings are in **Configuration** (the last item of the side bar) → **Network**. The
first time, the app takes ``ROS_DOMAIN_ID`` (and ``ROS_STATIC_PEERS`` or ``ROS_DISCOVERY_SERVER``)
from the terminal you start it from. There is no Home: the top of the side bar shows *Domain N*
when the node is up, and a **Topics** or **Nodes** tab (**Add** in the side bar) shows what is on
the network. If nothing shows up, check the firewall too: :doc:`../help/troubleshooting`.

Save it
-------

Once it works, save everything as a **robot profile** (Config → Robot profiles → *Save as…*) to
come back with one tap, on any of your devices: :doc:`../config/profiles`.

FAQ
===

Do I need to install anything on the robot?
   No. The app is a ROS 2 node that joins the DDS network directly. Robots that use Zenoh only need
   their usual Zenoh router. To draw robot meshes on the phone, serve them over HTTP from the robot
   (:doc:`../tutorials/visualize-robot`).

Which ROS 2 versions work?
   Foxy, Humble, Jazzy, Kilted and Lyrical, with Fast DDS, Cyclone DDS or Zenoh, or any middleware
   through foxglove_bridge. See :doc:`requirements`.

Is there a PC version?
   Yes, for Linux (Ubuntu 20.04 or newer), with the same tabs: :doc:`../getting-started/install`.

Does it work over 4G or a VPN?
   Yes, with a **Discovery Server** that both the phone and the robot can reach, with Discovery
   peers through the VPN, or with a Zenoh router or foxglove_bridge the phone can reach.

Is my robot's data sent anywhere?
   No. ROS 2 data goes straight between your phone or PC and your robots. See :doc:`privacy`.

Can I use it on a tablet?
   Yes, in portrait or landscape.

Can I use a gamepad?
   On the phone, yes: Bluetooth or USB, in the Teleop tabs. On the PC, drive with the keyboard
   instead: :kbd:`W`/:kbd:`S` or the up and down arrows to go forward and back, :kbd:`A`/:kbd:`D` or
   the left and right arrows to turn, :kbd:`Space` to stop.

Does the demo touch my network?
   No: it uses its own domain and only the phone's internal network. The demo is only on the phone.

Do I need an account?
   No. Everything works without one. An account is optional and only syncs your robot profiles
   across devices: :doc:`../getting-started/account`.

Is it free?
   Yes, with no ads. If it's useful to you, you can support the project on
   `GitHub Sponsors <https://github.com/sponsors/Juancams>`_.

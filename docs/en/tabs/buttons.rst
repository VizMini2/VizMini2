Buttons
=======

.. clip:: buttons

Your own buttons, each one doing a single thing:

* **Publish** a message on a topic.
* **Call** a service and show the response, with the time it took.
* **Send** an action goal, show its feedback and result, and cancel it while it runs (tap the
  button again and confirm with **Cancel goal**).

Tap **Add button**, choose the kind and use **Browse** to pick a topic, service or action from the
network (or type its name), then choose its type. Fill in the message: there is one field per
message field, arrays are written as comma-separated values and arrays of messages as JSON. Time
fields (such as ``header.stamp``) have a *current time when sent* box. Tap the line under a button
to see the whole response, feedback or error; there, a long list shows its first values and how
many there are, for example ``[0, 1, 1, 2, 3, 5, ...] (11)``.

Service and action buttons work with the same middlewares as the :doc:`services` tab.

Options
-------

* **QoS** for publishing: *Reliable* (default), *Best effort* or *Transient local*.
* **Repeat while held (Hz, 0 = once)**, up to 100 Hz: handy for "move forward while pressed". With
  repeat, releasing the button publishes the message once more with every number at zero (on by
  default), like a dead man's switch.
* **Response / goal acceptance timeout**, 5 s by default.
* **Ask for confirmation before sending**, for anything dangerous.
* A color: the default one, blue, green, red, orange, purple or gray.

Each button's ⋮ menu has *Edit*, *Duplicate*, *Move earlier*, *Move later* and
*Delete*. The buttons go with the tab when you duplicate, share or export it.

Message types
-------------

The app bundles more than 450 interfaces (``std_msgs``, ``geometry_msgs``, ``nav_msgs``,
``sensor_msgs``, ``nav2_msgs``, ``control_msgs``, ``moveit_msgs``, ``std_srvs``,
``example_interfaces``…), and the list to pick a type has a search box. Topics, services and
actions whose type is not bundled are marked *type not bundled* when you browse.

.. _custom-types:

**Your own types:** for a type the app doesn't bring (your robot's package), *Define a custom type…*
at the bottom of the type list (or pick the topic with *Browse*, which offers it straight away) asks
for its full name (``my_robot/srv/SetLight``) and the text of its ``.msg``, ``.srv`` or ``.action``
file: paste what ``ros2 interface show my_robot/srv/SetLight`` prints on the robot. It can use the
types the app already knows, and those you defined before. The type is kept on this phone or PC,
and the buttons, sliders and indicators that use it carry its definition, so they also work when
you export them or load them from a profile somewhere else.

.. container:: vm-clear

   See :doc:`../tutorials/buttons`.

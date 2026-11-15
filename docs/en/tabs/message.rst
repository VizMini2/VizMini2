Message
=======

.. clip:: message

The latest message of any topic as a tree you can unfold.
Tap the topic at the top to choose one on the network: the tab subscribes with its type and shows
how many messages arrived, how often and how long ago the last one came.

Submessages of the first level start unfolded; tap a row with ▸ to unfold it (arrays show their
size and the first 100 elements). What you unfold stays unfolded while new messages arrive.

Write a **path** to see only part of the message, with the same syntax as :doc:`indicators` and
:doc:`plot`: ``pose.pose.position``, ``poses[-1]``, ``status{name==motor}``. With a filter at the
start (``{header.frame_id==odom}.twist``), a message that doesn't match shows *Nothing at …*.

The pause button freezes what you see (messages keep being counted) and **Copy as JSON** copies
the message, or the part at the path, to paste it in a chat or in a button's message. Any message
type bundled with the app can be shown, and any other once you define it (*Define the type…*, see
:ref:`your own types <custom-types>`).

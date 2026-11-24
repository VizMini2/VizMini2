Actions
=======

.. clip:: actions

All the actions on the network, like ``ros2 action list``; those with no server are marked. Tap one
to send a goal:

1. Fill in the **Goal** form and tap **Send goal**.
2. **Feedback** updates live while the goal runs; **Cancel** stops it.
3. The **Result** appears with its final status (*SUCCEEDED*, *CANCELED*, *ABORTED*) and how long
   it took.

The action type has to be bundled with the app or defined by you: when it isn't, *Define the
type…* asks for it (:ref:`your own types <custom-types>`). One goal runs at a time on this screen, and leaving it cancels the goal that is running (rotating
the phone doesn't). Actions work with the same middlewares as :doc:`services`; through
foxglove_bridge, the bridge has to be started with ``include_hidden:=true`` (see
:doc:`../config/network`). To send the same goal often, make a button for it in the :doc:`buttons`
tab.

On the PC the goal screen opens inside the tab; the arrow at the top goes back to the list.

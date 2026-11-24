Build a dashboard
=================

Goal: one screen with the 3D view, a camera and a joystick.

1. Tap **+ → Dashboard**, give it a name if you like and tap **Create**.
2. Tap **Edit → Widget → RViz**. Drag its corner to make it take the top half.
3. Add a **Camera** widget and choose the image topic; place it under the 3D view.
4. Add a **Teleop** widget next to the camera.
5. Optional: add **Buttons** with your most used commands, or **Diagnostics** in *Log* mode.
6. Tap **Done** and drive while you watch.

Widgets stay exactly where you drop them and can overlap: the one you moved last is on top, so you
can put a joystick over the 3D view or a small camera in a corner. **Undo** reverts the last move.
Save the result in a robot profile (:doc:`../config/profiles`) to get it on another device.

In a hurry? The **+** catalog also has **Ready-made dashboards** (*Navigation*, *Camera robot*,
*Monitoring*, *Manipulation (MoveIt)*…) that use the topics found on the network.

On the PC it works the same: **Add** in the side bar is the **+**, and :kbd:`Ctrl+E` toggles edit
mode, :kbd:`Ctrl+Z` undoes and :kbd:`Ctrl+0` resets the zoom.

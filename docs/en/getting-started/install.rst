Installation
============

VizMini2 runs on **Android phones and tablets** (Android 8.0 or newer) and on **PCs** with Linux
(Ubuntu 20.04 or newer) or Windows (10 or 11). This page is about the phone; for the PC see
:doc:`desktop`.

On the phone
------------

VizMini2 is in closed testing on Google Play and not public there yet: install it from an APK
file.

1. Open the project's `releases page`_ on the phone and download
   ``VizMini2-<version>-universal.apk`` (it works on every phone; the ``arm64-v8a`` one is smaller
   and fits almost all recent phones).
2. Open it. If Android asks, allow installing apps from that source (your browser or file
   manager).
3. Open **VizMini2**. It opens on Home, with no account needed; the first time, a quick tour shows
   you the basics.

.. shot:: phone_home
   :caption: Home, here with the demo robot.

.. note::
   Your phone and your robot must be on the **same network** (or reachable through a VPN or a
   Discovery Server) for the app to see the robot. The demo works anywhere: it never leaves the
   phone.

The app asks for little: permission to show notifications (alerts and the background connection)
and, each time you record the screen, Android's screen capture prompt.

.. raw:: html

   <div class="vm-clear"></div>

On the PC
---------

The desktop app has the same tabs. From the same `releases page`_: on Ubuntu the |deb|
(``sudo apt install ./vizmini2_*_amd64.deb``) or the ``.tar.gz``; on Windows the ``.msi`` (for your
user, no administrator needed) or the ``.zip``. On Linux it can also be built from source and run
with ``ros2 run vizmini2 vizmini2``. All the details are in :doc:`desktop`.

Updating
--------

Download the newer APK (or ``.deb`` or ``.msi``) and install it over the one you have: your tabs,
profiles and settings are kept. With the ``.tar.gz`` or the ``.zip``, replace the folder. The :doc:`../changelog` lists what changed in each version.

Next: :doc:`demo` or :doc:`connect`. An :doc:`account <account>` is optional.

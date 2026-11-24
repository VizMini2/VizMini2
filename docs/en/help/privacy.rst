Privacy
=======

In short:

* No account is needed. Only if you link one (Google, Microsoft or GitHub) to sync your profiles,
  your name, email and the profiles you save are kept in the cloud (Firebase Authentication and
  Cloud Firestore, from Google), from the phone and the PC alike.
* **Robot data never leaves your network**: it goes straight between the phone (or the PC) and
  your robots.
* Crash reports are sent from the phone to fix bugs (Firebase Crashlytics). No usage analytics.
* No ads, and your data is never sold.
* A **GPS map** tab downloads the map images from OpenStreetMap's servers, which see your IP
  address and the area shown.
* You can delete your account from the app at any time: :doc:`../accounts/delete`.

Read the full `privacy policy <https://ros-2-viz-mini.web.app/privacy>`_.

On the PC
---------

The PC app sends no crash reports. Everything it stores stays on your computer: settings, tabs,
profiles and the account session in ``~/.config/vizmini2``; bags in ``~/.local/share/vizmini2``;
caches (3D view, native libraries, GPS map tiles) in ``~/.cache/vizmini2``; window recordings in ``~/Videos/VizMini2`` (your desktop's
videos folder). On Windows: ``%APPDATA%\VizMini2``, ``%LOCALAPPDATA%\VizMini2 Data`` (bags, and the
caches in its *Cache* folder) and *Videos\\VizMini2*.

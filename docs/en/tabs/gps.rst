GPS map
=======

.. clip:: gps

Your robot on a street map. The tab subscribes to a
``sensor_msgs/NavSatFix`` topic (``/gps/fix`` by default) and draws on
`OpenStreetMap <https://www.openstreetmap.org/copyright>`_ maps:

* the current **position**;
* the **accuracy circle**, from the message's covariance (if the driver fills it in);
* the **track**, with at least 30 cm between points.

The card at the top shows latitude, longitude, altitude, accuracy, the fix type (GPS, SBAS or
GBAS/RTK) and how long ago the last message arrived. Messages without a fix (status *NO_FIX*) or
with invalid coordinates (NaN or out of range) don't move anything; the card says when messages
arrive without a fix.

Tap the topic name to change it: type it, or pick one of the ``NavSatFix`` topics listed from the
network. Changing it clears the track. The follow button centers the map on the robot; moving the map
stops following it. Pinch or double tap to zoom in. The trash button clears the track.

On the PC, drag the map with the mouse and zoom with the wheel or a double click.

Map tiles are downloaded from OpenStreetMap (Internet is needed) and cached for a week so they aren't
requested again. On the phone, demo mode includes a tab with the simulated robot walking around
Puerta del Sol in Madrid.

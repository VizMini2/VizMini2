Maps
====

.. clip:: maps

Save the map, clear the costmaps and draw zones for Nav2, on top of the robot's map.

* The map is any ``nav_msgs/OccupancyGrid`` topic (``/map`` by default), with zoom (pinch, the
  wheel or the buttons) and pan.
* **Download PGM + YAML** saves the map as ``map_saver`` would (on the phone, shared to Files, Drive,
  mail...). **Save on the robot** calls Nav2's ``map_saver``; with SLAM Toolbox there are also
  *save map*, *save pose graph* and *pause / resume* buttons. The name is a path on the robot
  without extension.
* **Costmaps**: clear the global or the local costmap (or both) when they keep old obstacles.
* **Zones**: draw a *keepout* zone (the robot does not go in) or a *speed limit* zone (in percent of
  the maximum speed) by tapping its corners, then **Publish** it as a Nav2 costmap filter mask
  (``/keepout_filter_mask``, ``/speed_filter_mask``), optionally with its filter info
  (``/costmap_filter_info``), or download the mask as PGM + YAML for ``map_server``.

The costmap needs the *KeepoutFilter* or *SpeedFilter* plugin reading that filter info topic. Masks
are latched, so a costmap that starts later gets them too.

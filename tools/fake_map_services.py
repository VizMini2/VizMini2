#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
"""The map services the Maps tab calls, as Nav2 and SLAM Toolbox offer them, to test it without a robot:

    /map_saver/save_map                              nav2_msgs/srv/SaveMap (writes nothing; answers true)
    /slam_toolbox/save_map, /slam_toolbox/serialize_map, /slam_toolbox/pause_new_measurements
    /global_costmap/clear_entirely_global_costmap, /local_costmap/clear_entirely_local_costmap

Each call, and each keepout/speed mask or filter info received (latched, as a costmap would read them), is
reported on /fake_map_services/events (std_msgs/String, JSON) so the tests can check what arrived.

    source /opt/ros/<distro>/setup.bash; source "$(tools/e2e/build_msgs.sh)"
    python3 tools/fake_map_services.py
"""
import json

import rclpy
from rclpy.node import Node
from rclpy.qos import DurabilityPolicy, QoSProfile, ReliabilityPolicy
from nav2_msgs.msg import CostmapFilterInfo
from nav2_msgs.srv import ClearEntireCostmap, SaveMap
from nav_msgs.msg import OccupancyGrid
from slam_toolbox.srv import Pause, SaveMap as SlamSaveMap, SerializePoseGraph
from std_msgs.msg import String


class FakeMapServices(Node):
    def __init__(self):
        super().__init__("fake_map_services")
        latched = QoSProfile(depth=1, reliability=ReliabilityPolicy.RELIABLE, durability=DurabilityPolicy.TRANSIENT_LOCAL)
        self.events = self.create_publisher(String, "/fake_map_services/events", latched)
        self.paused = False
        self.create_service(SaveMap, "/map_saver/save_map", self.save_map)
        self.create_service(SlamSaveMap, "/slam_toolbox/save_map", self.slam_save)
        self.create_service(SerializePoseGraph, "/slam_toolbox/serialize_map", self.slam_serialize)
        self.create_service(Pause, "/slam_toolbox/pause_new_measurements", self.slam_pause)
        for c in ("global_costmap", "local_costmap"):
            self.create_service(ClearEntireCostmap, f"/{c}/clear_entirely_{c}", lambda req, res, c=c: self.clear(c, res))
        for t in ("/keepout_filter_mask", "/speed_filter_mask"):
            self.create_subscription(OccupancyGrid, t, lambda m, t=t: self.mask(t, m), latched)
        for t in ("/costmap_filter_info", "/speed_filter_info"):
            self.create_subscription(CostmapFilterInfo, t, lambda m, t=t: self.event("info", topic=t, type=m.type, mask=m.filter_mask_topic), latched)

    def event(self, kind, **kw):
        kw["event"] = kind
        self.events.publish(String(data=json.dumps(kw)))
        self.get_logger().info(json.dumps(kw))

    def save_map(self, req, res):
        self.event("map_saver", url=req.map_url, topic=req.map_topic, mode=req.map_mode)
        res.result = True
        return res

    def slam_save(self, req, res):
        self.event("slam_save", name=req.name.data)
        res.result = 0
        return res

    def slam_serialize(self, req, res):
        self.event("slam_serialize", filename=req.filename)
        res.result = 0
        return res

    def slam_pause(self, req, res):
        self.paused = not self.paused
        self.event("slam_pause", paused=self.paused)
        res.status = True
        return res

    def clear(self, costmap, res):
        self.event("clear", costmap=costmap)
        return res

    def mask(self, topic, m):
        values = sorted({v for v in m.data if v != 0})
        self.event("mask", topic=topic, width=m.info.width, height=m.info.height, cells=sum(1 for v in m.data if v != 0), values=values)


def main():
    rclpy.init()
    n = FakeMapServices()
    try:
        rclpy.spin(n)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

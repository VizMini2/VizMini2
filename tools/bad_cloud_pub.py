#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
"""Good and broken clouds for RvizDisplaysPcTest.badMessageDoesNotLeaveTheDisplayInError.

Publishes /rviz2mini_test/bad_cloud (sensor_msgs/PointCloud2, frame "map") at 5 Hz in 12 s cycles:
3 s good (one point), 2 s broken (they claim 10 points and carry no data) and 7 s good.

Usage: ROS_DOMAIN_ID=77 python3 tools/bad_cloud_pub.py
"""
import time

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import PointCloud2, PointField


class BadCloud(Node):
    def __init__(self):
        super().__init__("bad_cloud_pub", namespace="/rviz2mini_test")
        self.pub = self.create_publisher(PointCloud2, "bad_cloud", 10)
        self.start = time.monotonic()
        self.create_timer(0.2, self.tick)

    def tick(self):
        t = (time.monotonic() - self.start) % 12.0
        broken = 3.0 <= t < 5.0
        m = PointCloud2()
        m.header.frame_id = "map"
        m.header.stamp = self.get_clock().now().to_msg()
        m.height = 1
        m.width = 10 if broken else 1
        m.fields = [PointField(name=n, offset=4 * i, datatype=PointField.FLOAT32, count=1) for i, n in enumerate("xyz")]
        m.point_step = 12
        m.row_step = 12 * m.width
        m.data = b"" if broken else bytes(12)
        m.is_dense = True
        self.pub.publish(m)


def main():
    rclpy.init()
    rclpy.spin(BadCloud())


if __name__ == "__main__":
    main()

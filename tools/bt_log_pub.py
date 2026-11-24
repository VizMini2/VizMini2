#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
"""Publishes a /behavior_tree_log like the one from Nav2's bt_navigator (nav2_msgs/BehaviorTreeLog) for
BehaviorTreePcTest: a fake navigation cycle (plan, follow the path, sometimes fails and
recovers by spinning), one change every 0.4 s.

Usage: ROS_DOMAIN_ID=77 python3 tools/bt_log_pub.py
"""
import rclpy
from nav2_msgs.msg import BehaviorTreeLog, BehaviorTreeStatusChange
from rclpy.node import Node

# (name, uid, previous, new)
CYCLE = [
    ("NavigateRecovery", 1, "IDLE", "RUNNING"),
    ("ComputePathToPose", 4, "IDLE", "RUNNING"),
    ("ComputePathToPose", 4, "RUNNING", "SUCCESS"),
    ("FollowPath", 7, "IDLE", "RUNNING"),
    ("FollowPath", 7, "RUNNING", "FAILURE"),
    ("Spin", 11, "IDLE", "RUNNING"),
    ("Spin", 11, "RUNNING", "SUCCESS"),
    ("ComputePathToPose", 4, "SUCCESS", "IDLE"),
    ("FollowPath", 7, "FAILURE", "IDLE"),
    ("Spin", 11, "SUCCESS", "IDLE"),
]


class BtLogPub(Node):
    def __init__(self):
        super().__init__("bt_log_test_pub")
        self.pub = self.create_publisher(BehaviorTreeLog, "/behavior_tree_log", 10)
        self.i = 0
        self.create_timer(0.4, self.tick)

    def tick(self):
        name, uid, prev, cur = CYCLE[self.i % len(CYCLE)]
        self.i += 1
        now = self.get_clock().now().to_msg()
        msg = BehaviorTreeLog(timestamp=now)
        msg.event_log.append(BehaviorTreeStatusChange(timestamp=now, node_name=name, uid=uid, previous_status=prev, current_status=cur))
        self.pub.publish(msg)


def main():
    rclpy.init()
    rclpy.spin(BtLogPub())


if __name__ == "__main__":
    main()

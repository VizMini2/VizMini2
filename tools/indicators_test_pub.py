#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
"""Publisher for IndicatorsPcTest: /rviz2mini_test/battery (sensor_msgs/BatteryState) at 5 Hz.

The percentage sweeps 0..1 in 10 s (sawtooth), the voltage goes from 22 to 26 V with it and
location is "base".

Usage: ROS_DOMAIN_ID=77 python3 tools/indicators_test_pub.py
"""
import time

import rclpy
from rclpy.node import Node
from sensor_msgs.msg import BatteryState


class IndicatorsTestPub(Node):
    def __init__(self):
        super().__init__("indicators_test_pub", namespace="/rviz2mini_test")
        self.pub = self.create_publisher(BatteryState, "battery", 10)
        self.start = time.monotonic()
        self.create_timer(0.2, self.tick)

    def tick(self):
        p = ((time.monotonic() - self.start) % 10.0) / 10.0
        m = BatteryState()
        m.header.stamp = self.get_clock().now().to_msg()
        m.header.frame_id = "base_link"
        m.percentage = p
        m.voltage = 22.0 + 4.0 * p
        m.power_supply_status = BatteryState.POWER_SUPPLY_STATUS_DISCHARGING
        m.present = True
        m.location = "base"
        m.cell_voltage = [3.7 + 0.1 * i for i in range(6)]
        self.pub.publish(m)


def main():
    rclpy.init()
    rclpy.spin(IndicatorsTestPub())


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Test server for the "Botones" (Buttons) tab (ButtonsPcTest). Everything under /rviz2mini_test:

- add_two_ints service (example_interfaces/srv/AddTwoInts)
- set_bool service (std_srvs/srv/SetBool): replies with a text containing accents
- fibonacci action (example_interfaces/action/Fibonacci): one step every 0.3 s, cancellable
- topics button_twist (geometry_msgs/Twist) and button_text (std_msgs/String): whatever is received is
    forwarded as text on button_echo (std_msgs/String)

Usage: ROS_DOMAIN_ID=77 python3 tools/buttons_test_server.py
"""
import time

import rclpy
from rclpy.action import ActionServer, CancelResponse
from rclpy.callback_groups import ReentrantCallbackGroup
from rclpy.executors import MultiThreadedExecutor
from rclpy.node import Node

from example_interfaces.action import Fibonacci
from example_interfaces.srv import AddTwoInts
from geometry_msgs.msg import Twist
from std_msgs.msg import String
from std_srvs.srv import SetBool


class ButtonsTestServer(Node):
    def __init__(self):
        super().__init__("buttons_test_server", namespace="/rviz2mini_test")
        group = ReentrantCallbackGroup()
        self.create_service(AddTwoInts, "add_two_ints", self.add, callback_group=group)
        self.create_service(SetBool, "set_bool", self.set_bool, callback_group=group)
        ActionServer(self, Fibonacci, "fibonacci", self.fibonacci, callback_group=group,
                     cancel_callback=lambda _: CancelResponse.ACCEPT)
        self.echo = self.create_publisher(String, "button_echo", 10)
        self.create_subscription(Twist, "button_twist", self.on_twist, 10)
        self.create_subscription(String, "button_text", self.on_text, 10)

    def add(self, req, res):
        res.sum = req.a + req.b
        self.get_logger().info(f"add_two_ints {req.a} + {req.b} = {res.sum}")
        return res

    def set_bool(self, req, res):
        res.success = True
        res.message = f"Camión recibido: {req.data} ✓"
        return res

    def fibonacci(self, goal_handle):
        order = goal_handle.request.order
        seq = [0, 1]
        fb = Fibonacci.Feedback()
        for i in range(1, order):
            if goal_handle.is_cancel_requested:
                goal_handle.canceled()
                self.get_logger().info("fibonacci cancelada")
                return Fibonacci.Result(sequence=seq)
            seq.append(seq[i] + seq[i - 1])
            fb.sequence = seq
            goal_handle.publish_feedback(fb)
            time.sleep(0.3)
        goal_handle.succeed()
        self.get_logger().info(f"fibonacci {order} -> {seq}")
        return Fibonacci.Result(sequence=seq)

    def on_twist(self, m):
        self.echo.publish(String(data=f"twist {m.linear.x:.2f} {m.angular.z:.2f}"))

    def on_text(self, m):
        self.echo.publish(String(data=f"text {m.data}"))


def main():
    rclpy.init()
    node = ButtonsTestServer()
    executor = MultiThreadedExecutor()
    executor.add_node(node)
    try:
        executor.spin()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

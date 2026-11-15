#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Interactive markers server for InteractiveMarkersPcTest (namespace /rviz2mini_test_im, frame map).

- "mover" at (1, 1): arrows along x (MOVE_AXIS) and a ring to rotate around z (ROTATE_AXIS), with a gray cube.
- "planar" at (3, -2): ring to move it in the horizontal plane (MOVE_PLANE).
- "button" at (-2, 1): an orange cube that is a button (BUTTON).
- "menu" at (-2, -1): a sphere with a menu (MENU): Go (with Fast inside) and Stop.

Each feedback is forwarded as text on /rviz2mini_test/im_events (std_msgs/String):
"<event_type> <marker> <control> <x> <y> <yaw> <menu_entry_id>".

Usage: ROS_DOMAIN_ID=77 python3 tools/interactive_markers_server.py
"""
import math

import rclpy
from interactive_markers import InteractiveMarkerServer, MenuHandler
from rclpy.node import Node
from std_msgs.msg import String
from visualization_msgs.msg import InteractiveMarker, InteractiveMarkerControl, Marker


def control(name, mode, ox, oy, oz, ow=1.0):
    c = InteractiveMarkerControl()
    n = math.sqrt(ox * ox + oy * oy + oz * oz + ow * ow)
    c.orientation.x, c.orientation.y, c.orientation.z, c.orientation.w = ox / n, oy / n, oz / n, ow / n
    c.name = name
    c.interaction_mode = mode
    return c


def shape(kind, size, r, g, b):
    m = Marker()
    m.type = kind
    m.scale.x = m.scale.y = m.scale.z = size
    m.color.r, m.color.g, m.color.b, m.color.a = r, g, b, 1.0
    return m


class ImServer(Node):
    def __init__(self):
        super().__init__("interactive_markers_test_server")
        self.events = self.create_publisher(String, "/rviz2mini_test/im_events", 10)
        self.server = InteractiveMarkerServer(self, "rviz2mini_test_im")
        self.menu = MenuHandler()
        go = self.menu.insert("Go", callback=self.feedback)
        self.menu.insert("Fast", parent=go, callback=self.feedback)
        self.menu.insert("Stop", callback=self.feedback)

        mover = self.marker("mover", 1.0, 1.0, "Move me")
        box = control("box", InteractiveMarkerControl.NONE, 0.0, 0.0, 0.0)
        box.always_visible = True
        box.markers.append(shape(Marker.CUBE, 0.3, 0.6, 0.6, 0.6))
        mover.controls.append(box)
        mover.controls.append(control("move_x", InteractiveMarkerControl.MOVE_AXIS, 1.0, 0.0, 0.0))
        mover.controls.append(control("rotate_z", InteractiveMarkerControl.ROTATE_AXIS, 0.0, 1.0, 0.0))
        self.server.insert(mover, feedback_callback=self.feedback)

        planar = self.marker("planar", 3.0, -2.0, "Drag me")
        planar.controls.append(control("move_plane", InteractiveMarkerControl.MOVE_PLANE, 0.0, 1.0, 0.0))
        self.server.insert(planar, feedback_callback=self.feedback)

        button = self.marker("button", -2.0, 1.0, "Press me")
        b = control("press", InteractiveMarkerControl.BUTTON, 0.0, 0.0, 0.0)
        b.always_visible = True
        b.markers.append(shape(Marker.CUBE, 0.5, 1.0, 0.5, 0.0))
        button.controls.append(b)
        self.server.insert(button, feedback_callback=self.feedback)

        menu = self.marker("menu", -2.0, -1.0, "Menu")
        mc = control("open", InteractiveMarkerControl.MENU, 0.0, 0.0, 0.0)
        mc.always_visible = True
        mc.markers.append(shape(Marker.SPHERE, 0.5, 0.2, 0.6, 1.0))
        menu.controls.append(mc)
        self.server.insert(menu, feedback_callback=self.feedback)
        self.menu.apply(self.server, "menu")
        self.server.applyChanges()

    @staticmethod
    def marker(name, x, y, description):
        m = InteractiveMarker()
        m.header.frame_id = "map"
        m.name = name
        m.description = description
        m.scale = 1.0
        m.pose.position.x, m.pose.position.y = x, y
        m.pose.orientation.w = 1.0
        return m

    def feedback(self, fb):
        o = fb.pose.orientation
        yaw = math.atan2(2 * (o.w * o.z + o.x * o.y), 1 - 2 * (o.y * o.y + o.z * o.z))
        text = f"{fb.event_type} {fb.marker_name} {fb.control_name} {fb.pose.position.x:.3f} {fb.pose.position.y:.3f} {yaw:.3f} {fb.menu_entry_id}"
        self.get_logger().info(text)
        self.events.publish(String(data=text))
        # Like a real server: accepts the pose sent by the client.
        if fb.event_type == fb.POSE_UPDATE:
            self.server.setPose(fb.marker_name, fb.pose)
            self.server.applyChanges()


def main():
    rclpy.init()
    rclpy.spin(ImServer())


if __name__ == "__main__":
    main()

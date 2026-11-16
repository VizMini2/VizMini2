#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""A NavMap (EasyNavigation, https://github.com/EasyNavigation/NavMap) to try VizMini2's NavMap display: a building
with two floors joined by a ramp, an elevator and a door between two rooms (links), the layers "cost", "elevation"
and "occupancy", and a person walking around the ground floor whose cost goes out as sparse layer updates (and a
static TF map -> base_link, so there is a TF tree).

Publishes on /navmap (navmap_ros_interfaces/NavMap, every 2 s) and /navmap_layers (NavMapLayer, 10 Hz), frame "map".
Needs navmap_ros_interfaces: the real one, or the stand-in of tools/e2e/msgs:

    source /opt/ros/jazzy/setup.bash && source "$(tools/e2e/build_msgs.sh)"
    python3 tools/navmap_demo.py
"""
import math

import rclpy
import rclpy.executors
from rclpy.node import Node
from rclpy.qos import QoSProfile, ReliabilityPolicy, DurabilityPolicy
from geometry_msgs.msg import TransformStamped
from tf2_msgs.msg import TFMessage
from navmap_ros_interfaces.msg import NavMap, NavMapLayer, NavMapLink, NavMapSurface

CELL = 0.25         # m, side of the grid squares (two triangles each)
FLOOR_H = 3.0       # m, height of the first floor
GEOMETRY_ID = 0x5649_5A4D_494E_4932  # any fixed fingerprint ("VIZMINI2")


class Mesh:
    def __init__(self):
        self.x, self.y, self.z, self.tris = [], [], [], []

    def grid(self, x0, y0, w, h, height, blocked=()):
        """A floor of w x h m from (x0, y0) without the squares inside `blocked` (x0, y0, x1, y1 rectangles: walls,
        furniture), which a NavMap leaves out; `height(x, y)` gives z. Returns its triangle indices."""
        nx, ny = round(w / CELL), round(h / CELL)
        base = len(self.x)
        for j in range(ny + 1):
            for i in range(nx + 1):
                x, y = x0 + i * CELL, y0 + j * CELL
                self.x.append(x); self.y.append(y); self.z.append(height(x, y))
        out = []
        for j in range(ny):
            for i in range(nx):
                cx, cy = x0 + (i + 0.5) * CELL, y0 + (j + 0.5) * CELL
                if any(bx0 <= cx <= bx1 and by0 <= cy <= by1 for bx0, by0, bx1, by1 in blocked):
                    continue
                a = base + j * (nx + 1) + i
                b, c, d = a + 1, a + nx + 2, a + nx + 1
                for t in ((a, b, c), (a, c, d)):
                    out.append(len(self.tris)); self.tris.append(t)
        return out

    def centroid(self, t):
        a, b, c = self.tris[t]
        return ((self.x[a] + self.x[b] + self.x[c]) / 3, (self.y[a] + self.y[b] + self.y[c]) / 3,
                (self.z[a] + self.z[b] + self.z[c]) / 3)

    def nearest(self, cells, x, y, z):
        return min(cells, key=lambda t: sum((p - q) ** 2 for p, q in zip(self.centroid(t), (x, y, z))))


# What is not navigable on each floor (walls and furniture): x0, y0, x1, y1.
GROUND_BLOCKED = [(5.9, 0, 6.1, 3.0), (5.9, 4.2, 6.1, 8),           # the wall between the rooms, with its door
                  (2.2, 5.0, 4.0, 6.6), (0, 0, 0.9, 2.2),           # a table and a sofa in the left room
                  (8.4, 1.4, 10.4, 2.6), (11.3, 5.5, 12, 8)]        # a desk and shelves in the right room
FIRST_BLOCKED = [(6.8, 3.0, 9.2, 5.0), (4, 6.8, 5.6, 8)]            # a meeting table and a cupboard


def build():
    m = Mesh()
    ground = m.grid(0, 0, 12, 8, lambda x, y: 0.0, GROUND_BLOCKED)
    # Outside, two ramps with a landing between them up to the first floor.
    ramp1 = m.grid(12, 0, 6, 2, lambda x, y: 1.5 * (x - 12) / 6)
    landing = m.grid(18, 0, 2, 5, lambda x, y: 1.5)
    ramp2 = m.grid(12, 3, 6, 2, lambda x, y: 1.5 + 1.5 * (18 - x) / 6)
    # First floor: above the right room and part of the left one.
    first = m.grid(4, 0, 8, 8, lambda x, y: FLOOR_H, FIRST_BLOCKED)
    links = [
        NavMapLink(from_navcel=m.nearest(ground, 1.0, 7.4, 0), to_navcel=m.nearest(first, 4.4, 5.0, FLOOR_H),
                   type=NavMapLink.ELEVATOR, length=FLOOR_H, bidirectional=True),
        NavMapLink(from_navcel=m.nearest(ground, 5.6, 3.6, 0), to_navcel=m.nearest(ground, 6.4, 3.6, 0),
                   type=NavMapLink.DOOR, length=0.8, bidirectional=True),
    ]
    return m, [("ground floor", ground), ("ramps", ramp1 + landing + ramp2), ("first floor", first)], links


def rect_dist(x, y, r):
    return math.hypot(max(r[0] - x, 0, x - r[2]), max(r[1] - y, 0, y - r[3]))


class Demo(Node):
    def __init__(self):
        super().__init__("navmap_demo")
        self.m, self.surfaces, self.links = build()
        n = len(self.m.tris)
        cents = [self.m.centroid(t) for t in range(n)]
        # Static cost: high near the outer walls and around two "tables".
        def static_cost(x, y, z):
            """Inflated cost, as in a Nav2 costmap: high next to walls and furniture, fading out over a meter."""
            if z < 0.01:
                d = min([x, y, 12 - x, 8 - y] + [rect_dist(x, y, r) for r in GROUND_BLOCKED])
            elif abs(z - FLOOR_H) < 0.01 and 4 <= x <= 12:
                d = min([x - 4, y, 12 - x, 8 - y] + [rect_dist(x, y, r) for r in FIRST_BLOCKED])
            else:  # ramps and landing: their sides
                d = min(abs(y - c) for c in (0, 2, 3, 5)) if x < 18 else min(y, 5 - y, 20 - x)
            return 100 * math.exp(-3.0 * max(0.0, d - 0.1))
        self.base_cost = [static_cost(*c) for c in cents]
        self.cents = cents
        self.cost = list(self.base_cost)
        self.prev = set()
        occupancy = bytes(254 if c > 90 else int(c * 2) for c in self.base_cost)
        layers = [
            NavMapLayer(name="cost", type=NavMapLayer.F32, description="Cost to cross the cell", unit="",
                        geometry_id=GEOMETRY_ID, data_f32=self.cost),
            NavMapLayer(name="elevation", type=NavMapLayer.F32, unit="m", geometry_id=GEOMETRY_ID,
                        data_f32=[c[2] for c in cents]),
            NavMapLayer(name="occupancy", type=NavMapLayer.U8, geometry_id=GEOMETRY_ID, data_u8=occupancy),
        ]
        self.msg = NavMap(positions_x=self.m.x, positions_y=self.m.y, positions_z=self.m.z,
                          navcels_v0=[t[0] for t in self.m.tris], navcels_v1=[t[1] for t in self.m.tris],
                          navcels_v2=[t[2] for t in self.m.tris],
                          surfaces=[NavMapSurface(frame_id="map", navcels=cells) for _, cells in self.surfaces],
                          layers=layers, geometry_id=GEOMETRY_ID, links=self.links)
        self.msg.header.frame_id = "map"
        qos = QoSProfile(depth=1, reliability=ReliabilityPolicy.RELIABLE, durability=DurabilityPolicy.TRANSIENT_LOCAL)
        self.map_pub = self.create_publisher(NavMap, "navmap", qos)
        self.layer_pub = self.create_publisher(NavMapLayer, "navmap_layers", 10)
        tf = TransformStamped(child_frame_id="base_link")
        tf.header.frame_id = "map"
        tf.transform.translation.x, tf.transform.translation.y, tf.transform.rotation.w = 1.0, 1.0, 1.0
        self.tf_pub = self.create_publisher(TFMessage, "/tf_static", qos)
        self.tf_pub.publish(TFMessage(transforms=[tf]))
        self.t0 = self.get_clock().now()
        self.create_timer(2.0, self.publish_map)
        self.create_timer(0.1, self.publish_person)
        self.publish_map()
        self.get_logger().info(f"NavMap: {n} cells, {len(self.surfaces)} surfaces, {len(self.links)} links on /navmap")

    def publish_map(self):
        self.msg.header.stamp = self.get_clock().now().to_msg()
        self.msg.layers[0].data_f32 = self.cost
        self.map_pub.publish(self.msg)

    def publish_person(self):
        """A person walking a loop in the right room: the cells around them get a high cost (sparse update)."""
        t = (self.get_clock().now() - self.t0).nanoseconds / 1e9
        px, py = 9 + 1.6 * math.cos(t * 0.6), 4.6 + 2.0 * math.sin(t * 0.6)
        near = {i for i, (x, y, z) in enumerate(self.cents) if z < 0.5 and math.hypot(x - px, y - py) < 1.2}
        changed = sorted(near | self.prev)
        for i in changed:
            d = math.hypot(self.cents[i][0] - px, self.cents[i][1] - py)
            self.cost[i] = max(self.base_cost[i], 100 * math.exp(-3.0 * max(0.0, d - 0.25))) if i in near else self.base_cost[i]
        self.prev = near
        msg = NavMapLayer(name="cost", type=NavMapLayer.F32, geometry_id=GEOMETRY_ID, indices=changed,
                          data_f32=[self.cost[i] for i in changed])
        msg.header.frame_id = "map"
        self.layer_pub.publish(msg)


def main():
    rclpy.init()
    try:
        rclpy.spin(Demo())
    except (KeyboardInterrupt, rclpy.executors.ExternalShutdownException):
        pass


if __name__ == "__main__":
    main()

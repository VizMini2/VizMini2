#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Publishes a .navmap file (EasyNavigation's NavMap: https://github.com/EasyNavigation/NavMap) on /navmap, like their
navmap_publisher (plus a static TF from its frame to base_link), without building their stack: for real maps in VizMini2's NavMap display, or to record them in a bag.
Reads the file formats 1 (old, migrated as navmap_ros does) and 2 (zlib or uncompressed).

Maps to try, from EasyNavigation's playgrounds (e.g. https://github.com/EasyNavigation/easynav_playground_summit/tree/main/maps):
excavation_urjc.navmap, excavation_urjc_2.navmap, warehouse_20cm.navmap.

    source /opt/ros/jazzy/setup.bash && source "$(tools/e2e/build_msgs.sh)"
    python3 tools/navmap_file_pub.py warehouse_20cm.navmap [--topic /navmap] [--frame map]
"""
import argparse
import struct
import zlib

import rclpy
import rclpy.executors
from rclpy.node import Node
from rclpy.qos import QoSProfile, ReliabilityPolicy, DurabilityPolicy
from rclpy.serialization import deserialize_message
from geometry_msgs.msg import TransformStamped
from tf2_msgs.msg import TFMessage
from navmap_ros_interfaces.msg import NavMap, NavMapLayer, NavMapV1

MAGIC = b"NAVMAP\0\0"


def load(path: str) -> NavMap:
    with open(path, "rb") as f:
        data = f.read()
    version, codec = 1, 0
    if data.startswith(MAGIC):
        (version,) = struct.unpack_from("<I", data, 8)
        if version >= 2:
            codec, raw_size, stored_size = struct.unpack_from("<IQQ", data, 12)
            payload = data[32:32 + stored_size]
        else:
            (stored_size,) = struct.unpack_from("<Q", data, 12)
            payload = data[20:20 + stored_size]
    else:
        payload = data  # the oldest files: no header, version 1
    if codec == 1:
        payload = zlib.decompress(payload)
    if version >= 2:
        return deserialize_message(payload, NavMap)
    return migrate_v1(deserialize_message(payload, NavMapV1))


def migrate_v1(old: NavMapV1) -> NavMap:
    """Version 1 has the same geometry and layers without metadata (no unknown value, int16, links nor geometry id)."""
    m = NavMap(header=old.header, positions_x=old.positions_x, positions_y=old.positions_y, positions_z=old.positions_z,
               has_vertex_rgba=old.has_vertex_rgba, colors_r=old.colors_r, colors_g=old.colors_g,
               colors_b=old.colors_b, colors_a=old.colors_a, navcels_v0=old.navcels_v0, navcels_v1=old.navcels_v1,
               navcels_v2=old.navcels_v2, surfaces=old.surfaces)
    m.layers = [NavMapLayer(header=l.header, name=l.name, type=l.type, data_u8=l.data_u8, data_f32=l.data_f32,
                            data_f64=l.data_f64) for l in old.layers]
    return m


class Publisher(Node):
    def __init__(self, msg: NavMap, topic: str):
        super().__init__("navmap_file_pub")
        self.msg = msg
        qos = QoSProfile(depth=1, reliability=ReliabilityPolicy.RELIABLE, durability=DurabilityPolicy.TRANSIENT_LOCAL)
        self.pub = self.create_publisher(NavMap, topic, qos)
        # A static TF from the map's frame (map -> base_link), so viewers offer that frame as the fixed one.
        tf = TransformStamped(child_frame_id="base_link")
        tf.header.frame_id = msg.header.frame_id
        tf.header.stamp = self.get_clock().now().to_msg()
        tf.transform.rotation.w = 1.0
        self.tf_pub = self.create_publisher(TFMessage, "/tf_static", qos)
        self.tf_pub.publish(TFMessage(transforms=[tf]))
        # Also every few seconds, as navmap_publisher does (late subscribers that miss the transient local one).
        self.create_timer(3.0, self.publish)
        self.publish()

    def publish(self):
        self.msg.header.stamp = self.get_clock().now().to_msg()
        self.pub.publish(self.msg)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("file")
    ap.add_argument("--topic", default="/navmap")
    ap.add_argument("--frame", default="", help="frame_id to use instead of the file's (empty: the file's, or map)")
    a = ap.parse_args()
    msg = load(a.file)
    msg.header.frame_id = a.frame or msg.header.frame_id or "map"
    for s in msg.surfaces:
        s.frame_id = a.frame or s.frame_id or msg.header.frame_id
    rclpy.init()
    node = Publisher(msg, a.topic)
    xs, ys, zs = msg.positions_x, msg.positions_y, msg.positions_z
    node.get_logger().info(
        f"{a.file}: {len(msg.navcels_v0)} cells, {len(msg.surfaces)} surfaces, layers "
        f"{[l.name for l in msg.layers]}, frame '{msg.header.frame_id}', x {min(xs):.1f}..{max(xs):.1f} "
        f"y {min(ys):.1f}..{max(ys):.1f} z {min(zs):.1f}..{max(zs):.1f} -> {a.topic}")
    try:
        rclpy.spin(node)
    except (KeyboardInterrupt, rclpy.executors.ExternalShutdownException):
        pass


if __name__ == "__main__":
    main()

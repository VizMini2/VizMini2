#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Generates app/src/main/assets/ros_interfaces.txt with the .msg/.srv/.action definitions of a
ROS 2 installation (default /opt/ros/jazzy). The "Buttons" tab uses them to serialize
any message, service or action without generated classes.

Format: a header "@package/msg|srv|action/Name" followed by the definition without comments.

Usage: python3 tools/gen_ros_interfaces.py [/opt/ros/jazzy]
     python3 tools/gen_ros_interfaces.py --add /opt/ros/lyrical moveit_msgs octomap_msgs ...
       (adds to the current file those of these packages that are not there yet, without touching the rest)
"""
import os
import sys

PACKAGES = [
    "builtin_interfaces", "std_msgs", "std_srvs", "geometry_msgs", "sensor_msgs", "nav_msgs",
    "example_interfaces", "action_msgs", "unique_identifier_msgs", "turtlesim", "nav2_msgs",
    "action_tutorials_interfaces", "lifecycle_msgs", "diagnostic_msgs", "trajectory_msgs",
    "geographic_msgs", "rcl_interfaces", "visualization_msgs", "shape_msgs", "service_msgs",
    "rosgraph_msgs", "tf2_msgs", "actionlib_msgs",
]
KINDS = [("msg", ".msg"), ("srv", ".srv"), ("action", ".action")]

# Types that do not come with the distro but that the app understands (Foxglove image annotations).
EXTRA = """@foxglove_msgs/msg/Point2
float64 x
float64 y
@foxglove_msgs/msg/Color
float64 r
float64 g
float64 b
float64 a
@foxglove_msgs/msg/CircleAnnotation
builtin_interfaces/Time timestamp
foxglove_msgs/Point2 position
float64 diameter
float64 thickness
foxglove_msgs/Color fill_color
foxglove_msgs/Color outline_color
@foxglove_msgs/msg/PointsAnnotation
uint8 UNKNOWN=0
uint8 POINTS=1
uint8 LINE_LOOP=2
uint8 LINE_STRIP=3
uint8 LINE_LIST=4
builtin_interfaces/Time timestamp
uint8 type
foxglove_msgs/Point2[] points
foxglove_msgs/Color outline_color
foxglove_msgs/Color[] outline_colors
foxglove_msgs/Color fill_color
float64 thickness
@foxglove_msgs/msg/TextAnnotation
builtin_interfaces/Time timestamp
foxglove_msgs/Point2 position
string text
float64 font_size
foxglove_msgs/Color text_color
foxglove_msgs/Color background_color
@foxglove_msgs/msg/ImageAnnotations
foxglove_msgs/CircleAnnotation[] circles
foxglove_msgs/PointsAnnotation[] points
foxglove_msgs/TextAnnotation[] texts
"""


def strip_comment(line: str) -> str:
    """Removes what comes after '#' unless it is inside quotes (default values)."""
    quote = None
    for i, ch in enumerate(line):
        if quote:
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch == "#":
            return line[:i]
    return line


def read_package(root: str, pkg: str) -> list:
    entries = []
    for kind, ext in KINDS:
        d = os.path.join(root, "share", pkg, kind)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(ext):
                continue
            with open(os.path.join(d, f), encoding="utf-8") as fh:
                lines = [strip_comment(l).rstrip() for l in fh]
            body = "\n".join(l for l in lines if l.strip())
            entries.append((f"{pkg}/{kind}/{f[:-len(ext)]}", f"@{pkg}/{kind}/{f[:-len(ext)]}\n{body}\n"))
    return entries


def add(root: str, pkgs: list, out_path: str) -> None:
    with open(out_path, encoding="utf-8") as fh:
        text = fh.read()
    have = {l[1:].strip() for l in text.splitlines() if l.startswith("@")}
    new = [e for pkg in pkgs for (name, e) in read_package(root, pkg) if name not in have]
    with open(out_path, "a", encoding="utf-8") as fh:
        fh.write("".join(new))
    print(f"{len(new)} interfaces añadidas -> {os.path.normpath(out_path)}")


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    out_path = os.path.join(here, "..", "app", "src", "main", "assets", "ros_interfaces.txt")
    if len(sys.argv) > 2 and sys.argv[1] == "--add":
        add(sys.argv[2], sys.argv[3:], out_path)
        return
    root = sys.argv[1] if len(sys.argv) > 1 else "/opt/ros/jazzy"
    entries = []
    for pkg in PACKAGES:
        for kind, ext in KINDS:
            d = os.path.join(root, "share", pkg, kind)
            if not os.path.isdir(d):
                continue
            for f in sorted(os.listdir(d)):
                if not f.endswith(ext):
                    continue
                with open(os.path.join(d, f), encoding="utf-8") as fh:
                    lines = [strip_comment(l).rstrip() for l in fh]
                body = "\n".join(l for l in lines if l.strip())
                entries.append(f"@{pkg}/{kind}/{f[:-len(ext)]}\n{body}\n")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write(f"# Generado por tools/gen_ros_interfaces.py desde {root}\n")
        fh.write("".join(entries))
        # The foxglove_msgs ones only if the distro does not ship them.
        if not os.path.isdir(os.path.join(root, "share", "foxglove_msgs")):
            fh.write(EXTRA)
    print(f"{len(entries)} interfaces -> {os.path.normpath(out_path)}")


if __name__ == "__main__":
    main()

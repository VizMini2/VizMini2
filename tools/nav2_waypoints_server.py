#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Fake Nav2 servers to test VizMini2's waypoints bar without a real Nav2.

/follow_waypoints (nav2_msgs/action/FollowWaypoints) and /navigate_through_poses (NavigateThroughPoses): they accept the
route, print what they receive ("GOAL follow 3 poses frame=map loops=0 first=(1.00,2.00)"), advance one waypoint per
second with its feedback and finish in SUCCEEDED (or CANCELED if it is canceled).

    source /opt/ros/<distro>/setup.bash; python3 tools/nav2_waypoints_server.py
"""
import time

import rclpy
from rclpy.action import ActionServer, CancelResponse, GoalResponse
from rclpy.executors import MultiThreadedExecutor
from rclpy.node import Node
from nav2_msgs.action import FollowWaypoints, NavigateThroughPoses

STEP_S = 1.0


class FakeNav2(Node):
    def __init__(self):
        super().__init__('fake_nav2')
        common = dict(goal_callback=lambda g: GoalResponse.ACCEPT, cancel_callback=lambda g: CancelResponse.ACCEPT)
        self.follow = ActionServer(self, FollowWaypoints, 'follow_waypoints', self.run_follow, **common)
        self.through = ActionServer(self, NavigateThroughPoses, 'navigate_through_poses', self.run_through, **common)

    def log_goal(self, kind, poses, extra=''):
        first = poses[0].pose.position if poses else None
        where = f' first=({first.x:.2f},{first.y:.2f})' if first else ''
        frame = poses[0].header.frame_id if poses else ''
        print(f'GOAL {kind} {len(poses)} poses frame={frame}{extra}{where}', flush=True)

    def run_follow(self, handle):
        g = handle.request
        # number_of_loops came with Jazzy; Humble's goal does not have it.
        loops = f' loops={g.number_of_loops}' if hasattr(g, 'number_of_loops') else ''
        self.log_goal('follow', g.poses, loops)
        fb = FollowWaypoints.Feedback()
        for i in range(len(g.poses)):
            if handle.is_cancel_requested:
                handle.canceled(); print('CANCELED follow', flush=True)
                return FollowWaypoints.Result()
            fb.current_waypoint = i
            handle.publish_feedback(fb)
            time.sleep(STEP_S)
        handle.succeed(); print('SUCCEEDED follow', flush=True)
        return FollowWaypoints.Result()

    def run_through(self, handle):
        g = handle.request
        # Kilted onwards: nav_msgs/Goals (header + goals); before that, a list of PoseStamped.
        poses = g.poses.goals if hasattr(g.poses, 'goals') else g.poses
        self.log_goal('through', poses)
        fb = NavigateThroughPoses.Feedback()
        n = len(poses)
        for i in range(n):
            if handle.is_cancel_requested:
                handle.canceled(); print('CANCELED through', flush=True)
                return NavigateThroughPoses.Result()
            fb.number_of_poses_remaining = n - i
            fb.distance_remaining = float(n - i) * 1.5
            fb.estimated_time_remaining.sec = (n - i) * 3
            handle.publish_feedback(fb)
            time.sleep(STEP_S)
        handle.succeed(); print('SUCCEEDED through', flush=True)
        return NavigateThroughPoses.Result()


def main():
    rclpy.init()
    node = FakeNav2()
    ex = MultiThreadedExecutor()
    ex.add_node(node)
    print('fake nav2 ready', flush=True)
    try:
        ex.spin()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()

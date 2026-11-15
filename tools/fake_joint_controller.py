#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Fake controller for MoveIt without ros2_control (to test VizMini2's MoveIt panel).

Publishes /joint_states at 50 Hz and follows the trajectories sent by MoveIt:
/panda_arm_controller/follow_joint_trajectory (control_msgs/FollowJointTrajectory) interpolating between points, and
/panda_hand_controller/gripper_cmd (control_msgs/GripperCommand). Starts in the Panda's "ready" pose.
Prints "EXECUTED n points" when each trajectory finishes.
"""
import threading
import time

import rclpy
from rclpy.action import ActionServer, CancelResponse, GoalResponse
from rclpy.callback_groups import ReentrantCallbackGroup
from rclpy.executors import MultiThreadedExecutor
from rclpy.node import Node
from control_msgs.action import FollowJointTrajectory, GripperCommand
from sensor_msgs.msg import JointState

READY = {'panda_joint1': 0.0, 'panda_joint2': -0.785, 'panda_joint3': 0.0, 'panda_joint4': -2.356,
         'panda_joint5': 0.0, 'panda_joint6': 1.571, 'panda_joint7': 0.785,
         'panda_finger_joint1': 0.0, 'panda_finger_joint2': 0.0}


class FakeController(Node):
    def __init__(self):
        super().__init__('fake_joint_controller')
        self.q = dict(READY)
        self.lock = threading.Lock()
        self.pub = self.create_publisher(JointState, '/joint_states', 10)
        self.create_timer(0.02, self.publish)
        g = ReentrantCallbackGroup()
        common = dict(goal_callback=lambda r: GoalResponse.ACCEPT, cancel_callback=lambda r: CancelResponse.ACCEPT, callback_group=g)
        ActionServer(self, FollowJointTrajectory, '/panda_arm_controller/follow_joint_trajectory', self.follow, **common)
        ActionServer(self, GripperCommand, '/panda_hand_controller/gripper_cmd', self.gripper, **common)

    def publish(self):
        m = JointState()
        m.header.stamp = self.get_clock().now().to_msg()
        with self.lock:
            m.name = list(self.q.keys())
            m.position = [float(v) for v in self.q.values()]
        self.pub.publish(m)

    def follow(self, handle):
        traj = handle.request.trajectory
        names = list(traj.joint_names)
        pts = traj.points
        t0 = time.time()
        prev_t, prev = 0.0, [self.q.get(n, 0.0) for n in names]
        for p in pts:
            t = p.time_from_start.sec + p.time_from_start.nanosec / 1e9
            while True:
                if handle.is_cancel_requested:
                    handle.canceled(); print('CANCELED', flush=True)
                    return FollowJointTrajectory.Result()
                now = time.time() - t0
                a = 1.0 if t <= prev_t else min(1.0, max(0.0, (now - prev_t) / (t - prev_t)))
                with self.lock:
                    for i, n in enumerate(names):
                        self.q[n] = prev[i] + (p.positions[i] - prev[i]) * a
                if now >= t:
                    break
                time.sleep(0.01)
            prev_t, prev = t, list(p.positions)
        handle.succeed()
        print(f'EXECUTED {len(pts)} points', flush=True)
        r = FollowJointTrajectory.Result()
        r.error_code = 0
        return r

    def gripper(self, handle):
        target = handle.request.command.position
        with self.lock:
            self.q['panda_finger_joint1'] = target
            self.q['panda_finger_joint2'] = target
        time.sleep(0.5)
        handle.succeed()
        print(f'GRIPPER {target:.3f}', flush=True)
        r = GripperCommand.Result()
        r.position = target
        r.reached_goal = True
        return r


def main():
    rclpy.init()
    n = FakeController()
    ex = MultiThreadedExecutor()
    ex.add_node(n)
    print('fake controller ready', flush=True)
    try:
        ex.spin()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()

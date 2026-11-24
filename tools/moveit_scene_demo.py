#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
"""Puts a mesh (a tetrahedron) and a box grasped by the Panda's hand into the MoveIt scene, to test the
VizMini2 PlanningScene display (meshes and attached objects). With --clear it removes them.

    source /opt/ros/<distro>/setup.bash; python3 tools/moveit_scene_demo.py [--clear]
"""
import sys
import time

import rclpy
from geometry_msgs.msg import Point, Pose
from moveit_msgs.msg import AttachedCollisionObject, CollisionObject, PlanningScene
from moveit_msgs.srv import ApplyPlanningScene
from shape_msgs.msg import Mesh, MeshTriangle, SolidPrimitive


def main():
    rclpy.init()
    node = rclpy.create_node('moveit_scene_demo')
    cli = node.create_client(ApplyPlanningScene, '/apply_planning_scene')
    if not cli.wait_for_service(timeout_sec=15.0):
        print('no /apply_planning_scene'); sys.exit(1)
    clear = '--clear' in sys.argv
    scene = PlanningScene(is_diff=True)
    scene.robot_state.is_diff = True

    tetra = CollisionObject(id='demo_tetra')
    tetra.header.frame_id = 'panda_link0'
    tetra.operation = CollisionObject.REMOVE if clear else CollisionObject.ADD
    if not clear:
        m = Mesh()
        m.vertices = [Point(x=0.0, y=0.0, z=0.0), Point(x=0.15, y=0.0, z=0.0), Point(x=0.0, y=0.15, z=0.0), Point(x=0.0, y=0.0, z=0.15)]
        for a, b, c in [(0, 2, 1), (0, 1, 3), (0, 3, 2), (1, 2, 3)]:
            t = MeshTriangle(); t.vertex_indices = [a, b, c]; m.triangles.append(t)
        tetra.meshes = [m]
        p = Pose(); p.position.x = 0.45; p.position.y = 0.35; p.position.z = 0.0; p.orientation.w = 1.0
        tetra.mesh_poses = [p]
        tetra.pose.orientation.w = 1.0
    scene.world.collision_objects.append(tetra)

    held = AttachedCollisionObject(link_name='panda_hand')
    held.object.id = 'demo_held'
    held.object.header.frame_id = 'panda_hand'
    held.object.operation = CollisionObject.REMOVE if clear else CollisionObject.ADD
    if not clear:
        box = SolidPrimitive(type=SolidPrimitive.BOX, dimensions=[0.04, 0.04, 0.08])
        p = Pose(); p.position.z = 0.12; p.orientation.w = 1.0
        held.object.primitives = [box]; held.object.primitive_poses = [p]
        held.object.pose.orientation.w = 1.0
        held.touch_links = ['panda_hand', 'panda_leftfinger', 'panda_rightfinger']
    scene.robot_state.attached_collision_objects.append(held)

    f = cli.call_async(ApplyPlanningScene.Request(scene=scene))
    t0 = time.time()
    while not f.done() and time.time() - t0 < 10:
        rclpy.spin_once(node, timeout_sec=0.1)
    print('ok' if f.done() and f.result().success else 'failed', flush=True)


if __name__ == '__main__':
    main()

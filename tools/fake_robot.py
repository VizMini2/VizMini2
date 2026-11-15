#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""Simulated robot that publishes every type VizMini2 visualizes (for testing).

Usage: source /opt/ros/jazzy/setup.bash && ROS_DOMAIN_ID=77 python3 tools/fake_robot.py
"""
import math, struct, time, zlib
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSProfile, DurabilityPolicy, ReliabilityPolicy, qos_profile_sensor_data
from geometry_msgs.msg import (TransformStamped, PoseStamped, PoseWithCovarianceStamped, PoseArray, Pose,
                               PointStamped, PolygonStamped, Point32, Point, Quaternion,
                               WrenchStamped, TwistStamped, AccelStamped)
from nav_msgs.msg import OccupancyGrid, Odometry, Path, GridCells
from sensor_msgs.msg import (CompressedImage, LaserScan, PointCloud2, PointField, PointCloud, ChannelFloat32, Range, Image,
                             Temperature, Illuminance, FluidPressure, RelativeHumidity, CameraInfo, JointState)
from visualization_msgs.msg import Marker, MarkerArray, ImageMarker
from std_msgs.msg import ColorRGBA, String
from diagnostic_msgs.msg import DiagnosticArray, DiagnosticStatus, KeyValue
from tf2_ros import TransformBroadcaster, StaticTransformBroadcaster


URDF = '''<?xml version="1.0"?>
<robot name="fake_robot">
  <material name="naranja"><color rgba="1.0 0.5 0.0 1.0"/></material>
  <material name="negro"><color rgba="0.1 0.1 0.1 1.0"/></material>
  <link name="base_link">
    <visual><origin xyz="0 0 0.1"/><geometry><box size="0.6 0.4 0.2"/></geometry><material name="naranja"/></visual>
    <visual><origin xyz="0 0 0.21"/><geometry><mesh filename="package://rviz2mini_test/meshes/arrow.stl"/></geometry>
      <material name="flecha"><color rgba="0.2 0.6 1.0 1.0"/></material></visual>
    <visual><origin xyz="-0.2 0 0.25"/><geometry><mesh filename="package://rviz2mini_test/meshes/antenna.dae"/></geometry></visual>
    <collision><origin xyz="0 0 0.1"/><geometry><box size="0.7 0.5 0.25"/></geometry></collision>
  </link>
  <link name="left_wheel"><visual><origin rpy="1.5708 0 0"/><geometry><cylinder radius="0.1" length="0.05"/></geometry><material name="negro"/></visual></link>
  <link name="right_wheel"><visual><origin rpy="1.5708 0 0"/><geometry><cylinder radius="0.1" length="0.05"/></geometry><material name="negro"/></visual></link>
  <link name="laser"><visual><geometry><cylinder radius="0.05" length="0.06"/></geometry><material name="negro"/></visual></link>
  <link name="camera_link"><visual><geometry><box size="0.04 0.12 0.04"/></geometry></visual></link>
  <link name="head"><visual><geometry><sphere radius="0.08"/></geometry></visual></link>
  <joint name="left_wheel_joint" type="continuous"><parent link="base_link"/><child link="left_wheel"/>
    <origin xyz="0 0.22 0"/><axis xyz="0 1 0"/><limit effort="10" velocity="5"/></joint>
  <joint name="right_wheel_joint" type="continuous"><parent link="base_link"/><child link="right_wheel"/>
    <origin xyz="0 -0.22 0"/><axis xyz="0 1 0"/><limit effort="10" velocity="5"/></joint>
  <joint name="laser_joint" type="fixed"><parent link="base_link"/><child link="laser"/><origin xyz="0.2 0 0.3"/></joint>
</robot>'''


def quat(yaw):
    return Quaternion(x=0.0, y=0.0, z=math.sin(yaw / 2), w=math.cos(yaw / 2))


def color(r, g, b, a=1.0):
    return ColorRGBA(r=float(r), g=float(g), b=float(b), a=float(a))


class FakeRobot(Node):
    def __init__(self):
        super().__init__('fake_robot')
        self.t0 = time.time()
        self.tf = TransformBroadcaster(self)
        self.static_tf = StaticTransformBroadcaster(self)
        latched = QoSProfile(depth=1, durability=DurabilityPolicy.TRANSIENT_LOCAL, reliability=ReliabilityPolicy.RELIABLE)
        self.pubs = {
            'map': self.create_publisher(OccupancyGrid, '/map', latched),
            'scan': self.create_publisher(LaserScan, '/scan', qos_profile_sensor_data),
            'odom': self.create_publisher(Odometry, '/odom', 10),
            'plan': self.create_publisher(Path, '/plan', 10),
            'goal': self.create_publisher(PoseStamped, '/goal_pose', 10),
            'amcl': self.create_publisher(PoseWithCovarianceStamped, '/amcl_pose', 10),
            'parray': self.create_publisher(PoseArray, '/pose_array', 10),
            'point': self.create_publisher(PointStamped, '/clicked_point', 10),
            'fp': self.create_publisher(PolygonStamped, '/local_costmap/published_footprint', 10),
            'pc2': self.create_publisher(PointCloud2, '/points', qos_profile_sensor_data),
            'pc1': self.create_publisher(PointCloud, '/cloud_old', 10),
            'range': self.create_publisher(Range, '/range', qos_profile_sensor_data),
            'markers': self.create_publisher(MarkerArray, '/visualization_marker_array', 10),
            'marker': self.create_publisher(Marker, '/visualization_marker', 10),
            'urdf': self.create_publisher(String, '/robot_description', latched),
            'diag': self.create_publisher(DiagnosticArray, '/diagnostics', 10),
            'image': self.create_publisher(Image, '/camera/image_raw', qos_profile_sensor_data),
            'info': self.create_publisher(CameraInfo, '/camera/camera_info', qos_profile_sensor_data),
            'depth': self.create_publisher(Image, '/camera/depth/image_raw', qos_profile_sensor_data),
            'depth_info': self.create_publisher(CameraInfo, '/camera/depth/camera_info', qos_profile_sensor_data),
            'depth_png': self.create_publisher(CompressedImage, '/camera/depth/image_raw/compressedDepth', qos_profile_sensor_data),
            'joints': self.create_publisher(JointState, '/joint_states', 10),
            'image_markers': self.create_publisher(ImageMarker, '/camera/image_markers', 10),
            'cells': self.create_publisher(GridCells, '/grid_cells', 10),
            'wrench': self.create_publisher(WrenchStamped, '/wrench', 10),
            'twist': self.create_publisher(TwistStamped, '/twist', 10),
            'accel': self.create_publisher(AccelStamped, '/accel', 10),
            'temp': self.create_publisher(Temperature, '/temperature', 10),
            'lux': self.create_publisher(Illuminance, '/illuminance', 10),
            'pressure': self.create_publisher(FluidPressure, '/pressure', 10),
            'humidity': self.create_publisher(RelativeHumidity, '/humidity', 10),
        }
        self.log_n = 0
        self.publish_static()
        self.publish_map()
        self.pubs['urdf'].publish(String(data=URDF))
        self.create_timer(0.05, self.fast)    # 20 Hz: tf, odom
        self.create_timer(0.1, self.sensors)  # 10 Hz: scan, clouds, range
        self.create_timer(1.0, self.slow)     # 1 Hz: path, poses, markers
        self.create_timer(1.0, self.diagnostics)  # 1 Hz: /diagnostics and messages on /rosout
        self.create_timer(0.2, self.camera)   # 5 Hz: /camera/image_raw, depth, camera_info and /joint_states
        self.create_timer(1.0, self.extras)   # 1 Hz: GridCells, Wrench/Twist/Accel and single-value sensors

    def extras(self):
        now = self.stamp()
        t = time.time()
        cells = GridCells(); cells.header.stamp = now; cells.header.frame_id = 'map'
        cells.cell_width = cells.cell_height = 0.25
        cells.cells = [Point(x=1.0 + 0.25 * i, y=-2.0, z=0.0) for i in range(12)]
        self.pubs['cells'].publish(cells)
        w = WrenchStamped(); w.header.stamp = now; w.header.frame_id = 'base_link'
        w.wrench.force.x, w.wrench.force.z = 2.0, 1.0 + math.sin(t)
        w.wrench.torque.z = 0.5
        self.pubs['wrench'].publish(w)
        tw = TwistStamped(); tw.header = w.header
        tw.twist.linear.x, tw.twist.angular.z = 0.4, 0.3
        self.pubs['twist'].publish(tw)
        ac = AccelStamped(); ac.header = w.header
        ac.accel.linear.y, ac.accel.angular.x = 0.8, 0.2
        self.pubs['accel'].publish(ac)
        for key, msg in (('temp', Temperature(temperature=21.5 + math.sin(t))), ('lux', Illuminance(illuminance=350.0)),
                         ('pressure', FluidPressure(fluid_pressure=101325.0)), ('humidity', RelativeHumidity(relative_humidity=0.45))):
            msg.header = w.header
            self.pubs[key].publish(msg)

    def camera(self):
        # 160x120 rgb8: a gradient that shifts over time (to see that the image is moving).
        w, h = 160, 120
        shift = int(time.time() * 40) % w
        row = bytearray()
        for x in range(w):
            v = ((x + shift) % w) * 255 // (w - 1)
            row += bytes((v, 255 - v, 128))
        m = Image()
        m.header.stamp = self.stamp()
        m.header.frame_id = 'camera_optical'
        m.height, m.width, m.encoding, m.step = h, w, 'rgb8', w * 3
        m.data = bytes(row) * h
        self.pubs['image'].publish(m)
        self.pubs['info'].publish(self.camera_info(m.header, w, h))
        # Annotations: a moving circle and a fixed box (in image pixels).
        circle = ImageMarker(header=m.header, ns='demo', id=0, type=ImageMarker.CIRCLE, action=ImageMarker.ADD, scale=12.0)
        circle.position.x, circle.position.y = float(20 + shift % 120), 60.0
        circle.outline_color = ColorRGBA(r=1.0, g=1.0, b=0.0, a=1.0)
        box = ImageMarker(header=m.header, ns='demo', id=1, type=ImageMarker.POLYGON, action=ImageMarker.ADD, scale=2.0)
        box.points = [Point(x=50.0, y=30.0), Point(x=110.0, y=30.0), Point(x=110.0, y=90.0), Point(x=50.0, y=90.0)]
        box.outline_color = ColorRGBA(r=1.0, g=0.0, b=0.0, a=1.0)
        self.pubs['image_markers'].publish(circle)
        self.pubs['image_markers'].publish(box)
        # Depth 80x60 32FC1: a wall at 3 m with a square at 1.5 m in the center.
        dw, dh = 80, 60
        depth = bytearray()
        for y in range(dh):
            for x in range(dw):
                depth += struct.pack('<f', 1.5 if 25 <= x < 55 and 18 <= y < 42 else 3.0)
        d = Image()
        d.header = m.header
        d.height, d.width, d.encoding, d.step, d.is_bigendian = dh, dw, '32FC1', dw * 4, 0
        d.data = bytes(depth)
        self.pubs['depth'].publish(d)
        self.pubs['depth_info'].publish(self.camera_info(m.header, dw, dh))
        # The same depth as compressed_depth_image_transport: header + 16-bit PNG in mm.
        rows = b''.join(b'\x00' + b''.join(struct.pack('>H', 1500 if 25 <= x < 55 and 18 <= y < 42 else 3000) for x in range(dw)) for y in range(dh))
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
        png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', dw, dh, 16, 0, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')
        cd = CompressedImage(header=m.header, format='16UC1; compressedDepth png')
        cd.data = struct.pack('<iff', 0, 0.0, 0.0) + png
        self.pubs['depth_png'].publish(cd)
        # Wheel efforts: oscillate between -8 and 8 N·m (limit 10).
        t = time.time() - self.t0
        js = JointState()
        js.header.stamp = m.header.stamp
        js.name = ['left_wheel_joint', 'right_wheel_joint']
        js.position = [t, t]
        js.velocity = [1.0, 1.0]
        js.effort = [8.0 * math.sin(t), -4.0]
        self.pubs['joints'].publish(js)

    @staticmethod
    def camera_info(header, w, h):
        # Roughly 90° horizontal field of view: f = w / 2.
        f = w / 2.0
        ci = CameraInfo()
        ci.header = header
        ci.width, ci.height = w, h
        ci.distortion_model = 'plumb_bob'
        ci.d = [0.0] * 5
        ci.k = [f, 0.0, w / 2.0, 0.0, f, h / 2.0, 0.0, 0.0, 1.0]
        ci.r = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
        ci.p = [f, 0.0, w / 2.0, 0.0, 0.0, f, h / 2.0, 0.0, 0.0, 0.0, 1.0, 0.0]
        return ci

    def stamp(self):
        return self.get_clock().now().to_msg()

    def robot_pose(self):
        t = time.time() - self.t0
        a = 0.2 * t
        return 2.0 * math.cos(a), 2.0 * math.sin(a), a + math.pi / 2

    def publish_static(self):
        out = []
        for parent, child, x, y, z, yaw in [('map', 'odom', 0.5, 0.0, 0.0, 0.0),
                                            ('base_link', 'laser', 0.2, 0.0, 0.3, 0.0),
                                            ('base_link', 'camera_link', 0.25, 0.0, 0.5, 0.0),
                                            ('base_link', 'left_wheel', 0.0, 0.22, 0.0, 0.0),
                                            ('base_link', 'right_wheel', 0.0, -0.22, 0.0, 0.0)]:
            ts = TransformStamped()
            ts.header.stamp = self.stamp(); ts.header.frame_id = parent; ts.child_frame_id = child
            ts.transform.translation.x, ts.transform.translation.y, ts.transform.translation.z = x, y, z
            ts.transform.rotation = quat(yaw)
            out.append(ts)
        # Camera optical frame (z forward, x right, y down), like any driver's.
        ts = TransformStamped()
        ts.header.stamp = self.stamp(); ts.header.frame_id = 'camera_link'; ts.child_frame_id = 'camera_optical'
        ts.transform.rotation = Quaternion(x=-0.5, y=0.5, z=-0.5, w=0.5)
        out.append(ts)
        self.static_tf.sendTransform(out)

    def publish_map(self):
        m = OccupancyGrid()
        m.header.frame_id = 'map'; m.header.stamp = self.stamp()
        w = h = 200
        m.info.resolution = 0.05; m.info.width = w; m.info.height = h
        m.info.origin.position.x = -5.0; m.info.origin.position.y = -5.0
        m.info.origin.orientation.w = 1.0
        data = []
        for y in range(h):
            for x in range(w):
                if x < 20 and y < 20:
                    v = -1                                    # unknown corner (bottom-left)
                elif x in (0, 1, w - 1, w - 2) or y in (0, 1, h - 1, h - 2):
                    v = 100                                   # walls
                elif 140 <= x <= 160 and 40 <= y <= 60:
                    v = 100                                   # obstacle
                elif 30 <= x <= 50 and 140 <= y <= 170:
                    v = 50
                else:
                    v = 0
                data.append(v)
        m.data = data
        self.pubs['map'].publish(m)

    def fast(self):
        x, y, yaw = self.robot_pose()
        ts = TransformStamped()
        ts.header.stamp = self.stamp(); ts.header.frame_id = 'odom'; ts.child_frame_id = 'base_link'
        ts.transform.translation.x = x; ts.transform.translation.y = y
        ts.transform.rotation = quat(yaw)
        self.tf.sendTransform(ts)
        o = Odometry()
        o.header = ts.header; o.child_frame_id = 'base_link'
        o.pose.pose.position.x = x; o.pose.pose.position.y = y; o.pose.pose.orientation = quat(yaw)
        o.twist.twist.linear.x = 0.4; o.twist.twist.angular.z = 0.2
        self.pubs['odom'].publish(o)

    def sensors(self):
        now = self.stamp()
        t = time.time() - self.t0
        # LaserScan: circular room of radius 3.5 with "pillars".
        s = LaserScan()
        s.header.stamp = now; s.header.frame_id = 'laser'
        s.angle_min = -math.pi; s.angle_max = math.pi; s.angle_increment = 2 * math.pi / 360
        s.range_min = 0.1; s.range_max = 8.0
        s.ranges = [3.0 + 0.5 * math.sin(4 * i * s.angle_increment + t) if i % 45 else float('inf') for i in range(360)]
        s.intensities = [float(i % 100) for i in range(360)]
        self.pubs['scan'].publish(s)
        # PointCloud2 xyz + rgb: dome in front of the robot.
        pts = []
        for i in range(40):
            for j in range(60):
                th = math.pi * i / 80; ph = 2 * math.pi * j / 60
                px, py, pz = 1.5 * math.sin(th) * math.cos(ph), 1.5 * math.sin(th) * math.sin(ph), 1.5 * math.cos(th)
                r, g, b = int(255 * i / 40), int(255 * j / 60), 128
                rgb = struct.unpack('f', struct.pack('I', (r << 16) | (g << 8) | b))[0]
                pts.append(struct.pack('ffff', px, py, pz, rgb))
        pc = PointCloud2()
        pc.header.stamp = now; pc.header.frame_id = 'base_link'
        pc.height = 1; pc.width = len(pts)
        pc.fields = [PointField(name='x', offset=0, datatype=7, count=1), PointField(name='y', offset=4, datatype=7, count=1),
                     PointField(name='z', offset=8, datatype=7, count=1), PointField(name='rgb', offset=12, datatype=7, count=1)]
        pc.is_bigendian = False; pc.point_step = 16; pc.row_step = 16 * len(pts); pc.is_dense = True
        pc.data = b''.join(pts)
        self.pubs['pc2'].publish(pc)
        # Old PointCloud with an intensity channel: ring on the floor.
        p1 = PointCloud()
        p1.header.stamp = now; p1.header.frame_id = 'map'
        p1.points = [Point32(x=4.0 * math.cos(k / 50), y=4.0 * math.sin(k / 50), z=0.05) for k in range(315)]
        p1.channels = [ChannelFloat32(name='intensity', values=[float(k) for k in range(315)])]
        self.pubs['pc1'].publish(p1)
        r = Range()
        r.header.stamp = now; r.header.frame_id = 'camera_link'
        r.radiation_type = Range.ULTRASOUND; r.field_of_view = 0.5; r.min_range = 0.05; r.max_range = 4.0
        r.range = 1.0 + 0.5 * math.sin(t)
        self.pubs['range'].publish(r)

    def slow(self):
        now = self.stamp()
        x, y, yaw = self.robot_pose()
        path = Path(); path.header.stamp = now; path.header.frame_id = 'map'
        for k in range(40):
            ps = PoseStamped(); ps.header = path.header
            a = k / 40 * math.pi
            ps.pose.position.x = -3 + 6 * k / 40; ps.pose.position.y = -2 + math.sin(a)
            ps.pose.orientation = quat(math.atan2(math.cos(a) * math.pi / 6, 1))
            path.poses.append(ps)
        self.pubs['plan'].publish(path)
        goal = PoseStamped(); goal.header = path.header
        goal.pose.position.x = 3.0; goal.pose.position.y = -2.0; goal.pose.orientation = quat(math.pi / 4)
        self.pubs['goal'].publish(goal)
        amcl = PoseWithCovarianceStamped(); amcl.header = path.header
        amcl.pose.pose.position.x = x + 0.5; amcl.pose.pose.position.y = y; amcl.pose.pose.orientation = quat(yaw)
        cov = [0.0] * 36; cov[0] = 0.25; cov[7] = 0.04; cov[1] = cov[6] = 0.05
        amcl.pose.covariance = cov
        self.pubs['amcl'].publish(amcl)
        pa = PoseArray(); pa.header = path.header
        for k in range(30):
            p = Pose(); p.position.x = x + 0.5 + 0.4 * math.cos(k); p.position.y = y + 0.4 * math.sin(k * 1.7)
            p.orientation = quat(yaw + 0.3 * math.sin(k)); pa.poses.append(p)
        self.pubs['parray'].publish(pa)
        pt = PointStamped(); pt.header = path.header; pt.point.x = -2.0; pt.point.y = 2.0; pt.point.z = 0.5
        self.pubs['point'].publish(pt)
        fp = PolygonStamped(); fp.header.stamp = now; fp.header.frame_id = 'base_link'
        fp.polygon.points = [Point32(x=0.3, y=0.25), Point32(x=-0.3, y=0.25), Point32(x=-0.3, y=-0.25), Point32(x=0.3, y=-0.25)]
        self.pubs['fp'].publish(fp)

        arr = MarkerArray()
        def mk(i, typ, px, py, sx=0.4, sy=0.4, sz=0.4, c=color(0, 0.6, 1)):
            m = Marker(); m.header.frame_id = 'map'; m.header.stamp = now
            m.ns = 'demo'; m.id = i; m.type = typ; m.action = Marker.ADD
            m.pose.position.x = float(px); m.pose.position.y = float(py); m.pose.position.z = 0.3; m.pose.orientation.w = 1.0
            m.scale.x, m.scale.y, m.scale.z = float(sx), float(sy), float(sz); m.color = c
            arr.markers.append(m); return m
        mk(0, Marker.CUBE, -4, 3.5, c=color(1, 0, 0))
        mk(1, Marker.SPHERE, -3, 3.5, c=color(0, 1, 0))
        mk(2, Marker.CYLINDER, -2, 3.5, c=color(0, 0, 1))
        mk(3, Marker.ARROW, -1, 3.5, sx=0.8, sy=0.1, sz=0.1, c=color(1, 1, 0))
        ls = mk(4, Marker.LINE_STRIP, 0, 3.5, sx=0.05, c=color(1, 0.5, 0))
        ls.points = [Point(x=0.4 * math.cos(k / 3), y=0.4 * math.sin(k / 3), z=0.0) for k in range(20)]
        ll = mk(5, Marker.LINE_LIST, 1, 3.5, sx=0.05, c=color(1, 1, 1))
        ll.points = [Point(x=-0.3, y=-0.3), Point(x=0.3, y=0.3), Point(x=-0.3, y=0.3), Point(x=0.3, y=-0.3)]
        cl = mk(6, Marker.CUBE_LIST, 2, 3.5, sx=0.1, sy=0.1, sz=0.1, c=color(0.8, 0.2, 0.8))
        cl.points = [Point(x=0.15 * (k % 3), y=0.15 * (k // 3)) for k in range(9)]
        sl = mk(7, Marker.SPHERE_LIST, 3, 3.5, sx=0.12, sy=0.12, sz=0.12, c=color(0.2, 1, 1))
        sl.points = [Point(x=0.0, y=0.0, z=0.15 * k) for k in range(5)]
        pm = mk(8, Marker.POINTS, 4, 3.5, sx=0.08, sy=0.08)
        pm.points = [Point(x=0.1 * math.cos(k), y=0.1 * k / 4) for k in range(12)]
        pm.colors = [color(k / 12, 1 - k / 12, 0.5) for k in range(12)]
        tx = mk(9, Marker.TEXT_VIEW_FACING, -4, 2.5, sz=0.3, c=color(1, 1, 1)); tx.text = 'Hola RViz'
        tl = mk(10, Marker.TRIANGLE_LIST, -3, 2.5, sx=1.0, sy=1.0, sz=1.0, c=color(1, 0.3, 0.3))
        tl.points = [Point(x=0.0, y=0.0), Point(x=0.5, y=0.0), Point(x=0.25, y=0.4)]
        mesh = mk(11, Marker.MESH_RESOURCE, -2, 2.5); mesh.mesh_resource = 'package://rviz2mini_test/meshes/arrow.stl'
        self.pubs['markers'].publish(arr)
        single = Marker(); single.header.frame_id = 'base_link'; single.header.stamp = now
        single.ns = 'single'; single.id = 0; single.type = Marker.ARROW; single.action = Marker.ADD
        single.pose.position.z = 0.8; single.pose.orientation = quat(time.time() - self.t0)
        single.scale.x, single.scale.y, single.scale.z = 0.6, 0.08, 0.08; single.color = color(1, 0.6, 0)
        self.pubs['marker'].publish(single)

    def diagnostics(self):
        t = time.time() - self.t0
        battery = max(0.0, 100.0 - t * 0.5)
        arr = DiagnosticArray(); arr.header.stamp = self.stamp()
        def status(name, level, msg, hw, **values):
            st = DiagnosticStatus(level=level, name=name, message=msg, hardware_id=hw)
            st.values = [KeyValue(key=k, value=str(v)) for k, v in values.items()]
            return st
        arr.status = [
            status('fake_robot: Motores', DiagnosticStatus.OK, 'Funcionando', 'motor_ctrl', temperatura=f'{40 + 5 * math.sin(t / 10):.1f}', corriente='1.2'),
            status('fake_robot: Batería', DiagnosticStatus.WARN if battery < 60 else DiagnosticStatus.OK,
                   'Batería baja' if battery < 60 else 'OK', 'bms', porcentaje=f'{battery:.0f}', voltaje=f'{11 + battery / 100:.2f}'),
            status('fake_robot: Lidar', DiagnosticStatus.ERROR, 'Sin datos de la mitad trasera', 'lidar0', frecuencia='10.0'),
        ]
        self.pubs['diag'].publish(arr)
        self.log_n += 1
        if self.log_n % 2 == 0:
            self.get_logger().info(f'Vuelta {self.log_n}: robot en movimiento')
        if self.log_n % 5 == 0:
            self.get_logger().warning(f'Batería al {battery:.0f} %')
        if self.log_n % 7 == 0:
            self.get_logger().error('Lidar: paquete perdido')


def main():
    rclpy.init()
    rclpy.spin(FakeRobot())


if __name__ == '__main__':
    main()

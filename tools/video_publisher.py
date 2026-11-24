#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
"""H.264 and H.265 video topics, to test VizMini2's video decoding: ffmpeg encodes a moving test pattern live
(x264/x265 with zero latency, a key frame every second) and each frame is published as it comes out, as
foxglove_compressed_video_transport and ffmpeg_image_transport would:

    /video/image_raw/foxglove       foxglove_msgs/msg/CompressedVideo        H.264
    /video/image_raw/ffmpeg         ffmpeg_image_transport_msgs/msg/FFMPEGPacket  H.264 (the same frames)
    /video_hevc/image_raw/foxglove  foxglove_msgs/msg/CompressedVideo        H.265 (if ffmpeg has libx265)

    source /opt/ros/<distro>/setup.bash; source "$(tools/e2e/build_msgs.sh)"
    python3 tools/video_publisher.py [--size 320x240] [--fps 15]
"""
import argparse
import shutil
import subprocess
import threading

import rclpy
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from foxglove_msgs.msg import CompressedVideo
from ffmpeg_image_transport_msgs.msg import FFMPEGPacket


def access_units(stream, aud_type, nal_type):
    """Yields the access units of an Annex B stream with delimiters (one is complete when the next one starts)."""
    buf = b""
    while True:
        chunk = stream.read1(65536) if hasattr(stream, "read1") else stream.read(65536)
        if not chunk:
            break
        buf += chunk
        # Positions of the delimiters (start codes followed by an AUD).
        starts = []
        i = 0
        while True:
            j = buf.find(b"\x00\x00\x01", i)
            if j < 0 or j + 3 >= len(buf):
                break
            if nal_type(buf[j + 3]) == aud_type:
                starts.append(j - 1 if j > 0 and buf[j - 1] == 0 else j)
            i = j + 3
        for a, b in zip(starts, starts[1:]):
            yield buf[a:b]
        if len(starts) > 1:
            buf = buf[starts[-1]:]
    if buf:
        yield buf


class VideoPublisher(Node):
    def __init__(self, size, fps):
        super().__init__("video_publisher")
        self.fox = self.create_publisher(CompressedVideo, "/video/image_raw/foxglove", qos_profile_sensor_data)
        self.ff = self.create_publisher(FFMPEGPacket, "/video/image_raw/ffmpeg", qos_profile_sensor_data)
        self.hevc = self.create_publisher(CompressedVideo, "/video_hevc/image_raw/foxglove", qos_profile_sensor_data)
        self.w, self.h = (int(v) for v in size.split("x"))
        self.fps = fps
        self.procs = []
        self.start("libx264", "h264", self.publish_h264, 9, lambda b: b & 0x1F)
        if self.has_encoder("libx265"):
            self.start("libx265", "hevc", self.publish_h265, 35, lambda b: (b >> 1) & 0x3F)
        self.get_logger().info(f"video {size} at {fps} fps")

    @staticmethod
    def has_encoder(name):
        out = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
        return f" {name} " in out

    def start(self, encoder, fmt, publish, aud_type, nal_type):
        params = f"keyint={self.fps}:min-keyint={self.fps}:repeat-headers=1:aud=1"
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-re", "-f", "lavfi",
               "-i", f"testsrc2=size={self.w}x{self.h}:rate={self.fps}", "-c:v", encoder, "-preset", "ultrafast",
               "-tune", "zerolatency", "-pix_fmt", "yuv420p",
               "-x264-params" if encoder == "libx264" else "-x265-params",
               params if encoder == "libx264" else params + ":log-level=error", "-f", fmt, "pipe:1"]
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
        self.procs.append(p)

        def loop():
            for au in access_units(p.stdout, aud_type, nal_type):
                publish(au)
        threading.Thread(target=loop, daemon=True).start()

    def key(self, au, idr):
        types = []
        i = 0
        while True:
            j = au.find(b"\x00\x00\x01", i)
            if j < 0 or j + 3 >= len(au):
                break
            types.append(au[j + 3])
            i = j + 3
        return idr(types)

    def publish_h264(self, au):
        now = self.get_clock().now().to_msg()
        m = CompressedVideo(timestamp=now, frame_id="video", data=au, format="h264")
        self.fox.publish(m)
        key = self.key(au, lambda t: any((b & 0x1F) == 5 for b in t))
        p = FFMPEGPacket(width=self.w, height=self.h, encoding="libx264", flags=1 if key else 0, data=au)
        p.header.stamp = now
        p.header.frame_id = "video"
        self.ff.publish(p)

    def publish_h265(self, au):
        self.hevc.publish(CompressedVideo(timestamp=self.get_clock().now().to_msg(), frame_id="video", data=au, format="h265"))

    def destroy_node(self):
        for p in self.procs:
            p.terminate()
        super().destroy_node()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", default="320x240")
    ap.add_argument("--fps", type=int, default=15)
    a = ap.parse_args()
    if not shutil.which("ffmpeg"):
        raise SystemExit("needs ffmpeg")
    rclpy.init()
    n = VideoPublisher(a.size, a.fps)
    try:
        rclpy.spin(n)
    except KeyboardInterrupt:
        pass
    finally:
        n.destroy_node()


if __name__ == "__main__":
    main()

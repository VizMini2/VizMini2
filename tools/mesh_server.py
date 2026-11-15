#!/usr/bin/env python3
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
"""HTTP mesh server for the VizMini2 RobotModel.

Translates the URLs requested by the app to local files:
  /<package>/<path>        -> package share (ament_index) / <path>
  /__file__/<absolute path> -> /<absolute path>   (for file:// URIs in the URDF)

Usage (on the robot, with ROS 2 sourced):
  python3 mesh_server.py --port 8000 [--package name=/path/to/share ...]
and in the app, in the RobotModel properties: "Mesh server" = http://ROBOT_IP:8000
Only serves mesh files (.stl, .dae, .obj); nothing else from the disk.
"""
import argparse
import os
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import unquote

MESH_EXTENSIONS = ('.stl', '.dae', '.obj')
OVERRIDES = {}


def package_share(pkg):
    if pkg in OVERRIDES:
        return OVERRIDES[pkg]
    try:
        from ament_index_python.packages import get_package_share_directory
        return get_package_share_directory(pkg)
    except Exception:
        return None


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        path = unquote(self.path.split('?', 1)[0]).lstrip('/')
        if not path.lower().endswith(MESH_EXTENSIONS) or '..' in path.split('/'):
            self.send_error(403, 'Solo ficheros de malla')
            return
        if path.startswith('__file__/'):
            local = '/' + path[len('__file__/'):]
        else:
            pkg, _, rest = path.partition('/')
            share = package_share(pkg)
            if share is None:
                self.send_error(404, f'Paquete desconocido: {pkg}')
                return
            local = os.path.join(share, rest)
        if not os.path.isfile(local):
            self.send_error(404, 'No existe')
            return
        with open(local, 'rb') as f:
            data = f.read()
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=8000)
    ap.add_argument('--package', action='append', default=[], help='nombre=/ruta/al/share (sin ament)')
    args = ap.parse_args()
    for p in args.package:
        name, _, root = p.partition('=')
        OVERRIDES[name] = root
    print(f'Sirviendo mallas en el puerto {args.port}')
    ThreadingHTTPServer(('0.0.0.0', args.port), Handler).serve_forever()


if __name__ == '__main__':
    main()

#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: GPL-3.0-only
#
# Screenshots and videos of the PC app for the manual, in English and Spanish, against real ROS 2 servers of this
# machine: docs/shots/<lang>/shot_pc_<name>.webp and docs/clips/<lang>/clip_pc_<name>.mp4 (docs/build.sh copies them
# next to the app's own, so `.. clip:: pc_<name>` and the images work).
#
#   tools/docs_media/pc_media.sh [name ...]     (all by default: dashboard camera maps alerts plot controllers moveit)
#
# Needs the app image (./gradlew :desktopApp:createDistributable), a sourced ROS 2 or DISTRO=lyrical, ffmpeg, and a
# display (the videos open the window for a few seconds). Servers: domain 81 the fake robot, video and map services;
# 82 MoveIt with the Panda; 83 the Panda under ros2_control. Each capture uses its own settings folder.
set -uo pipefail
cd "$(dirname "$0")/../.."
REPO="$PWD"
APP="$REPO/desktopApp/build/compose/binaries/main/app/vizmini2/bin/vizmini2"
[ -x "$APP" ] || { echo "no app image: ./gradlew :desktopApp:createDistributable"; exit 1; }
WORK="$REPO/build/docs_media"; mkdir -p "$WORK"
ONLY=" ${*:-dashboard camera maps alerts plot controllers moveit} "
want() { [[ "$ONLY" == *" $1 "* ]]; }
if [ -z "${ROS_DISTRO:-}" ]; then
  # shellcheck disable=SC1090
  set +u; source "/opt/ros/${DISTRO:-$(ls /opt/ros | head -1)}/setup.bash"; set -u
fi
MSGS="$(tools/e2e/build_msgs.sh)"

servers="ROS_DOMAIN_ID=81 python3 -u tools/fake_robot.py > $WORK/fake_robot.log 2>&1 &
(source '$MSGS'; ROS_DOMAIN_ID=81 exec python3 -u tools/video_publisher.py) > $WORK/video.log 2>&1 &
(source '$MSGS'; ROS_DOMAIN_ID=81 exec python3 -u tools/fake_map_services.py) > $WORK/maps.log 2>&1 &"
want moveit && servers="$servers
ROS_DOMAIN_ID=82 ros2 launch tools/moveit_panda_headless.launch.py > $WORK/moveit.log 2>&1 &"
want controllers && servers="$servers
ROS_DOMAIN_ID=83 ros2 launch tools/ros2_control_panda.launch.py > $WORK/ros2_control.log 2>&1 &"
setsid bash -c "$servers wait" &
PGID="$(ps -o pgid= -p $! | tr -d ' ')"
trap 'kill -TERM -- "-$PGID" 2> /dev/null; sleep 2; kill -KILL -- "-$PGID" 2> /dev/null' EXIT
echo "servers starting..."; sleep 25

# A fresh settings folder per capture (never the user's): $1 name, $2 language.
cfg() { local d="$WORK/cfg-$2-$1"; rm -rf "$d"; mkdir -p "$d"; echo "$d"; }
app() {  # $1 settings folder, $2 language, then the app's arguments.
  local c="$1" l="$2"; shift 2
  env -i HOME="$HOME" PATH=/usr/bin:/bin DISPLAY="${DISPLAY:-:0}" XAUTHORITY="${XAUTHORITY:-}" XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-}" \
    JAVA_TOOL_OPTIONS="-Dvizmini2.config=$c -Dvizmini2.lang=$l" timeout 120 "$APP" "$@" > "$c/app.log" 2>&1
}
webp() { mkdir -p "docs/shots/$2"; ffmpeg -v error -y -i "$1" -vf "scale='min(1280,iw)':-1" -c:v libwebp -quality 82 "docs/shots/$2/shot_pc_$3.webp"; }
shot() {  # $1 name, $2 language, $3 tab, $4 domain, then extra arguments.
  local n="$1" l="$2" t="$3" d="$4"; shift 4
  local c; c="$(cfg "$n" "$l")"
  app "$c" "$l" "$@" --shot "$c/shot.png" --tab "$t" --domain "$d" --size 1280x800 --millis 9000  # the first --millis counts
  [ -s "$c/shot.png" ] && webp "$c/shot.png" "$l" "$n" && echo "  shot_pc_$n ($l)" || echo "  FAILED shot $n ($l): $c/app.log"
}
record() {  # $1 name, $2 language, $3 tab, $4 domain, $5 seconds, $6 settings folder (prepared), then extra arguments.
  local n="$1" l="$2" t="$3" d="$4" s="$5" c="$6"; shift 6
  app "$c" "$l" --record "$c/rec.mp4" --seconds "$s" --tab "$t" --domain "$d" "$@"
  if [ -s "$c/rec.mp4" ]; then
    mkdir -p "docs/clips/$l"
    ffmpeg -v error -y -i "$c/rec.mp4" -vf "scale=960:-2,fps=15,format=yuv420p" -an -c:v libx264 -preset slow -crf 30 -movflags +faststart "docs/clips/$l/clip_pc_$n.mp4"
    ffmpeg -v error -y -sseof -0.5 -i "$c/rec.mp4" -frames:v 1 "$c/last.png" && webp "$c/last.png" "$l" "$n"
    echo "  clip_pc_$n ($l)"
  else echo "  FAILED record $n ($l): $c/app.log"; fi
}
# FilePrefs (java.util.Properties, "s:" for strings): one key of one preferences file.
prefs() { python3 - "$1" "$2" "$3" <<'EOF'
import sys
path, key, value = sys.argv[1:]
esc = lambda s: s.replace("\\", "\\\\").replace("\n", "\\n").encode("ascii", "backslashreplace").decode().replace("\\x", "\\u00")
with open(path, "a") as f: f.write(f"{key}=s:{esc(value)}\n")
EOF
}

for l in en es; do
  echo "== $l"
  if want dashboard; then
    c="$(cfg dashboard "$l")"; app "$c" "$l" --showcase
    record dashboard "$l" DASHBOARD 81 8 "$c"
  fi
  want camera && shot camera "$l" CAMERA 81 --set "topics=VIDEO	/video/image_raw/foxglove
RAW	/camera/image_raw
VIDEO	/video_hevc/image_raw/foxglove"
  want maps && shot maps "$l" MAPS 81 --set 'zones=KEEPOUT 100 1.0,1.0;3.5,1.0;3.5,2.5;1.0,2.5\nSPEED 40 -4.0,-4.0;-1.0,-4.0;-1.0,-2.0;-4.0,-2.0'
  if want alerts; then
    c="$(cfg alerts "$l")"
    prefs "$c/rviz2mini_alerts.properties" topic_rules '[{"id":"t","name":"Temperature above 20 C","topic":"/temperature","kind":"VALUE","field":"temperature","op":"GT","threshold":20,"silence":5,"severity":"WARNING","enabled":true},{"id":"s","name":"Laser stopped","topic":"/scan","kind":"SILENCE","field":"","op":"LT","threshold":0,"silence":3,"severity":"ERROR","enabled":true},{"id":"r","name":"Obstacle closer than 0.8 m","topic":"/range","kind":"VALUE","field":"range","op":"LT","threshold":0.8,"severity":"ERROR","enabled":true}]'
    record alerts "$l" ALERTS 81 7 "$c"
  fi
  if want plot; then
    cat > "$WORK/plot.json" <<'EOF'
{"windowS":30,"headerStamp":false,"panels":[{"kind":"TIME","series":[{"topic":"/temperature","type":"sensor_msgs/msg/Temperature","field":"temperature","label":"temperature (°C)","color":0}]},{"kind":"TIME","series":[{"topic":"/odom","type":"nav_msgs/msg/Odometry","field":"twist.twist.linear.x","label":"linear (m/s)","color":1},{"topic":"/odom","type":"nav_msgs/msg/Odometry","field":"twist.twist.angular.z","label":"angular (rad/s)","color":3}]},{"kind":"XY","sameScale":true,"series":[{"topic":"/odom","type":"nav_msgs/msg/Odometry","field":"pose.pose.position.x","label":"x","color":2},{"topic":"/odom","type":"nav_msgs/msg/Odometry","field":"pose.pose.position.y","label":"y","color":2}]}]}
EOF
    shot plot "$l" PLOT 81 --set "plotLayout=@$WORK/plot.json" --millis 25000
  fi
  # The first trajectory controller's "Move joints" (the list in the manager's order puts it fourth, as in the clip).
  want controllers && shot controllers "$l" CONTROLLERS 83 --events "wait:6000;press:482,568;release:482,568;wait:2000"
  want moveit && shot moveit "$l" MOVEIT 82
done
echo "done: docs/shots/*/shot_pc_*.webp, docs/clips/*/clip_pc_*.mp4"

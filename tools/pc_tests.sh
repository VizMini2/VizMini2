#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Interoperability tests of the phone against ROS 2 on this PC, with a single command.
#
# For each test class it starts on the PC the servers it needs (in a clean environment, with the chosen distro and
# RMW), runs the test on the phone via adb, saves the logs and stops the servers. The servers are
# started again for each class: that way a test does not inherit another one's state.
#
# Usage:
#   tools/pc_tests.sh [options] [Class[#method] ...]
#
# Options:
#   -d DISTRO   ROS 2 distro in /opt/ros (default: lyrical, jazzy, kilted or rolling, the first one present)
#   -r RMW      RMW of the PC servers (default $RMW_IMPLEMENTATION or rmw_fastrtps_cpp)
#   -s SERIAL   adb phone (default $ANDROID_SERIAL or the first one connected)
#   -p IP       PC IP as seen by the phone (default: that of the interface that reaches the phone)
#   -b          build and install the app and the tests first (direct debug, arm64-v8a)
#   -l          list the classes and their servers and exit
#
# Without classes it runs the normal suite (all except PcInteropTest). Examples:
#   tools/pc_tests.sh -b
#   tools/pc_tests.sh ButtonsPcTest RvizDisplaysPcTest#badMessageDoesNotLeaveTheDisplayInError
#   tools/pc_tests.sh -r rmw_cyclonedds_cpp ButtonsPcTest
#
# Logs in build/pc-tests/<date>/. Returns the number of classes that failed.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
APP=com.juancams.vizmini2
RUNNER=com.juancams.vizmini2.test/com.juancams.rviz2mini.TestRunner
PKG=com.juancams.rviz2mini
DOMAIN=77
CLASS_TIMEOUT=900

DEFAULT_CLASSES=(ButtonsPcTest SlidersPcTest IndicatorsPcTest PlotPcTest InteractiveMarkersPcTest BehaviorTreePcTest CamerasPcTest PanelsPcTest StreamsPcTest BridgePcTest BridgeParamsPcTest RvizDisplaysPcTest GraphLifecyclePcTest NewFeaturesPcTest KnownRobotsPcTest ExploreUiPcTest ForegroundServiceDeviceTest DiagnosticsDeviceTest ProfilesAndImportDeviceTest ZenohPcTest)

# Servers for each class (see each test's header).
servers_for() {
    case "$1" in
        ButtonsPcTest) echo "buttons" ;;
        SlidersPcTest) echo "" ;;
        IndicatorsPcTest) echo "indicators" ;;
        PlotPcTest) echo "indicators" ;;
        InteractiveMarkersPcTest) echo "robot im" ;;
        BehaviorTreePcTest) echo "bt" ;;
        CamerasPcTest) echo "robot cam2" ;;
        PanelsPcTest) echo "robot params indicators" ;;
        StreamsPcTest) echo "streams" ;;
        BridgePcTest) echo "robot buttons params lifecycle bridge" ;;
        BridgeParamsPcTest) echo "params bridgeparams" ;;
        RvizDisplaysPcTest) echo "robot badcloud mesh" ;;
        GraphLifecyclePcTest) echo "buttons robot lifecycle" ;;
        NewFeaturesPcTest) echo "robot params ds buttons" ;;
        KnownRobotsPcTest|RewindPcTest) echo "robot" ;;
        Nav2PcTest) echo "nav2" ;;
        MoveItPcTest) echo "moveit mesh" ;;
        ExploreUiPcTest) echo "buttons robot lifecycle" ;;
        # Named DeviceTest but they expect the PC's fake robot.
        ForegroundServiceDeviceTest|DiagnosticsDeviceTest|ProfilesAndImportDeviceTest) echo "robot" ;;
        PcInteropTest) echo "interop" ;;
        # Only with -r rmw_zenoh_cpp (otherwise skipped): the router is started by start_servers.
        ZenohPcTest) echo "robot buttons" ;;
        *) echo "?" ;;
    esac
}

# Commands for each server (run with the distro already sourced and ROS_DOMAIN_ID=$DOMAIN).
server_cmd() {
    case "$1" in
        buttons) cat <<EOF
python3 tools/buttons_test_server.py &
ros2 run demo_nodes_cpp add_two_ints_server --ros-args -r __ns:=/rviz2mini_test/cpp &
ros2 run action_tutorials_cpp fibonacci_action_server --ros-args -r __ns:=/rviz2mini_test/cpp &
EOF
        ;;
        robot) echo "python3 tools/fake_robot.py &" ;;
        nav2) echo "python3 tools/nav2_waypoints_server.py &" ;;
        moveit) printf '%s\n' "ros2 launch tools/moveit_panda_headless.launch.py &" "(sleep 15; python3 tools/moveit_scene_demo.py) &" ;;
        streams) echo "python3 tools/stream_test_server.py &" ;;
        cam2) echo "ros2 run image_tools cam2image --ros-args -p burger_mode:=true &" ;;
        badcloud) echo "python3 tools/bad_cloud_pub.py &" ;;
        indicators) echo "python3 tools/indicators_test_pub.py &" ;;
        im) echo "python3 tools/interactive_markers_server.py &" ;;
        bt) cat <<EOF
[ -x tools/bt_groot/bt_groot_server ] || tools/bt_groot/build.sh
tools/bt_groot/bt_groot_server 1667 &
python3 tools/bt_log_pub.py &
EOF
        ;;
        bridge) echo "ros2 run foxglove_bridge foxglove_bridge --ros-args -p port:=8765 -p include_hidden:=true &" ;;
        # No services: parameters can only go through the "parameters" capability.
        bridgeparams) echo "ros2 run foxglove_bridge foxglove_bridge --ros-args -r __node:=foxglove_bridge_params -p port:=8766 -p 'service_whitelist:=[\"^/none\$\"]' &" ;;
        mesh) echo "python3 tools/mesh_server.py --port 8000 --package rviz2mini_test=tools/test_meshes/rviz2mini_test &" ;;
        lifecycle) echo "ros2 run lifecycle lifecycle_talker --ros-args -r __ns:=/rviz2mini_test &" ;;
        params) echo "ros2 run demo_nodes_cpp parameter_blackboard --ros-args -r __ns:=/rviz2mini_test -p speed:=1.5 -p name:=robot -p enabled:=true -p 'gains:=[1.0, 2.0]' -p 'ids:=[1, 2, 3]' &" ;;
        ds) cat <<EOF
fastdds discovery -i 0 -l $PC_IP -p 11811 &
sleep 2
ROS_DOMAIN_ID=79 ROS_DISCOVERY_SERVER=$PC_IP:11811 ros2 run demo_nodes_cpp talker --ros-args -r chatter:=/rviz2mini_test/ds_chatter &
EOF
        ;;
        interop) cat <<EOF
ros2 topic echo /cmd_vel geometry_msgs/msg/Twist > /dev/null &
ros2 run image_tools cam2image --ros-args -p burger_mode:=true &
ros2 run tf2_ros static_transform_publisher --x 1 --y 2 --yaw 1.5707963 --frame-id map --child-frame-id odom &
EOF
        ;;
    esac
}

die() { echo "pc_tests: $*" >&2; exit 100; }

DISTRO="" RMW="${RMW_IMPLEMENTATION:-rmw_fastrtps_cpp}" SERIAL="${ANDROID_SERIAL:-}" PC_IP="" BUILD=0 LIST=0
while getopts "d:r:s:p:blh" o; do
    case "$o" in
        d) DISTRO="$OPTARG" ;; r) RMW="$OPTARG" ;; s) SERIAL="$OPTARG" ;; p) PC_IP="$OPTARG" ;;
        b) BUILD=1 ;; l) LIST=1 ;;
        *) sed -n '4,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    esac
done
shift $((OPTIND - 1))
CLASSES=("$@"); [ ${#CLASSES[@]} -eq 0 ] && CLASSES=("${DEFAULT_CLASSES[@]}")

if [ "$LIST" = 1 ]; then
    for c in "${DEFAULT_CLASSES[@]}" PcInteropTest; do printf '%-22s %s\n' "$c" "$(servers_for "$c")"; done
    exit 0
fi

# --- Environment ---------------------------------------------------------------------------------------------
if [ -z "$DISTRO" ]; then
    for d in lyrical jazzy kilted rolling; do [ -f "/opt/ros/$d/setup.bash" ] && { DISTRO=$d; break; }; done
fi
[ -f "/opt/ros/$DISTRO/setup.bash" ] || die "no está ROS 2 '$DISTRO' en /opt/ros"
[ -f "/opt/ros/$DISTRO/lib/lib$RMW.so" ] || die "$RMW no está instalado para $DISTRO (sudo apt install ros-$DISTRO-${RMW//_/-})"
case "$RMW" in rmw_zenoh*) echo "pc_tests: aviso: con Zenoh solo ZenohPcTest conecta por Zenoh; las demás clases van por DDS y no verán los servidores del PC" >&2 ;; esac

[ -x "$ADB" ] || ADB="$(command -v adb)" || die "no encuentro adb (ANDROID_HOME=${ANDROID_HOME:-})"
[ -n "$SERIAL" ] || SERIAL="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
[ -n "$SERIAL" ] || die "no hay ningún móvil conectado por adb"
adbs() { "$ADB" -s "$SERIAL" "$@"; }

PHONE_IP="$(adbs shell ip -4 -o addr show wlan0 2>/dev/null | awk '{ sub(/\/.*/, "", $4); print $4; exit }')"
if [ -z "$PC_IP" ]; then
    [ -n "$PHONE_IP" ] || die "no sé la IP del móvil (¿Wi-Fi?): pasa -p <IP del PC>"
    PC_IP="$(ip -4 route get "$PHONE_IP" 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit } }')"
fi
[ -n "$PC_IP" ] || die "no sé la IP del PC: pasa -p <IP>"

# Servers from a previous run that were not stopped would skew the results (duplicate topics).
if pgrep -f "^python3 tools/(fake_robot|bad_cloud_pub|buttons_test_server|mesh_server|nav2_waypoints_server)\.py" > /dev/null; then
    die "ya hay servidores de prueba en marcha (pgrep -af 'python3 tools/'): páralos antes"
fi

LOGS="$ROOT/build/pc-tests/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$LOGS"
echo "ROS 2 $DISTRO · $RMW · móvil $SERIAL (${PHONE_IP:-?}) · PC $PC_IP · logs en $LOGS"

# --- Build and install -----------------------------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
    echo "Compilando..."
    (cd "$ROOT" && ./gradlew -q assembleDirectDebug assembleDirectDebugAndroidTest) > "$LOGS/build.log" 2>&1 \
        || die "falla la compilación (ver $LOGS/build.log)"
    # Both APKs must come from the same build: otherwise the app gets SIGSEGV.
    # The APK for the phone's ABI (arm64 on real devices, x86_64 on emulators) or, if missing, the universal one.
    # -d: allows installing over a debug build with a higher versionCode (another branch) without wiping data.
    abi=$(adbs shell getprop ro.product.cpu.abi | tr -d '\r')
    apk="$ROOT/app/build/outputs/apk/direct/debug/app-direct-$abi-debug.apk"
    [ -f "$apk" ] || apk="$ROOT/app/build/outputs/apk/direct/debug/app-direct-universal-debug.apk"
    adbs install -r -d "$apk" > "$LOGS/install.log" 2>&1 \
        && adbs install -r -d "$ROOT/app/build/outputs/apk/androidTest/direct/debug/app-direct-debug-androidTest.apk" >> "$LOGS/install.log" 2>&1 \
        || die "falla la instalación (ver $LOGS/install.log)"
fi
# MIUI/HyperOS: without this permission the activity does not open in the background and the test waits until the
# class timeout. MIUI resets it to "ignore" midway through the suite, so it is renewed every few
# seconds while the tests run (on other phones the command just fails).
adbs shell appops set "$APP" 10021 allow > /dev/null 2>&1 || true
KEEPER_PID=""
if adbs shell appops get "$APP" 10021 2> /dev/null | grep -q MIUIOP; then
    ( while sleep 5; do adbs shell appops set "$APP" 10021 allow > /dev/null 2>&1; done ) &
    KEEPER_PID=$!
fi
stop_keeper() { [ -n "$KEEPER_PID" ] && kill "$KEEPER_PID" 2> /dev/null; KEEPER_PID=""; }

# Screen on and unlocked while the tests run: with it off the activity stops and the UI tests
# fail ("Can not perform this action after onSaveInstanceState"). At the end the setting is restored.
STAY_ON="$(adbs shell settings get global stay_on_while_plugged_in 2> /dev/null | tr -d '\r')"
restore_screen() { [ -n "$STAY_ON" ] && adbs shell settings put global stay_on_while_plugged_in "$STAY_ON" > /dev/null 2>&1; STAY_ON=""; }
wake_phone() {
    adbs shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
    adbs shell wm dismiss-keyguard > /dev/null 2>&1
}
adbs shell svc power stayon usb > /dev/null 2>&1
wake_phone; sleep 1
if adbs shell dumpsys window 2> /dev/null | grep -q 'isKeyguardShowing=true'; then
    echo "pc_tests: aviso: el móvil está bloqueado (PIN o patrón): desbloquéalo, los tests de interfaz lo necesitan" >&2
fi

# --- Servers -------------------------------------------------------------------------------------------------
SERVERS_PGID=""
start_servers() { # $1: list of servers, $2: log file
    # Without servers there is nothing to start (the shell would exit immediately and leave no group to check).
    if [ -z "$1" ]; then SERVERS_PGID=""; : > "$2"; return 0; fi
    # Without job control (set -m): that way all servers stay in this shell's process group and
    # are stopped together. With it, each one had its own group and they stayed alive from one class to the next.
    local script="source /opt/ros/$DISTRO/setup.bash; export ROS_DOMAIN_ID=$DOMAIN RMW_IMPLEMENTATION=$RMW; cd '$ROOT'"
    # The caller's Cyclone DDS configuration (e.g. AllowMulticast), which the clean environment would lose.
    [ -n "${CYCLONEDDS_URI:-}" ] && script+="; export CYCLONEDDS_URI='$CYCLONEDDS_URI'"
    local s
    # With rmw_zenoh_cpp the nodes need the Zenoh router (the phone also connects to it).
    if [ "$RMW" = rmw_zenoh_cpp ]; then script+=$'\n'"ros2 run rmw_zenoh_cpp rmw_zenohd &"$'\n'"sleep 2"; fi
    for s in $1; do script+=$'\n'"$(server_cmd "$s")"; done
    script+=$'\nwait'
    # Clean environment: the user's profile may bring another distro or sourced workspaces.
    # setsid: new process group (and session) so they can all be stopped without touching this script.
    setsid env -i HOME="$HOME" PATH=/usr/local/bin:/usr/bin:/bin LANG="${LANG:-C.UTF-8}" bash -c "$script" > "$2" 2>&1 &
    local pid=$!
    sleep 0.2
    SERVERS_PGID="$(ps -o pgid= -p "$pid" 2> /dev/null | tr -d ' ')"
    [ -n "$SERVERS_PGID" ] && [ "$SERVERS_PGID" != "$(ps -o pgid= -p $$ | tr -d ' ')" ] || die "no se pudo aislar el grupo de procesos de los servidores"
    sleep 4 # node discovery and startup
}
stop_servers() {
    [ -n "$SERVERS_PGID" ] || return 0
    kill -TERM -- "-$SERVERS_PGID" 2> /dev/null
    for _ in $(seq 20); do pgrep -g "$SERVERS_PGID" > /dev/null || break; sleep 0.25; done
    kill -KILL -- "-$SERVERS_PGID" 2> /dev/null
    sleep 0.5
    pgrep -g "$SERVERS_PGID" > /dev/null && echo "pc_tests: aviso: quedan servidores del grupo $SERVERS_PGID" >&2
    SERVERS_PGID=""
}
trap 'stop_servers; stop_keeper; restore_screen; exit 130' INT TERM
trap 'stop_servers; stop_keeper; restore_screen' EXIT

# --- Tests ---------------------------------------------------------------------------------------------------
FAILED=()
for spec in "${CLASSES[@]}"; do
    cls="${spec%%#*}"
    servers="$(servers_for "$cls")"
    [ "$servers" = "?" ] && { echo "pc_tests: clase desconocida: $cls (usa -l)" >&2; FAILED+=("$spec"); continue; }
    name="${spec//#/_}"
    printf '%-60s ' "$spec [${servers:-sin servidores}]"
    start_servers "$servers" "$LOGS/$name.servers.log"
    wake_phone
    adbs logcat -c 2> /dev/null
    # Safety net: the user's tabs before the test (compared and, if they change, restored afterwards).
    adbs exec-out run-as "$APP" cat shared_prefs/rviz2mini_tabs.xml > "$LOGS/$name.tabs_before.xml" 2> /dev/null
    timeout "$CLASS_TIMEOUT" "$ADB" -s "$SERIAL" shell am instrument -w -e pc true -e peers "$PC_IP" -e pcIp "$PC_IP" -e rmw "$RMW" \
        -e class "$PKG.$spec" "$RUNNER" > "$LOGS/$name.txt" 2>&1
    code=$?
    adbs logcat -d -v time > "$LOGS/$name.logcat.txt" 2> /dev/null
    stop_servers
    adbs exec-out run-as "$APP" cat shared_prefs/rviz2mini_tabs.xml > "$LOGS/$name.tabs_after.xml" 2> /dev/null
    tabs_changed=""
    if [ -s "$LOGS/$name.tabs_before.xml" ] && ! cmp -s "$LOGS/$name.tabs_before.xml" "$LOGS/$name.tabs_after.xml"; then
        tabs_changed=1
        adbs shell am force-stop "$APP" > /dev/null 2>&1
        adbs push "$LOGS/$name.tabs_before.xml" /data/local/tmp/vm_tabs.xml > /dev/null 2>&1 \
            && adbs shell "run-as $APP sh -c 'cat /data/local/tmp/vm_tabs.xml > shared_prefs/rviz2mini_tabs.xml'" \
            && adbs shell rm -f /data/local/tmp/vm_tabs.xml
    fi
    if [ $code -eq 124 ]; then
        echo "COLGADO (${CLASS_TIMEOUT}s)"; FAILED+=("$spec")
        adbs shell am force-stop "$APP" > /dev/null 2>&1
    elif grep -q '^OK (' "$LOGS/$name.txt"; then
        echo "OK $(grep -o '^OK ([0-9]* tests\{0,1\})' "$LOGS/$name.txt" | sed 's/^OK //')"
    else
        echo "FALLA: $(grep -E '^Tests run:|shortMsg=' "$LOGS/$name.txt" | head -1)"; FAILED+=("$spec")
    fi
    if [ -n "$tabs_changed" ]; then
        echo "    ¡AVISO! $spec cambió las pestañas del usuario: devueltas (antes/después en $LOGS/$name.tabs_*.xml)"
        FAILED+=("$spec(pestañas)")
    fi
done

echo
if [ ${#FAILED[@]} -eq 0 ]; then echo "Todo bien."; else echo "Fallan: ${FAILED[*]} (detalles en $LOGS)"; fi
exit ${#FAILED[@]}

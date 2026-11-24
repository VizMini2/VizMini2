#!/usr/bin/env bash
# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
# Starts the simple example and, once it is up, gives it its problem and runs a plan; then again every
# PAUSE seconds (another goal each time), so the PlanSys2 tab always has something to show. Ctrl+C stops it.
set -e
source /ws/install/setup.bash
PAUSE=${PAUSE:-10}
ros2 launch plansys2_simple_example plansys2_simple_example.launch.yaml > /tmp/launch.log 2>&1 &
sleep 8
CMDS=$(ros2 pkg prefix plansys2_simple_example)/share/plansys2_simple_example/launch/commands
goals=("(and(robot_at leia bathroom))" "(and(robot_at leia kitchen))" "(and(robot_at leia entrance))")
i=0
while true; do
  goal=${goals[$((i % ${#goals[@]}))]}
  echo "=== plan $((i + 1)): $goal"
  # From scratch each time: the problem as the example gives it, with another goal.
  ros2 service call /problem_expert/clear_problem_knowledge plansys2_msgs/srv/ClearProblemKnowledge > /dev/null || true
  { grep -v '^set goal' "$CMDS"; echo "set goal $goal"; echo "run"; echo "quit"; } | ros2 run plansys2_terminal plansys2_terminal || true
  i=$((i + 1))
  sleep "$PAUSE"
done

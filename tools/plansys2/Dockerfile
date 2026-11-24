# Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
# SPDX-License-Identifier: Apache-2.0
#
# PlanSys2 rolling from source with its simple example (a robot that moves between rooms and charges), to try the
# PlanSys2 tab against a real one. See README.md.
FROM ros:rolling
SHELL ["/bin/bash", "-c"]
RUN apt-get update && apt-get install -y --no-install-recommends python3-vcstool git && rm -rf /var/lib/apt/lists/*
WORKDIR /ws/src
RUN git clone --depth 1 -b rolling https://github.com/PlanSys2/ros2_planning_system.git \
 && vcs import < ros2_planning_system/dependency_repos.repos \
 && git clone --depth 1 -b rolling https://github.com/PlanSys2/ros2_planning_system_examples.git examples \
 && find examples -mindepth 1 -maxdepth 1 -type d ! -name plansys2_simple_example ! -name '.*' -exec touch {}/COLCON_IGNORE \;
WORKDIR /ws
RUN apt-get update && rosdep update && rosdep install -y -r -q --from-paths src --ignore-src && rm -rf /var/lib/apt/lists/*
RUN source /opt/ros/rolling/setup.bash \
 && colcon build --cmake-args -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF \
    --packages-up-to plansys2_simple_example plansys2_terminal plansys2_tui_cli
COPY run.sh /run.sh
RUN chmod +x /run.sh
ENTRYPOINT ["/run.sh"]

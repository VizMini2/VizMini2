# PlanSys2 rolling, to try the PlanSys2 tab

A container with PlanSys2 rolling built from source and its simple example (the robot *leia* moving
between rooms and charging): it gives it the problem and runs a plan, and again every few seconds with
another goal, so the PlanSys2 tab (and PlanSys2's own TUI) always have something to show.

```bash
docker build -t vizmini2-plansys2 tools/plansys2            # once (about 15 min)
docker run --rm -it --net=host -e ROS_DOMAIN_ID=42 -e FASTDDS_BUILTIN_TRANSPORTS=UDPv4 vizmini2-plansys2
```

Then, on the same computer (or on the phone, on the same network), connect VizMini2 to domain 42 and
add a PlanSys2 tab. `PAUSE=20` (with `-e`) leaves more time between plans.

To compare with PlanSys2's terminal monitor, in another terminal:

```bash
docker exec -it $(docker ps -q -f ancestor=vizmini2-plansys2) bash -c \
  'source /ws/install/setup.bash && ros2 run plansys2_tui_cli plansys2_tui'
```

`--net=host` lets Fast DDS reach the host; with another network setup, use VizMini2's discovery peers instead.
`FASTDDS_BUILTIN_TRANSPORTS=UDPv4` is needed: the container runs as root, and its Fast DDS (the same version as
VizMini2's) would otherwise send the data through shared memory that VizMini2, running as your user, can't open (the
topics show up, but nothing arrives). Running the container as your user (`--user $(id -u):$(id -g) -e HOME=/tmp`)
works too.

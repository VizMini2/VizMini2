PlanSys2
========

.. clip:: plansys2

Follow a `PlanSys2 <https://plansys2.github.io/>`_ plan while it runs, as its terminal monitor
(``plansys2_tui``) does, on the phone or on the PC.

* **Overview:** the monitor's four boxes together. Tap a box's title to open it on its own.
* **Plan:** the plan being executed, in order, each action with its state (pending, executing,
  succeeded, failed, cancelled), its progress, its planned start and duration, and what its
  performer says (``executing_plan`` and ``action_execution_info``).
* **Actions:** the actions under way, the node that performs each one, its last message on
  ``actions_hub`` (request, feedback, finish…) and how far it goes.
* **Performers:** every action performer with its state (ready, running, failure) and its action
  (``performers_status``).
* **Knowledge:** what the problem expert knows (``problem_expert/knowledge``): instances, predicates,
  functions with their values, the goal with each of its predicates ticked when it holds now, the
  metric and the constraints. In this view a filter finds a predicate or an object.
* **Plan log:** what the executor does while the plan runs, and why, one line per event
  (``executor/execution_trace``): each action starting and ending on time, late or early, the plan
  shifting, failures and replans. *Follow* keeps the newest line in sight; *Clear* empties it.

It only listens to topics, so it does not change anything on the robot. If the PlanSys2 nodes run in
a namespace, type it in *Namespace*. It understands the messages of PlanSys2 rolling and those of the
released versions (Humble, Jazzy); the plan log needs a PlanSys2 that publishes the execution trace.

The demo robot has a PlanSys2 of its own: a cleaning robot that cleans rooms and goes back to charge,
one plan after another.

MoveIt
======

.. clip:: moveit

Planifica y mueve un brazo robótico con MoveIt (``move_group``), desde el móvil o el PC.

* Lee el robot y sus grupos de planificación de ``move_group`` (URDF y SRDF), con las poses con
  nombre como *ready*.
* Pon el objetivo articulación por articulación con un slider cada una, o mueve la pinza en X, Y, Z
  y alrededor de sus ejes (MoveIt resuelve las articulaciones), y luego **Planificar**, **Ejecutar**
  o **Planificar y ejecutar**, a la velocidad que elijas. **Parar** cancela el movimiento.
* *En línea recta* planifica una trayectoria cartesiana de la punta en vez de dejar que elija el
  planificador.
* *Añadir obstáculo* pone un obstáculo entre la pinza y el objetivo para que el plan lo esquive.
* En las opciones se elige el planificador (OMPL, Pilz, CHOMP... lo que ofrezca ``move_group``), el
  tiempo de planificación, el namespace de ``move_group`` y la versión de los mensajes de MoveIt
  (Humble, Jazzy, Lyrical: se detecta sola).

Añade un display *Trajectory* a una pestaña RViz para ver el movimiento planificado con un robot
fantasma, y un display *Planning scene* para ver los objetos de colisión.

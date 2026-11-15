Prueba la demo
==============

.. clip:: rviz
   :caption: El robot de la demo en la pestaña RViz.

La demo arranca un **robot simulado dentro de tu móvil**: un pequeño robot diferencial en una
habitación de 10 × 8 m con cajas. Publica lo mismo que un robot móvil real (TF, odometría, láser,
mapa, cámara, también como vídeo H.264, diagnósticos, batería, GPS, logs, su URDF y una ruta
planificada), obedece a ``/cmd_vel`` y se mueve solo cuando nadie usa el joystick.

Además ofrece parámetros, servicios, una acción, un nodo lifecycle y un controller manager de
ros2_control, para que todas las pestañas tengan algo que enseñar:

* Parámetros de ``/demo_robot``: ``autopilot``, ``max_linear_speed``, ``max_angular_speed``,
  ``robot_name`` (solo lectura) y ``use_sim_time``.
* Servicios: ``/demo_robot/return_home`` (``std_srvs/Trigger``), ``/demo_robot/set_autopilot``
  (``std_srvs/SetBool``) y ``/add_two_ints``.
* Acción: ``/fibonacci`` (``example_interfaces/action/Fibonacci``), con feedback y cancelación.
* Nodo lifecycle ``/camera_driver``: la cámara solo publica mientras está *active*.
* ``/controller_manager`` con ``joint_state_broadcaster`` y ``diff_drive_controller`` activos:
  desactiva ``diff_drive_controller`` en una pestaña Controladores y el robot se para.

La demo usa su propio dominio de ROS (231) y solo la red interna del móvil (loopback), así que
**nada llega a tu Wi-Fi** y nunca interfiere con robots reales.

Arrancarla
----------

En Inicio pulsa **Probar la demo** (o **Iniciar modo demo** en el recorrido rápido). Tus pestañas
y tu conexión se guardan y vuelven al pulsar **Salir de la demo**. No hace falta cuenta.

La demo añade pestañas ya preparadas: *RViz demo*, *Conducir*, *Cámara*, *Diagnóstico*, *Topics*,
*Botones*, *Sliders*, *Indicadores*, *Mapa GPS*, *Gráficas*, *Lifecycle* y *Dashboard demo*.

La demo solo está en la app de Android; la app del PC no tiene modo demo.

.. container:: vm-clear

   Siguiente: :doc:`../tutorials/drive-demo`.

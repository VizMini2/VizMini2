Requisitos
==========

Móvil
-----

* Android 8.0 (API 26) o superior. Probada en Android 16.
* Una GPU con OpenGL ES 3 para la vista 3D (cualquier móvil reciente).
* Wi-Fi en la red del robot, o una VPN / Discovery Server. Con un router de Zenoh o
  foxglove_bridge, cualquier red desde la que se llegue al puerto del robot.

PC
--

* **Ubuntu 20.04 o posterior**, x86-64 (glibc 2.31 o posterior), con un driver OpenGL para la vista
  3D. En Wayland funciona a través de XWayland.
* Instalada desde el ``.deb`` (trae todo lo que necesita; no hace falta tener ROS 2 instalado en el
  PC), desde el ``.tar.gz``, o compilada como paquete de ROS 2 con ``colcon build``
  (:doc:`../getting-started/desktop`).
* Recomendado: ``ffmpeg`` (``sudo apt install ffmpeg``) para los topics de vídeo H.264/H.265 y para
  grabar la ventana, y ``notify-send`` (paquete ``libnotify-bin``, ya incluido en casi todos los
  escritorios) para las notificaciones de las alertas.
* O **Windows 10 u 11**, 64 bits (x86-64), con un driver OpenGL para la vista 3D, instalada desde el
  ``.msi`` para tu usuario (sin administrador) o descomprimida del ``.zip``. También ahí se
  recomienda ``ffmpeg`` (``winget install ffmpeg``). Mira :doc:`../getting-started/desktop`.

Robot
-----

* ROS 2 **Foxy, Humble, Jazzy, Kilted o Lyrical**.
* **Fast DDS** (``rmw_fastrtps_cpp``) o **Cyclone DDS** (``rmw_cyclonedds_cpp``): topics,
  servicios, acciones, parámetros y lifecycle, incluso los dos en la misma red. Los topics
  funcionan también con otras implementaciones de DDS.
* **Zenoh** (``rmw_zenoh_cpp``): todo, a través del router de Zenoh del robot
  (:doc:`../config/network`).
* Cualquier otro middleware: a través de `foxglove_bridge
  <https://docs.foxglove.dev/docs/visualization/ros-foxglove-bridge>`_ en el robot.
* Interfaces incluidas (unas 470): ``action_msgs``, ``action_tutorials_interfaces``,
  ``actionlib_msgs``, ``builtin_interfaces``, ``control_msgs``, ``controller_manager_msgs``,
  ``diagnostic_msgs``, ``example_interfaces``, ``ffmpeg_image_transport_msgs``, ``foxglove_msgs``,
  ``geographic_msgs``, ``geometry_msgs``, ``lifecycle_msgs``, ``moveit_msgs``, ``nav2_msgs``,
  ``nav_msgs``, ``navmap_ros_interfaces``, ``object_recognition_msgs``, ``octomap_msgs``, ``rcl_interfaces``,
  ``rosgraph_msgs``, ``sensor_msgs``, ``service_msgs``, ``shape_msgs``, ``slam_toolbox``,
  ``std_msgs``, ``std_srvs``, ``tf2_msgs``, ``trajectory_msgs``, ``turtlesim``,
  ``unique_identifier_msgs`` y ``visualization_msgs``. Cuando los mensajes de MoveIt
  (``moveit_msgs``) y de ros2_control (``controller_manager_msgs``) cambian de una distribución a
  otra, la app detecta sola la versión del robot.

Permisos (móvil)
----------------

Internet y multicast Wi-Fi (para DDS), notificaciones (alertas del robot), un servicio en primer
plano (mantener la conexión en segundo plano) y captura de pantalla (solo cuando grabas un vídeo de
la pantalla).

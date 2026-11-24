Limitaciones conocidas
======================

* **Multicast:** muchas redes Wi-Fi lo descartan; usa Discovery peers, un Discovery Server, un
  router de Zenoh o foxglove_bridge (:doc:`../config/network`).
* **Los servicios, las acciones, los parámetros y lifecycle por DDS** necesitan Fast DDS o Cyclone
  DDS en el robot. Con otros middlewares, usa un router de Zenoh o foxglove_bridge.
* **Los tipos de mensaje** que no vienen con la app (:doc:`requirements` lista los paquetes) hay
  que definirlos pegando el texto de su ``.msg``, ``.srv`` o ``.action`` (:ref:`tus propios tipos <custom-types>`); los
  displays de la vista 3D solo admiten los tipos estándar.
* **Las mallas** (modelo del robot y markers ``MESH_RESOURCE``) tienen que ser STL o Collada
  (``.dae``). El móvil las descarga de un servidor de mallas en el robot; el PC además las busca en
  su propia instalación o workspace de ROS 2.
* **Los bags** solo pueden ser MCAP (no ``.db3``). La app los graba sin comprimir; reproduce bags
  sin comprimir, con LZ4 y con Zstandard.
* **La pestaña Cámara** se suscribe con *Best effort* (también recibe de publicadores
  *Reliable*). Los displays Image y Camera de RViz te dejan elegir la QoS.
* La **frecuencia de teleop** es fija, 10 Hz.
* **ROS 2 Lyrical:** las nubes de puntos y las imágenes pueden dejar de llegar después de usar
  ``ros2 topic echo`` o ``ros2 topic hz`` en el robot (un fallo del ``rmw_fastrtps_cpp`` de
  Lyrical): mira :doc:`troubleshooting`.
* **PC:** no admite mando (se conduce con el teclado o el ratón); el vídeo H.264/H.265 y grabar la
  ventana necesitan ``ffmpeg``. Solo PCs x86-64 con Linux o Windows: ni placas ARM (Raspberry Pi,
  Jetson…) ni macOS.
* **Los servicios que sirve la app** (los del robot de demo): responden a clientes de ROS 2 con
  Fast DDS (``rmw_fastrtps_cpp``), emparejando cada respuesta con su petición como hace
  rmw_fastrtps; los clientes con Cyclone DDS (``rmw_cyclonedds_cpp``) no reciben respuesta. Llamar
  a los servicios de un robot desde la app funciona con los dos.

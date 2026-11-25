Solución de problemas
=====================

El robot no aparece
-------------------

Inicio dice *Solo se ve este dispositivo*:

1. **¿Mismo dominio?** Ajustes → Red → Domain ID tiene que coincidir con el ``ROS_DOMAIN_ID`` del
   robot. **Buscar robots**, a su lado, escucha en todos los dominios y muestra dónde hay nodos.
2. **¿Misma red?** El móvil tiene que estar en la Wi-Fi del robot (no en datos móviles ni en una
   red de invitados aislada).
3. **¿Multicast bloqueado?** Muy habitual en Wi-Fi de oficinas, universidades, invitados y mesh.
   Escribe la IP del robot en **Discovery peers** y pulsa **Aplicar y reconectar**.
4. Usa **Comprobar red**: muestra la IP del móvil, los peers en uso y cada participante que ve.
5. ¿Nada? Pide que desactiven el *IGMP snooping* y el *aislamiento de clientes/AP* en el router, o
   usa un **Discovery Server** en los dos lados.

En el PC
~~~~~~~~

En el PC no hay Inicio: el estado de la barra lateral pone *Dominio N* cuando el nodo está en
marcha, y una pestaña **Nodos** o **Topics** muestra lo que ve.

1. **¿Mismo dominio?** Configuración → Red → Domain ID tiene que coincidir con el
   ``ROS_DOMAIN_ID`` del robot, o usa **Buscar robots**. La primera vez, la app toma
   ``ROS_DOMAIN_ID``, ``ROS_STATIC_PEERS`` y ``ROS_DISCOVERY_SERVER`` de su entorno (la terminal
   desde la que se arranca o, en Windows, las variables de entorno de tu usuario); después solo
   cuenta lo que pongas en Configuración.
2. **¿Cortafuegos?** DDS usa multicast UDP y puertos a partir del 7400. En Linux, si tienes ``ufw``
   u otro cortafuegos activo, permite la red del robot. En Windows, permite VizMini2 cuando el
   Firewall de Windows lo pregunte; si lo cerraste, abre *Seguridad de Windows* → *Firewall y
   protección de red* → *Permitir una aplicación a través del firewall* y marca *VizMini2* para las
   redes *Privadas*. Si tu Wi-Fi está marcada como **Pública**, márcala como **Privada**
   (*Configuración* → *Red e Internet*). También puedes añadir la IP del robot en **Discovery
   peers**.
3. **¿Varias tarjetas de red** (Docker, VPN…)? Escribe la IP o la interfaz correcta en
   **Interfaces de red permitidas**.
4. Compruébalo desde una terminal, sin ventana: ``vizmini2 --check --domain N``. Con el paquete de
   ROS 2 es ``ros2 run vizmini2 vizmini2 --check --domain N``; con el ``.deb``,
   ``/opt/vizmini2/bin/vizmini2 --check --domain N``. Se conecta unos segundos y escribe su estado,
   los nodos y los topics que ve. En Windows, ``VizMini2.exe --check`` no escribe nada en la
   terminal: redirígelo a un fichero como se explica en :doc:`../getting-started/desktop` (*Línea
   de comandos*).

Aparecen los topics pero no llegan datos
----------------------------------------

* **QoS:** una suscripción *Reliable* no recibe de un publicador *Best effort*. En los displays de
  RViz y en Echo, elige *Best effort*. Para topics "latched" como ``/map`` o
  ``/robot_description``, usa *Transient local*.
* **Tipo desconocido:** la app trae unas 470 interfaces comunes (:doc:`requirements`); define las
  tuyas en la pestaña Botones (:doc:`../tabs/buttons`).
* **Mensajes grandes por Wi-Fi:** las nubes de puntos y las imágenes sin comprimir pesan mucho.
  Mejor imágenes comprimidas y nubes reducidas.
* **ROS 2 en un contenedor Docker en el mismo PC** (Kilted o posterior, ejecutado como *root*,
  como es habitual): los topics aparecen pero no llegan datos, porque Fast DDS los manda por memoria
  compartida y la app, que se ejecuta con tu usuario, no puede abrirla. Arranca el contenedor con
  ``-e FASTDDS_BUILTIN_TRANSPORTS=UDPv4`` (o con tu usuario: ``--user $(id -u):$(id -g)``).
* **Topics de vídeo en el PC** (H.264/H.265, como ``foxglove_msgs/CompressedVideo``): necesitan
  ``ffmpeg``: ``sudo apt install ffmpeg`` en Linux, ``winget install ffmpeg`` en Windows. Sin él,
  la cámara lo dice.
* **Las nubes o las imágenes se paran después de usar** ``ros2 topic echo`` **o** ``ros2 topic hz``
  **en el robot (ROS 2 Lyrical):** un fallo del ``rmw_fastrtps_cpp`` de Lyrical hace que un
  publicador deje de enviar los topics con arrays de bytes (``sensor_msgs/PointCloud2``, ``Image``,
  ``CompressedImage``…) a los lectores que no son de ROS, como la app (en el móvil y en el PC), en
  cuanto se cierra otro suscriptor de ROS de ese topic. Reinicia el nodo que publica el topic y
  vuelven los datos. Hasta entonces, evita ``echo``/``hz`` sobre esos topics mientras usas la app.

Los servicios o las acciones no responden
-----------------------------------------

* El robot tiene que usar **Fast DDS** (``rmw_fastrtps_cpp``) o **Cyclone DDS**
  (``rmw_cyclonedds_cpp``): la app los distingue sola. Con **Zenoh** (``rmw_zenoh_cpp``), rellena el
  **Router de Zenoh** en :doc:`Ajustes → Red <../config/network>`. Con cualquier otro middleware, usa
  foxglove_bridge. Si aun así no responden, prueba el ajuste **Formato de servicios** en Ajustes →
  Red.
* La app espera hasta el tiempo límite del botón (5 s por defecto). Los servicios largos necesitan
  más tiempo.

RViz muestra el robot sin mallas
--------------------------------

Configura el servidor de mallas: :doc:`../tutorials/visualize-robot`. En el PC, las mallas de los
paquetes instalados en el mismo ordenador se encuentran sin él.

El robot sigue moviéndose
-------------------------

No debería: al soltar el joystick se envía velocidad cero, y STOP, perder la conexión o una
pantalla congelada también lo paran. Si tu robot sigue con la última orden, configura en el robot
un tiempo límite de velocidad (casi todos los controladores lo tienen).

Solo en el PC
-------------

El dock muestra un icono genérico o un nombre de Java (Linux)
   El ``.deb`` añade *VizMini2* al menú de aplicaciones con su icono. Con ``ros2 run``, la app añade
   esa entrada del menú (en ``~/.local/share/applications``) la primera vez que arranca; si el dock
   sigue mostrando el icono genérico, cierra la app y vuelve a abrirla.

Wayland (Linux)
   La app funciona a través de XWayland, que Ubuntu trae por defecto. Si la ventana no se abre en
   una sesión Wayland, comprueba que XWayland está instalado o entra con una sesión X11 (Xorg).

Windows dice "Windows protegió su PC" al instalar
   El ``.msi`` no está firmado: elige *Más información* → *Ejecutar de todas formas*.

No llegan notificaciones
   En Linux, las alertas usan ``notify-send``: instálalo con ``sudo apt install libnotify-bin``. En
   Windows salen del icono de VizMini2 en el área de notificaciones: comprueba que las
   notificaciones de Windows están activadas para él. Comprueba también que **Notificaciones en
   este PC** está activado en la pestaña Alertas (:doc:`../tabs/alerts`).

Grabar vídeo no funciona
   Grabar la ventana necesita ``ffmpeg``: ``sudo apt install ffmpeg`` en Linux, ``winget install
   ffmpeg`` en Windows (la app también encuentra el que instala winget aunque ya estuviera abierta).
   O pon en la variable de entorno ``VIZMINI2_FFMPEG`` la ruta del programa ``ffmpeg``.

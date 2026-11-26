Arquitectura
============

Un solo código, dos apps: casi todo lo que ve el usuario se comparte entre Android y el escritorio.

.. list-table::
   :header-rows: 1
   :widths: 22 78

   * - Carpeta
     - Qué es
   * - ``core/``
     - Lógica compartida en Kotlin/JVM puro, con tests unitarios: la conexión ROS y sus
       middlewares, el códec de mensajes dinámicos, los modelos de cada pestaña (gráficas,
       alertas, controladores, mapas, MoveIt, Nav2…), los bags, los perfiles y los textos
       (``i18n``).
   * - ``ui/``
     - Pantallas Compose compartidas (la mayoría de las pestañas, el catálogo, los diálogos,
       importar y exportar), compiladas en las dos apps.
   * - ``rviz3d/``
     - La vista 3D sobre Filament: displays, herramientas, interactive markers, mallas.
   * - ``app/``
     - La app Android: actividades y fragments alrededor de las pantallas compartidas, la conexión
       en segundo plano, el modo demo, las notificaciones, la grabación de pantalla y Firebase
       (cuenta, informes de fallos).
   * - ``desktopApp/``
     - La app de escritorio (Compose for Desktop): la ventana y la barra lateral, los diálogos de
       ficheros, la decodificación de vídeo y la grabación con ffmpeg, las notificaciones de
       escritorio (``notify-send`` o el área de notificaciones), el ``.deb`` y el ``.msi``, y los
       tests de extremo a extremo.
   * - ``package.xml``, ``CMakeLists.txt``, ``scripts/``
     - El repositorio también es un paquete de ROS 2: ``colcon build`` ejecuta Gradle (con el JDK
       del sistema) e instala los jar de la app, y ``ros2 run vizmini2 vizmini2`` los arranca con la
       Java del sistema.
   * - ``tools/``
     - Servidores de prueba (robot falso, Nav2, MoveIt con el Panda, ros2_control, vídeo,
       mapas…), scripts de la CI y de las versiones, la compilación en Ubuntu 20.04 de las
       librerías nativas de escritorio, las nativas de Windows (``tools/desktop-natives``) y la
       lista de binarios precompilados (``tools/prebuilt``).
   * - ``docs/``
     - Este manual (Sphinx, inglés y español).

Cómo habla con ROS
------------------

* **DDS** con **Fast DDS** (a través de jros2), compilado para Android (arm64, x86-64…), Linux y
  Windows. La app es un participante DDS como cualquier nodo de ROS 2: sin puente ni agente en el
  robot. Los robots con Cyclone DDS interoperan por la red.
* **Los mensajes son dinámicos**: las definiciones de interfaces
  (``app/src/main/assets/ros_interfaces.txt``, generadas por ``tools/gen_ros_interfaces.py``) se
  leen en tiempo de ejecución y se codifican y decodifican con un códec CDR, así que cualquier
  tipo incluido se puede usar sin clases generadas. Cuando un mensaje cambia entre distribuciones
  (MoveIt, ros2_control), la app lleva las definiciones de cada versión y detecta cuál usa el
  robot.
* **Zenoh** (las key expressions y attachments de ``rmw_zenoh``) y **foxglove_bridge** (su
  protocolo WebSocket) son transportes alternativos detrás de la misma interfaz, para redes donde
  el descubrimiento DDS no funciona.

La vista 3D
-----------

Filament dibuja la escena en las dos plataformas (OpenGL ES en Android, OpenGL en escritorio). En el
escritorio dibuja fuera de pantalla y el fotograma se muestra dentro de Compose. TF es un árbol
indexado por tiempo; los displays se suscriben por la misma conexión que el resto de la app.

Librerías nativas de escritorio
-------------------------------

Las librerías nativas de la app de escritorio (Fast DDS, el JNI de Filament y un pequeño ayudante de
X11) se compilan para Linux dentro de un Ubuntu 20.04 en un espacio de nombres de usuario
(``tools/compat``), así que necesitan como mucho glibc 2.31; Fast DDS enlaza libstdc++ de forma
estática y oculta, para que no choque con la de la JVM. Las de Windows las compila con MSVC el
workflow *Windows natives* (``tools/desktop-natives``). El entorno de Java va incluido (jlink), así
que ni el ``.deb`` ni el ``.msi`` necesitan Java ni ROS.

Las nativas de Fast DDS y Filament y jros2 para Android no están en git: viven en la release
*prebuilt* del repositorio y la compilación las descarga (``tools/prebuilt``). Cada paquete lleva
solo las nativas de su sistema: el ``.deb``, el ``.tar.gz`` y el paquete de ROS las de Linux; el
``.msi`` y el ``.zip`` las de Windows.

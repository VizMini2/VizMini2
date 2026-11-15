RViz
====

.. clip:: rviz

Una vista 3D de tu robot, como RViz: TF, mapas, láseres, nubes de puntos, cámaras, markers, rutas,
poses, el modelo del robot y las trayectorias planificadas de MoveIt, con las herramientas de Nav2
(meta, pose inicial y rutas de waypoints) y un objetivo de MoveIt que se mueve arrastrando. El móvil
y el PC dibujan la misma escena con los mismos displays.

Al crearla
----------

* **Fixed frame:** déjalo vacío para que se elija solo (``map``, ``odom``… o la raíz del árbol TF).
* **Añadir displays de mapa, láser, modelo del robot y trayectoria** (activado por defecto): añade
  *Map*, *RobotModel*, *LaserScan* y un *Path* llamado *Plan* en ``/plan`` al *Grid* y la *TF* con
  los que empieza toda vista, listo para un robot típico con Nav2.

Displays
--------

En el móvil, los displays aparecen como chips abajo, con un punto que indica su estado. Toca un chip
para activar o desactivar el display y mantenlo pulsado para editarlo o quitarlo. Pulsa **Añadir**
para añadir uno *por tipo de display* o *por topic* (la búsqueda muestra todos los topics de la red
que se pueden dibujar).

Cada display tiene su propia QoS: *Best effort*, *Reliable* o *Transient local*, y además puedes
cambiar la profundidad, el historial, la fiabilidad y la durabilidad una a una, como en RViz 2. Si un
display no recibe nada, su diálogo dice por qué y, si puede, propone un arreglo con un botón (otro
topic del mismo tipo, o la QoS que usa el publicador).

En el PC, los displays están en un panel a la derecha, como en RViz: *Global Options* (fixed frame y
fondo), *Global Status* y cada display con su casilla y sus propiedades, que se editan ahí mismo. Con
el botón derecho sobre un display tienes *Duplicate*, *Rename* y *Remove* (también abajo del panel,
junto a *Add*). Arrastra el borde izquierdo del panel para ensancharlo; el botón de arriba a la
derecha lo oculta. En una ventana estrecha o en un widget de dashboard se ven los chips en su lugar:
clic para activar o desactivar un display, botón derecho para editarlo.

.. list-table::
   :header-rows: 1
   :widths: 22 78

   * - Display
     - Opciones principales
   * - Grid
     - Frame de referencia, plano (XY, XZ, YZ), tamaño y número de celdas, color, transparencia,
       estilo y grosor de línea, desplazamiento.
   * - TF
     - Mostrar nombres, ejes y flechas; escala, tamaño del nombre, tiempo hasta dar un frame por
       perdido. Los topics de TF (``/tf, /tf_static`` o los de un robot, como ``/robot1/tf``) y qué
       frames ocultar: una casilla por frame, o patrones como ``*wheel*``.
   * - Axes
     - Frame, longitud, radio, transparencia.
   * - RobotModel
     - Desde ``/robot_description`` (transient local). Geometría visual y de colisión,
       transparencia y un **servidor de mallas** opcional (ver abajo).
   * - Map
     - ``nav_msgs/OccupancyGrid`` con colores *map*, *costmap* o *raw*, transparencia y *dibujar
       detrás*. Los mapas grandes se reducen para caber en la GPU.
   * - LaserScan, PointCloud2, PointCloud
     - Cuadrados o círculos, tamaño, color por intensidad, distancia, x/y/z, RGB o fijo, límites de
       valores, tiempo de persistencia (hasta 30 s). PointCloud2 muestra hasta 500.000 puntos.
   * - Odometry
     - Flechas o ejes, cuántas guardar, tolerancia de posición y de ángulo.
   * - Path
     - Color, estilo y grosor de línea, poses como ejes o flechas.
   * - Pose, PoseWithCovariance, PoseArray
     - Flechas o ejes; elipse de covarianza en ``PoseWithCovariance`` (p. ej. ``/amcl_pose``).
   * - PointStamped, Polygon, Range
     - Puntos pulsados, huellas (footprints) y conos de sensores de distancia.
   * - Marker, MarkerArray
     - Flechas, cubos, esferas, cilindros, líneas, listas de cubos y esferas, puntos, triángulos y
       texto; se respetan el borrado, el borrado total y el tiempo de vida. Los markers de malla
       (``MESH_RESOURCE``, STL o Collada) se descargan del **servidor de mallas** del display, como
       en RobotModel.
   * - Image
     - La imagen de una cámara en un panel sobre la vista 3D: ``sensor_msgs/Image``,
       ``CompressedImage`` (topics que acaban en ``/compressed``) o vídeo H.264/H.265
       (``…/foxglove``, ``…/ffmpeg``, mira :doc:`camera`). Esquina, ancho y transparencia. Varios
       paneles en la misma esquina se apilan; arrastra un panel para ponerlo donde quieras y arrastra
       su esquina inferior derecha (o pellízcalo, en el móvil) para cambiar su tamaño.
   * - Camera
     - La imagen de la cámara con la escena 3D dibujada encima, vista desde la propia cámara: la
       rejilla, los markers, las nubes, el robot… coinciden con lo que ve la cámara. Usa el
       ``camera_info`` de al lado de la imagen (``/camera/image_raw`` → ``/camera/camera_info``, u
       otro) para la proyección y la TF de su frame para la pose. Esquina y ancho; el panel se mueve
       y se cambia de tamaño como el de Image. **Image Rendering**: imagen y escena, la imagen
       encima de la escena (con *Overlay Alpha*), solo la imagen o solo la escena.
   * - DepthCloud
     - Una imagen de profundidad (``16UC1`` en mm o ``32FC1`` en m) y su ``camera_info`` convertidas
       en nube de puntos, coloreada por distancia o con los colores de otra imagen (*Color Image
       Topic*). Alcance máximo, número máximo de puntos, tamaño y estilo. También valen los topics
       ``…/compressedDepth`` (PNG, de compressed_depth_image_transport).
   * - Effort
     - El esfuerzo de cada articulación de ``sensor_msgs/JointState`` como un arco alrededor de su
       eje (del URDF de ``/robot_description``): cuanto más cerca del límite de esfuerzo, más largo y
       más rojo; la flecha indica el sentido. Radio y ancho.
   * - GridCells
     - Las casillas de ``nav_msgs/GridCells`` (obstáculos, zonas infladas…): color y transparencia.
   * - Wrench, TwistStamped, AccelStamped
     - Dos flechas en el frame del mensaje: fuerza y par, o lineal y angular. La parte angular se
       dibuja a lo largo de su eje (regla de la mano derecha). Colores, escala de cada flecha y
       grosor.
   * - Temperature, Illuminance, FluidPressure, RelativeHumidity
     - Una esfera en el frame del sensor, del azul (mínimo) al rojo (máximo), con el valor al lado.
   * - Trajectory
     - La trayectoria planificada de MoveIt (``moveit_msgs/DisplayTrajectory`` en
       ``/display_planned_path``) recorrida por un robot fantasma translúcido, hecho a partir del URDF
       y colocado donde está el robot real. Color, transparencia, bucle y velocidad; también puede
       enseñar el objetivo del panel :doc:`moveit`.
   * - PlanningScene
     - Los objetos de colisión de MoveIt (``/monitored_planning_scene``): cajas, esferas, cilindros,
       conos y mallas, también los que lleva enganchados el robot. Color y transparencia.
   * - InteractiveMarkers
     - Los controles de un servidor de interactive markers (``<namespace>/update``): sus markers o,
       si un control no trae ninguno, las flechas y anillos de RViz. Se mueven con la herramienta
       **Interactuar**.

Cada mensaje se coloca con la TF del instante de su stamp, con los últimos 10 s de historial. Si el
reloj de las TF va hacia atrás (una bag en bucle o una simulación que se reinicia con ``/clock``), el
historial se vacía, igual que en RViz, y también se borran los puntos acumulados por la persistencia,
la estela de *Odometry* y los puntos de *PointStamped*.

Vistas y gestos
---------------

* Vistas: **Orbit**, **XYOrbit**, **TopDownOrtho**, **FPS** y **ThirdPersonFollower** (sigue un
  frame, ``base_link`` por defecto). *Restablecer vista* vuelve a colocar la cámara.
* **Vistas guardadas** (en el menú; en el PC, abajo del panel de displays): guarda la cámara actual
  con un nombre y vuelve a ella con un toque.
* Un dedo gira, dos dedos desplazan, pellizcar hace zoom y girar con dos dedos rota.
* El fondo puede ser oscuro, gris de RViz, negro, gris claro, blanco o azul noche.

En el PC, arrastra con el botón izquierdo para girar, con el derecho o el central (o
:kbd:`Mayús` + izquierdo) para desplazar, y usa la rueda para hacer zoom.

Herramientas
------------

* **Goal** publica una meta 2D (``geometry_msgs/PoseStamped``) en ``/goal_pose``.
* **Pose** publica una estimación de pose 2D (``PoseWithCovarianceStamped``) en ``/initialpose``.

Toca el suelo y arrastra para elegir la orientación. La pose se envía en el fixed frame (o en
``map``).

.. clip:: rviz_select
   :poster: rviz
   :caption: Elegir: toca un punto del láser para ver dónde está.

En el móvil, el botón de la regla tiene el resto de herramientas; en el PC están todas en la barra:

* **Publicar punto** envía el punto del suelo que toques (``geometry_msgs/PointStamped``) a
  ``/clicked_point``, como RViz. Puedes arrastrar antes de levantar el dedo para ajustarlo.
* **Medir**: arrastra por el suelo de un punto a otro para ver la distancia y cuánto cambia en x
  y en y. La medida se queda hasta la siguiente o hasta que vuelvas a tocar la regla.
* **Elegir** funciona con nubes de puntos y láseres: toca un punto para ver dónde está, en el fixed
  frame y en el frame del sensor, y a qué distancia del sensor.
* **Interactuar** funciona con los displays *InteractiveMarkers*: arrastra las flechas para mover
  a lo largo de un eje, un anillo para girar o para mover en un plano, y toca botones y menús. El
  servidor recibe el mismo feedback que desde RViz (``<namespace>/feedback``). Si tocas fuera de
  un control, la vista se mueve como siempre; la herramienta sigue activa hasta que vuelvas a tocar
  la regla.
* **Waypoints** monta una ruta de Nav2: toca el suelo y arrastra para elegir la orientación de cada
  waypoint. La herramienta sigue activa para seguir añadiendo.
* **Objetivo de MoveIt** pone flechas y anillos en el objetivo de la pinza de un panel
  :doc:`moveit` abierto (pestaña o widget): arrastra las flechas para moverlo y los anillos para
  girarlo, y luego *Planificar* en el panel. Si MoveIt no encuentra articulaciones para esa posición,
  te lo dice.

Puedes cambiar los topics de Goal, Pose y Publicar punto desde el menú, en *Topics de las
herramientas…*. Mira :doc:`../tutorials/nav2`.

Barra de Nav2
-------------

Con waypoints en el mapa, una barra arriba dice cuántos hay y cómo mandarlos: **Parar en cada uno**
(``FollowWaypoints`` de Nav2, si quieres **En bucle**) o **Pasar por ellos** sin parar
(``NavigateThroughPoses``). Los botones quitan el último waypoint o todos, e **Ir** manda la ruta
(**Cancelar** la para). Mientras va, la barra enseña el waypoint actual, la distancia y el tiempo que
quedan y las recuperaciones. La ruta se guarda con la pestaña.

La barra también enseña el progreso de cualquier objetivo de ``navigate_to_pose``, como uno mandado
con la herramienta **Goal**.

Volver atrás
------------

El botón **Volver atrás** (el reloj con una flecha) repite el último minuto de esta vista (los topics
de sus displays y la TF) sin haber grabado una bag: solo en este dispositivo, en bucle, con la barra
de tiempo de siempre para pausar, cambiar la velocidad o saltar. Al cerrar la barra se vuelve al
robot en directo. En el PC la vista guarda ese minuto desde el principio; en el móvil se activa con
el primer toque (o con *Guardar el último minuto* en el menú), porque gasta memoria.

Importar y exportar .rviz
-------------------------

Desde el menú, **Importar archivo .rviz…** trae los displays (todos los tipos de la tabla, grupos
incluidos; el *MotionPlanning* de MoveIt pasa a ser un *Trajectory*), el fixed frame, el fondo, la
vista actual y los topics de meta, pose y punto de una configuración del RViz de escritorio. Lo que
no pueda usar aparece como *No compatible aquí*. Importar sustituye la configuración actual de la
pestaña. **Exportar .rviz…** guarda la vista en un archivo que se abre con ``rviz2 -d``. Mira
:doc:`../config/import-export` y :doc:`../tutorials/visualize-robot`.

Anotaciones sobre la imagen
---------------------------

Image y Camera tienen un **Annotations Topic**: cajas, círculos, líneas y textos dibujados sobre la
imagen, de ``visualization_msgs/ImageMarker`` (se acumulan por namespace e id, con su tiempo de
vida, como en image_view) o de ``foxglove_msgs/ImageAnnotations`` de Foxglove (cada mensaje sustituye
al anterior). El tipo se averigua solo en la red. Las coordenadas son píxeles de la imagen. En un
panel de Image, el botón del ojo (o un doble toque) las enseña u oculta; en Camera, la opción *Show
Annotations*.

Mallas del robot
----------------

Las cajas, cilindros y esferas del URDF se dibujan directamente. Las mallas (STL o Collada
``.dae``) se descargan por HTTP desde el **servidor de mallas** indicado en el display RobotModel,
por ejemplo ``http://IP_DEL_ROBOT:8000``: ``package://paquete/ruta`` se convierte en
``<servidor>/paquete/ruta``. Sirve cualquier servidor web que ofrezca las carpetas ``share`` de tus
paquetes: mira :doc:`../tutorials/visualize-robot`. Sin servidor de mallas, las piezas con mallas
no se dibujan y el display indica cuántas.

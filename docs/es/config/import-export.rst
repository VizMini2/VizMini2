Importar y exportar
===================

VizMini2 lee y escribe los ficheros de configuración de las herramientas habituales de ROS, así que
puedes traer tus vistas y volver a llevártelas.

**Importar:** **+** (*Añadir* en el PC) → *Importar una pestaña, un fichero de RViz o un layout…*.
El formato se reconoce por su contenido:

* **RViz 2** (``.rviz``): una pestaña RViz nueva con sus displays (33 tipos, de Grid y TF a
  MarkerArray, Camera, Trajectory y PlanningScene), el frame fijo, el fondo, la vista y los topics de
  las herramientas. Desde una pestaña RViz, su menú también importa en esa pestaña.
* **Layouts de paneles** (``.json``): los que exportan las herramientas web de visualización de ROS,
  y los que generan sus SDK. Se convierten en un dashboard con un widget por panel, colocados como en
  el layout. 3D → RViz (sus topics visibles como displays), Plot y State Transitions → Gráficas
  (rutas de mensajes, XY, ``@derivative``…), Image → Cámara, Raw Messages → Mensaje, Teleop, Gauge e
  Indicator → Indicadores, Publish → un botón con el mismo mensaje, Map → GPS, Diagnósticos, Log,
  Parámetros, Topic Graph y Transform Tree. Un panel Tab trae todas sus pestañas: la abierta en el
  dashboard y cada una de las demás como un dashboard propio (*layout · pestaña*).
* **Layouts de gráficas** (``.xml``): sus gráficas, las XY y las **series personalizadas en Lua**
  (la misma ``function(time, value, v1…)`` que aquí) como pestañas de Gráficas, o un dashboard con
  ellas.
* Las pestañas compartidas de VizMini2 (``.vizmini2.json``).

Lo que no tiene equivalente se lista antes de añadir, para que no se pierda nada sin avisar.

**Exportar:** en el menú de una pestaña (pulsación larga en el móvil, clic derecho en la barra
lateral en el PC), *Exportar como…*, según lo que tenga la pestaña: una pestaña RViz como fichero de
RViz 2 (``.rviz``); cualquier pestaña o dashboard como layout de paneles (``.json``); las pestañas de
Gráficas (o las de un dashboard) como layout de gráficas (``.xml``), que al abrirlo se suscribe a los
topics correctos.

.. note::
   Los layouts de gráficas no dicen el tipo de mensaje de cada topic, y sus nombres de curva juntan
   topic y campo (``/odom/pose/pose/position/x``). VizMini2 usa los topics a los que se suscribe el
   layout y los que hay en la red para separarlos; abre el fichero con el robot en la red, o revisa
   los avisos tras importar.

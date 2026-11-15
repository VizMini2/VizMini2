Mapas
=====

.. clip:: maps

Guarda el mapa, limpia los costmaps y dibuja zonas para Nav2, sobre el mapa del robot.

* El mapa es cualquier topic ``nav_msgs/OccupancyGrid`` (``/map`` por defecto), con zoom (pellizco,
  la rueda o los botones) y desplazamiento.
* **Descargar PGM + YAML** guarda el mapa como lo haría ``map_saver`` (en el móvil, se comparte con
  Archivos, Drive, el correo...). **Guardar en el robot** llama al ``map_saver`` de Nav2; con SLAM
  Toolbox también hay botones de *guardar mapa*, *guardar grafo de poses* y *pausar / reanudar*. El
  nombre es una ruta en el robot sin extensión.
* **Costmaps**: limpia el costmap global o el local (o los dos) cuando se quedan con obstáculos
  viejos.
* **Zonas**: dibuja una zona *prohibida* (el robot no entra) o de *límite de velocidad* (en
  porcentaje de la velocidad máxima) tocando sus esquinas, y **Publícala** como máscara de un filtro
  de costmap de Nav2 (``/keepout_filter_mask``, ``/speed_filter_mask``), si quieres con su
  información de filtro (``/costmap_filter_info``), o descarga la máscara en PGM + YAML para
  ``map_server``.

El costmap necesita el plugin *KeepoutFilter* o *SpeedFilter* leyendo ese topic de información del
filtro. Las máscaras se quedan publicadas, así que un costmap que arranque después también las recibe.

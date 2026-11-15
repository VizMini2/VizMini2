Diagnóstico
===========

.. clip:: diagnostics

Cuatro vistas en una pestaña; elige con cuál empezar al crearla y cambia de una a otra arriba. Todas
tienen *Pausar*, *Reanudar* y *Borrar*, y una línea de estado con lo que ha llegado.

Log
   ``/rosout`` con un nivel mínimo (*DEBUG*, *INFO*, *WARN*, *ERROR*, *FATAL*; *INFO* y superiores
   por defecto), un filtro por nodo o texto, y las últimas 2000 líneas. El botón de **nodos** deja
   solo los que elijas. Toca una línea para verla entera, con su hora exacta, el fichero, la línea y
   la función; desde ahí se **copia** o, en el móvil, se **comparte el log** (lo que dejan los
   filtros) como texto.

Diagnostics
   ``/diagnostics`` como ``rqt_robot_monitor``: primero el peor nivel, con un recuento por nivel.
   Las entradas que pasan 10 segundos sin novedades pasan a *STALE*. Pulsa una entrada para
   desplegar sus valores y su ID de hardware.

Echo
   Los mensajes en directo de cualquier topic. Escribe el topic y pulsa ✓ (en los topics conocidos
   se rellena el tipo), elige el tipo con **Tipo…** de una lista de tipos comunes y la QoS con
   **QoS**: *Best effort* (por defecto) recibe de cualquier publicador, *Reliable*, o *Transient
   local* para recibir el último mensaje que guarda el publicador (por ejemplo ``/map``). Para otros
   tipos, usa :doc:`message` o :doc:`topics`.

Plot
   Hasta cuatro campos numéricos de un topic, elegidos con **Campos**, durante los últimos 30
   segundos. Los mensajes sin números (mapas, imágenes, nubes de puntos) no se pueden dibujar. Para
   más, usa una pestaña :doc:`plot`.

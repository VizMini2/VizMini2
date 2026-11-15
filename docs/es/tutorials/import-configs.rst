Traer tus configuraciones de RViz y tus layouts
===============================================

Objetivo: aprovechar las configuraciones que ya tienes en vez de montar las pestañas otra vez, y
llevar las pestañas de VizMini2 de vuelta a esas herramientas.

.. shot:: phone_add_tab
   :caption: Importar es el primer botón de la pantalla "+".

Desde RViz 2
------------

1. Copia tu fichero ``.rviz`` al móvil (o déjalo en el PC).
2. **+** (*Añadir* en el PC) → *Importar una pestaña, un fichero de RViz o un layout…* y elígelo.
3. Aparece una pestaña RViz nueva con los mismos displays, topics, fixed frame, fondo y vista. Para
   sustituir los displays de una pestaña que ya tienes, usa *Importar archivo .rviz…* en el menú de
   esa pestaña RViz.

Desde un layout de paneles
--------------------------

1. Exporta el layout desde tu herramienta de visualización (un ``.json``).
2. Impórtalo igual: sale un dashboard con un widget por panel, colocados como en el layout. Los
   paneles 3D pasan a widgets RViz, las gráficas a widgets de Gráficas (con sus rutas de mensaje,
   gráficas XY y ``@derivative``), las imágenes a widgets de Cámara, los paneles Publish a botones,
   etc. Si el layout tiene un panel Tab, también llegan sus demás pestañas, cada una como un
   dashboard propio.

Desde un layout de gráficas
---------------------------

1. Guarda el layout desde tu herramienta de gráficas (un ``.xml``).
2. Impórtalo con el robot en la red (los layouts de gráficas no guardan los tipos de mensaje;
   VizMini2 los busca ahí). Sus gráficas, gráficas XY y series Lua personalizadas entran como
   pestañas de Gráficas, o como un dashboard de gráficas si hay varias pestañas.

Revisa las notas
----------------

Cuando algo no tiene equivalente (un panel que VizMini2 no tiene, una opción que no usa), un
diálogo lo lista antes de añadir las pestañas: pulsa **Añadir** para seguir. Nada se pierde sin
avisar.

Llevar las pestañas de vuelta
-----------------------------

Mantén pulsada una pestaña (clic derecho en la barra lateral del PC) → **Exportar como…**, según lo
que tenga: una pestaña RViz como fichero de RViz 2, que se abre con ``rviz2 -d fichero.rviz``; un
dashboard como layout de paneles; y las gráficas como layout de gráficas que se suscribe a los
topics correctos. Los formatos se explican en :doc:`../config/import-export`.

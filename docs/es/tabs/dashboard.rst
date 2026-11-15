Dashboard
=========

.. clip:: dashboard

Tu propia pantalla, hecha con otras pestañas como widgets: una vista 3D, varias cámaras, un
joystick, botones, diagnósticos… todo a la vez. El móvil y el PC usan el mismo dashboard.

* Pulsa **Editar**, luego **Widget**, y elige cualquier tipo de pestaña (menos otro dashboard).
  Pulsa **Hecho** para volver a usar los widgets.
* Los widgets se quedan justo donde los sueltas, y pueden solaparse: el último que has movido queda
  encima, y un widget que tapa a otro se dibuja translúcido y con un borde de color (un joystick sobre
  la vista 3D, una cámara pequeña en una esquina…). El tablero mide 12 celdas de ancho con el zoom
  al 100 % y crece hacia la derecha y hacia abajo según colocas widgets. El widget más pequeño es de
  3 × 2 celdas.
* En modo edición, arrastra un widget para moverlo, arrastra el botón redondo de su esquina inferior
  derecha para cambiar su tamaño, o pulsa la papelera roja para quitarlo. **Deshacer** (la flecha de
  la barra) revierte el último movimiento, cambio de tamaño o borrado; un widget quitado vuelve con
  sus ajustes.
* En el móvil, en modo edición, los widgets de Botones, Sliders e Indicadores muestran además un
  **+** para añadirles un botón, un slider o un indicador (dentro de un dashboard no llevan su barra
  de "Añadir"). Uno vacío también añade al tocarlo.
* **Zoom**: pellizca con dos dedos para acercar o alejar el tablero y arrastra con dos dedos para
  moverte por él (los gestos de dos dedos sobre un joystick son del joystick). El porcentaje de la
  barra lo devuelve al 100 %. El zoom se recuerda para cada dashboard en cada dispositivo.
* El botón de pausa de la barra congela todos los widgets a la vez (los mensajes siguen llegando y
  al reanudar se ve el último). Mientras se reproduce una bag desde la pestaña :doc:`bags`, su barra
  de tiempo aparece aquí, y pausar, cambiar la velocidad o saltar mueve todos los widgets a la vez.

Cada widget guarda sus propios ajustes, como una pestaña normal.

En el PC, haz zoom con :kbd:`Ctrl` + rueda y usa :kbd:`Ctrl+0` para volver al 100 %, :kbd:`Ctrl+E`
para entrar o salir del modo edición, :kbd:`Esc` para salir de él y :kbd:`Ctrl+Z` para deshacer. Con
el botón derecho sobre un widget tienes *Traer al frente*, *Editar* y *Quitar widget* sin entrar en
modo edición.

Arrastrar topics
----------------

Arrastra un topic desde un widget de Topics (en el móvil, mantenlo pulsado antes), o un campo de su
mensaje, y suéltalo en el dashboard:

* en un hueco libre se convierte ahí en un widget nuevo: una imagen en una Cámara, algo que dibuja
  RViz en una vista 3D, el diagnóstico en un widget de Diagnóstico y un número en una Gráfica;
* sobre un widget de RViz añade un display, y sobre uno de Gráficas una serie (si has soltado un topic
  entero, se abre el editor de la serie para elegir el campo).

Dashboards ya montados
----------------------

La pantalla **+** tiene *Dashboards ya montados*, que añaden un dashboard con sus widgets ya
colocados y configurados, con los topics que haya en la red si los hay: **Navegación** (3D con mapa,
láser, plan y footprint, joystick, velocidad y diagnóstico), **Robot con cámara**,
**Monitorización** (diagnóstico, gráficas, indicadores, grafo de nodos y árbol TF), **Manipulación
(MoveIt)** y **Showcase** (casi todo lo que sabe hacer la app, pensado para ``tools/fake_robot.py``).
En el móvil, el modo demo trae además un *Dashboard demo*.

Los layouts de paneles (``.json``) se pueden importar como dashboard, y un dashboard se puede
exportar como layout de paneles: mira :doc:`../config/import-export`.

.. container:: vm-clear

   Mira :doc:`../tutorials/dashboard`.

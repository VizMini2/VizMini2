Ver tu robot en 3D
==================

Objetivo: ver el modelo de tu robot, TF, láser y mapa en la pestaña RViz.

1. Conéctate al robot (:doc:`../getting-started/connect`).
2. Pulsa **+ → RViz**, deja marcado **Añadir displays de mapa, láser, modelo del robot y
   trayectoria** y pulsa **Crear**.
3. Si el robot publica ``/robot_description``, el display **RobotModel** lo dibuja. Si el modelo
   usa mallas (``package://mi_robot/meshes/base.stl``…), el móvil las descarga por HTTP: sirve la
   carpeta que contiene las carpetas ``share`` de tus paquetes. Por ejemplo, con un workspace
   compilado con ``colcon build --merge-install``, en el robot::

      cd ~/ros2_ws/install/share
      python3 -m http.server 8000

   Después escribe ``http://IP_DEL_ROBOT:8000`` en el campo **Mesh server** (servidor de mallas) del
   display RobotModel. El móvil pide ``http://IP_DEL_ROBOT:8000/mi_robot/meshes/base.stl``, así que
   comprueba que esa dirección se abre en un navegador. Solo se dibujan mallas STL y Collada
   (``.dae``). Los displays Marker y MarkerArray tienen el mismo campo para los markers
   ``MESH_RESOURCE``.

   **En el PC**, si los paquetes del robot están instalados o compilados en el mismo ordenador,
   deja el campo vacío: las mallas se leen de su instalación de ROS 2 y de los workspaces de
   ``AMENT_PREFIX_PATH`` (arranca la app desde una terminal con el workspace cargado).
4. ¿Falta algún display? Pulsa **Añadir → Por topic**: la lista muestra todos los topics que se
   pueden dibujar.
5. Elige el **fixed frame** en el selector de arriba (``map`` si navegas, ``odom`` si no).

En el PC, **Añadir** en la barra lateral es el **+**. En la vista 3D, arrastra con el botón
izquierdo del ratón para girar, con el derecho o el central para desplazar, y usa la rueda para
hacer zoom.

¿Ya tienes una configuración de RViz?
-------------------------------------

Copia tu fichero ``.rviz`` al móvil (en el PC, simplemente elígelo) y usa el menú → **Importar
archivo .rviz…**. Se traen los displays, el frame, el fondo, la vista y los topics de meta y pose;
lo que no sea compatible aparece en una lista.

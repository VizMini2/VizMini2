Controladores
=============

.. clip:: controllers

Los controladores de ros2_control de un robot, como ``ros2 control``: verlos, cambiarlos y mover sus
articulaciones.

* **Manager** elige el controller manager (los que hay en la red, o escribe su nombre; por defecto
  ``/controller_manager``).
* Cada controlador enseña su estado (*active*, *inactive*, *unconfigured*), su tipo y su frecuencia,
  y los botones que permite su estado: **Activar**, **Desactivar**, **Configurar** y **Descargar**.
  *Interfaces* lista las interfaces de comando y de estado que ocupa o necesita.
* Si activas un controlador que necesita las articulaciones de otro activo, te ofrece cambiar los
  dos de una vez (desactivar el otro y activar este).
* *Declarados pero sin cargar* lista los controladores de los parámetros del manager que aún no
  están cargados, con **Cargar**.
* **Mover articulaciones** (controladores de trayectorias y de comandos directos): un slider por
  articulación, con los límites del robot de ``robot_description`` y su posición actual de
  ``joint_states``; **Enviar**, o *Enviar mientras arrastro*. Las trayectorias tardan el tiempo que
  pongas.
* **Hardware** lista los componentes de hardware con su estado y sus interfaces, y los activa o
  desactiva.

Funciona con los controller managers de Foxy, Humble, Jazzy y Kilted/Lyrical (sus mensajes son
distintos: la versión se detecta sola, o se elige abajo). El robot de la demo también tiene uno: si
desactivas su ``diff_drive_controller``, se para.

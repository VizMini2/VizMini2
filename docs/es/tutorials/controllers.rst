Cambiar y mandar controladores de ros2_control
===============================================

Objetivo: ver los controladores de ros2_control de un robot, cambiarlos como con ``ros2 control
switch_controllers`` y mover sus articulaciones a mano. Con la demo del Panda de :doc:`moveit`
(funciona con ros2_control y hardware simulado) o cualquier robot con un ``controller_manager``.

.. shot:: pc_controllers
   :align: center
   :caption: La pestaña Controladores: cuatro controladores del Panda y las articulaciones del brazo desplegadas.

1. Arranca tu robot, o la demo del Panda:

   .. code-block:: bash

      ros2 launch moveit_resources_panda_moveit_config demo.launch.py

2. Añade una pestaña **Controladores** con **+**. Encuentra sola el controller manager
   (``/controller_manager``; **Manager** elige otro, por ejemplo bajo un namespace) y lista cada
   controlador con su tipo, su estado y su frecuencia. **Actualizar** los vuelve a leer.
3. Pulsa **Interfaces** en un controlador para ver qué interfaces de comando ocupa y qué
   interfaces de estado lee; la sección *Hardware* lista todas las interfaces y quién las tiene.
4. **Desactiva** ``panda_arm_controller`` y vuelve a **Activarlo**. Si dos controladores usan las
   mismas articulaciones, la app ofrece **Cambiar**: desactiva el viejo y activa el nuevo en un
   solo paso, como ``ros2 control switch_controllers``.
5. Pulsa **Mover articulaciones** en ``panda_arm_controller`` (un ``JointTrajectoryController``):
   un slider por articulación, empezando en la posición actual. Arrástralos y pulsa **Enviar**, o
   marca **Enviar mientras arrastro**; **Tiempo** fija cuánto dura el movimiento. **Actual** copia
   la posición del robot y **Cero** pone todos los sliders a cero. Los controladores de comando
   directo (posiciones, velocidades, esfuerzos) tienen los mismos sliders.
6. Los controladores declarados en los parámetros del manager pero sin cargar aparecen en
   *Declarados pero sin cargar*, con **Cargar**; uno inactivo se puede **Descargar**.

La versión de los mensajes de ``controller_manager_msgs`` (Foxy, Humble, Jazzy, Kilted) se detecta
sola. Más en :doc:`../tabs/controllers`.

.. warning::
   En un robot real, **Enviar** mueve las articulaciones de verdad: empieza con poca distancia y
   mucho **Tiempo**.

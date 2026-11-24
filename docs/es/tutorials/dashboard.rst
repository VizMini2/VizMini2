Crear un dashboard
==================

Objetivo: una pantalla con la vista 3D, una cámara y un joystick.

1. Pulsa **+ → Dashboard**, ponle nombre si quieres y pulsa **Crear**.
2. Pulsa **Editar → Widget → RViz**. Arrastra su esquina para que ocupe la mitad de arriba.
3. Añade un widget **Cámara** y elige el topic de imagen; colócalo debajo de la vista 3D.
4. Añade un widget **Teleop** al lado de la cámara.
5. Opcional: añade **Botones** con tus órdenes más usadas, o **Diagnóstico** en modo *Log*.
6. Pulsa **Hecho** y conduce mientras miras.

Los widgets se quedan justo donde los sueltas y se pueden solapar: el último que has movido queda
encima, así que puedes poner un joystick sobre la vista 3D o una cámara pequeña en una esquina.
**Deshacer** revierte el último movimiento. Guarda el resultado en un perfil de robot
(:doc:`../config/profiles`) para tenerlo en otro dispositivo.

¿Tienes prisa? El catálogo de **+** también tiene **Dashboards ya montados** (*Navegación*, *Robot
con cámara*, *Monitorización*, *Manipulación (MoveIt)*…) que usan los topics que encuentra en la red.

En el PC funciona igual: **Añadir** en la barra lateral es el **+**, :kbd:`Ctrl+E` entra y sale del
modo edición, :kbd:`Ctrl+Z` deshace y :kbd:`Ctrl+0` vuelve al zoom del 100 %.

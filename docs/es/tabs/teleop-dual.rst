Teleop (2 joysticks)
====================

.. clip:: teleop_dual

Dos joysticks, para robots holonómicos y drones. Funciona como :doc:`teleop` (mismos mensajes,
velocidades y paradas de seguridad) con modos para dos joysticks:

* **Avanzar / girar:** un joystick avanza y el otro gira (el de por defecto).
* **Holonómico + girar:** un joystick mueve en cualquier dirección y el otro gira.
* **Dron (modo 2):** subir/bajar y guiñada en el joystick izquierdo, avanzar y desplazarse de lado
  en el derecho, como una emisora de RC en modo 2 (``linear.z`` y ``angular.z`` a la izquierda,
  ``linear.x`` y ``linear.y`` a la derecha).
* **Personalizado:** asigna cada eje de cada joystick a cualquier eje lineal o angular.

Con un mando en el móvil se usan sus dos joysticks. En el PC el teclado solo mueve el joystick
izquierdo, así que el derecho se arrastra con el ratón.

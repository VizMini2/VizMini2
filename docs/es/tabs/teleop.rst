Teleop
======

.. clip:: teleop

Conduce el robot con un joystick en pantalla, con un mando Bluetooth o USB en el móvil o con el
teclado en el PC.

* Publica ``geometry_msgs/Twist`` en el topic de velocidad elegido al crear la pestaña
  (``/cmd_vel`` por defecto) a 10 Hz constantes mientras conduces. Activa **Usar TwistStamped**
  para los controladores que lo esperan (por ejemplo ``diff_drive_controller`` en Jazzy).
* La línea de arriba muestra el topic, el tipo de mensaje y cuántos suscriptores tiene, para saber
  si el robot está escuchando, y debajo la velocidad que se envía.
* **Ajustes** (botón del engranaje, no mientras conduces): el topic, la velocidad lineal máxima
  (hasta 3 m/s, 0,5 por defecto), la velocidad angular máxima (hasta 6 rad/s, 1,0 por defecto), el
  modo de control y la parada por pérdida de conexión.
* **Modos:** *Diferencial* (avanzar + girar), *Holonómico* (avanzar + desplazarse de lado) o
  *Personalizado*, donde cada eje del joystick puede mover cualquier eje lineal o angular, invertido
  si quieres.
* **Mando** (móvil): los joysticks conducen y el botón **B** es STOP.

En el PC, arrastra el joystick con el ratón o usa el teclado: :kbd:`W`/:kbd:`S` o :kbd:`↑`/:kbd:`↓`
y :kbd:`A`/:kbd:`D` o :kbd:`←`/:kbd:`→` mueven el joystick (el izquierdo), y :kbd:`Espacio` es STOP.

Seguridad
---------

* **STOP** envía velocidad cero y bloquea el joystick hasta que lo sueltes y lo vuelvas a tocar.
* Al soltar el joystick (o las teclas), el robot recibe velocidad cero tres veces y deja de
  publicarse, para no pelearse con otras fuentes de ``cmd_vel`` como Nav2.
* Si la app deja de responder más de 0,6 s mientras conduces, el robot se para.
* **Detener si se pierde la conexión mientras conduces** (activado por defecto) para el robot si se
  cae la conexión de ROS 2, si el robot deja de escuchar en el topic o, en el móvil, si se pierde la
  red. Desconectar el mando mientras conduces también lo para. Pulsa el aviso rojo para seguir.
* Salir de la pestaña, o dejar la app del móvil en segundo plano, para el robot.

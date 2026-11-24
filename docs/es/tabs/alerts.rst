Alertas
=======

.. clip:: alerts

Recibe una notificación cuando algo va mal en el robot, incluso con la app en segundo plano.

* **Reglas** sobre cualquier topic: un campo por encima o por debajo de un umbral
  (``/battery_state percentage < 0.2``, una temperatura, un código de error) o unos segundos sin
  mensajes (``/scan`` parado). Elige el topic y el campo de la red, o escríbelos; cada regla es un
  aviso o un error.
* Cada regla enseña si está saltando ahora y el último valor. Una regla avisa cuando empieza a
  cumplirse y otra vez cuando se recupera, no con cada mensaje.
* **Últimos avisos** guarda el historial de todas las alertas: tus reglas, los diagnósticos que
  pasan a *ERROR* y la pérdida de contacto con el robot.

En el móvil, el interruptor de arriba activa las notificaciones de alertas (están desactivadas hasta
que lo hagas); con el *servicio de conexión* (Configuración) siguen llegando con la app en segundo
plano. En el PC son notificaciones del escritorio mientras VizMini2 está abierta.

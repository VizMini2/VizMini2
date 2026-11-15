Alertas del robot
=================

**Ajustes → Alertas del robot** envía una notificación cuando algo va mal:

* **Se ha perdido el contacto con el robot.**
* **Diagnósticos en ERROR o STALE** y, si quieres, **también avisos (WARN)**. La misma alerta se
  repite como mucho una vez por minuto.
* **Nodos lifecycle perdidos, finalizados o con error.**

La red se revisa cada 3 segundos. Las alertas están desactivadas hasta que las activas, aquí o con
el interruptor de arriba de la pestaña Alertas. Funcionan con la app abierta, y en segundo plano si
está activada la :doc:`background`. **Enviar una notificación de prueba** comprueba que te llegan;
Android 13 y posteriores piden permiso la primera vez.

Tus propias reglas sobre cualquier topic (un valor que pasa un umbral, un topic que se calla) están
en la :doc:`pestaña Alertas <../tabs/alerts>`, que además guarda el historial de todas las alertas.

En el PC
--------

Las alertas son notificaciones del escritorio (con ``notify-send``) mientras VizMini2 está abierta.
Sus interruptores están arriba en la pestaña Alertas: **Notificaciones en este PC**, **Al perder el
contacto con el robot** y **Cuando un diagnóstico pasa a ERROR**, todos activados por defecto. Tus
reglas sobre topics funcionan igual que en el móvil.

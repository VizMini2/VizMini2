Botones para servicios y acciones
=================================

Objetivo: un panel con las órdenes que usas cada día.

Un botón de servicio
--------------------

1. Pulsa **+ → Botones** y luego **Añadir botón**.
2. Elige **Service** y pulsa **Explorar** para escogerlo (por ejemplo ``/demo_robot/return_home``
   en la demo, o ``/reset_odom`` en tu robot).
3. Rellena los campos de la petición, ponle una etiqueta y un color, y guárdalo.
4. Púlsalo: la respuesta aparece bajo el botón con lo que ha tardado.

Un botón de acción
------------------

Los mismos pasos eligiendo **Action** (por ejemplo ``/fibonacci`` con ``order: 10`` en la demo, o
``/navigate_to_pose`` con una pose en un robot con Nav2). Mientras se ejecuta, el botón muestra el
feedback y se convierte en **Cancelar**.

Un botón "mantener para moverse"
--------------------------------

Elige **Publicar**, topic ``/cmd_vel``, tipo ``geometry_msgs/msg/Twist``, ``linear.x = 0.2`` y pon
**Repetir mientras se mantiene pulsado** a 10 Hz. El robot solo se mueve mientras mantienes el
botón pulsado: al soltarlo, el botón publica una vez el mismo mensaje con todos los números a cero
(es una opción que puedes desactivar).

Para lo que sea arriesgado, activa **Pedir confirmación antes de enviar**.

En el PC funciona igual, haciendo clic en vez de pulsar (**Añadir** en la barra lateral es el
**+**).

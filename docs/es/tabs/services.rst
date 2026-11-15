Services
========

.. clip:: services

Todos los servicios de la red, como ``ros2 service list``; los que no tienen servidor van marcados.
Pulsa uno para llamarlo:

1. Rellena el formulario de la **Request** (un campo por cada campo de la petición).
2. Pulsa **Llamar**.
3. La **Response** aparece con *Respondido en N ms*. Si no hay respuesta en 10 segundos, la
   llamada falla.

Las llamadas funcionan con servidores en Fast DDS (``rmw_fastrtps_cpp``) y Cyclone DDS
(``rmw_cyclonedds_cpp``), incluso mezclados en la misma red, por Zenoh y a través de
foxglove_bridge. Si un servicio no responde, mira *Formato de servicios* en
:doc:`../config/network`. El tipo del servicio tiene que venir incluido en la app o haberlo
definido tú: si no lo está, *Definir el tipo…* te lo pide (:ref:`tus propios tipos <custom-types>`).

Para llamar a menudo al mismo servicio, hazte un botón en la pestaña :doc:`buttons`. En el PC la
pantalla de llamada se abre dentro de la pestaña; la flecha de arriba vuelve a la lista.

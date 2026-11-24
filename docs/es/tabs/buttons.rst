Botones
=======

.. clip:: buttons

Tus propios botones, cada uno para una sola cosa:

* **Publicar** un mensaje en un topic.
* **Llamar** a un servicio y mostrar la respuesta, con lo que ha tardado.
* **Enviar** un goal a una acción, mostrar su feedback y su resultado, y cancelarlo mientras se
  ejecuta (pulsa otra vez el botón y confirma con **Cancelar goal**).

Pulsa **Añadir botón**, elige el tipo y usa **Explorar** para escoger un topic, servicio o acción de
la red (o escribe su nombre), y después elige su tipo. Rellena el mensaje: hay un campo por cada
campo del mensaje, los arrays se escriben separados por comas y los arrays de mensajes en JSON. Los
campos de tiempo (como ``header.stamp``) tienen una casilla *hora actual al enviar*. Toca la línea
de debajo de un botón para ver entera la respuesta, el feedback o el error; ahí, una lista larga
muestra sus primeros valores y cuántos hay, por ejemplo ``[0, 1, 1, 2, 3, 5, ...] (11)``.

Los botones de servicio y de acción funcionan con los mismos middlewares que la pestaña
:doc:`services`.

Opciones
--------

* **QoS** al publicar: *Reliable* (por defecto), *Best effort* o *Transient local*.
* **Repetir mientras se mantiene pulsado (Hz, 0 = una vez)**, hasta 100 Hz: útil para "avanzar
  mientras lo pulso". Con repetición, al soltar el botón se publica una vez más el mensaje con todos
  los números a cero (activado por defecto), como un pedal de hombre muerto.
* **Tiempo de espera** de la respuesta o de aceptación del goal, 5 s por defecto.
* **Pedir confirmación antes de enviar**, para lo que sea peligroso.
* Un color: el de por defecto, azul, verde, rojo, naranja, morado o gris.

El menú ⋮ de cada botón tiene *Editar*, *Duplicar*, *Mover antes*, *Mover después* y *Eliminar*.
Los botones van con la pestaña cuando la duplicas, la compartes o la exportas.

Tipos de mensaje
----------------

La app incluye más de 450 interfaces (``std_msgs``, ``geometry_msgs``, ``nav_msgs``,
``sensor_msgs``, ``nav2_msgs``, ``control_msgs``, ``moveit_msgs``, ``std_srvs``,
``example_interfaces``…), y la lista para elegir el tipo tiene buscador. Los topics, servicios y
acciones cuyo tipo no viene incluido aparecen marcados como *tipo no incluido* al explorar.

.. _custom-types:

**Tus propios tipos:** para un tipo que la app no trae (el paquete de tu robot), *Definir un tipo
personalizado…* al final de la lista de tipos (o elegir el topic con *Explorar*, que lo ofrece
directamente) pide su nombre completo (``mi_robot/srv/SetLight``) y el texto de su fichero
``.msg``, ``.srv`` o ``.action``: pega lo que muestra ``ros2 interface show mi_robot/srv/SetLight``
en el robot. Puede usar los tipos que la app ya conoce y los que hayas definido antes. El tipo se
queda en este móvil o PC, y los botones, sliders e indicadores que lo usan llevan su definición,
así que también funcionan al exportarlos o al cargarlos desde un perfil en otro sitio.

.. container:: vm-clear

   Mira :doc:`../tutorials/buttons`.

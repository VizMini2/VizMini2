Conectar con tu robot
=====================

VizMini2 es un nodo de ROS 2 en tu móvil o en tu PC. Se une a la red DDS como cualquier otro nodo,
así que ve directamente los topics, servicios y acciones de tu robot: no hay que instalar nada en
el robot.

Antes de empezar
----------------

* El robot usa ROS 2 (Foxy o posterior) con **Fast DDS** (``rmw_fastrtps_cpp``, el de por defecto)
  o **Cyclone DDS** (``rmw_cyclonedds_cpp``). Los topics funcionan también con otras
  implementaciones de DDS. Para **Zenoh** (``rmw_zenoh_cpp``) o cualquier otro middleware, mira
  *Sin DDS* más abajo.
* El móvil y el robot están en la misma red.
* Sabes el ``ROS_DOMAIN_ID`` del robot (0 si nadie lo ha cambiado).

Pasos
-----

1. Abre **Ajustes** en la barra de abajo y, en **Red**, pon en **Domain ID** el ``ROS_DOMAIN_ID``
   del robot. ¿No sabes cuál es? **Buscar robots** muestra los dominios con nodos en la red: toca
   uno para conectarte a él.
2. Pulsa **Aplicar y reconectar**.
3. Ve a **Inicio**: *Conexión ROS 2* debe decir **Conectado**, y *En la red* debe mostrar los topics
   y nodos del robot.

Si *En la red* solo muestra este móvil
--------------------------------------

Muchas redes Wi-Fi (oficinas, universidades, redes de invitados y mesh) bloquean el tráfico
multicast que usa DDS para descubrir otros equipos. Entonces:

1. En **Ajustes → Red → Discovery peers**, escribe la IP del robot (varias, separadas por comas).
   Funciona como ``ROS_STATIC_PEERS``.
2. Pulsa **Aplicar y reconectar**.
3. Pulsa **Comprobar red** para ver qué ve el móvil y qué probar después.

En redes grandes, por 4G o a través de una VPN, usa un **Discovery Server** (el robot debe usar el
mismo). Mira :doc:`../config/network` y :doc:`../help/troubleshooting`.

Sin DDS
-------

* Robot con **Zenoh**: escribe su router de Zenoh (``tcp/IP_DEL_ROBOT:7447``) en **Ajustes → Red →
  Router de Zenoh**.
* Cualquier middleware, o un robot detrás de un router o en internet: arranca ``foxglove_bridge``
  en el robot y escribe ``ws://IP_DEL_ROBOT:8765`` en **Ajustes → Red → foxglove_bridge**.

Después pulsa **Aplicar y reconectar**. Los detalles están en :doc:`../config/network`.

En el PC
--------

Los mismos ajustes están en **Configuración** (el último elemento de la barra lateral) → **Red**. La
primera vez, la app toma ``ROS_DOMAIN_ID`` (y ``ROS_STATIC_PEERS`` o ``ROS_DISCOVERY_SERVER``) de su
entorno: la terminal desde la que la arrancas o, en Windows, las variables de entorno de tu usuario.
No hay Inicio: arriba en la barra lateral pone *Dominio N* cuando el nodo está en marcha, y una
pestaña **Topics** o **Nodos** (**Añadir** en la barra lateral) muestra lo que hay en la red. Si no
aparece nada, revisa también el cortafuegos: :doc:`../help/troubleshooting`.

Guárdalo
--------

Cuando funcione, guárdalo todo como **perfil de robot** (Ajustes → Perfiles de robot →
*Guardar como…*) para volver con un toque, en cualquiera de tus dispositivos:
:doc:`../config/profiles`.

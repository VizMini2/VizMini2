Preguntas frecuentes
====================

¿Hay que instalar algo en el robot?
   No. La app es un nodo de ROS 2 que se une directamente a la red DDS. Los robots que usan Zenoh
   solo necesitan su router de Zenoh de siempre. Para dibujar las mallas del robot en el móvil,
   sírvelas por HTTP desde el robot (:doc:`../tutorials/visualize-robot`).

¿Qué versiones de ROS 2 funcionan?
   Foxy, Humble, Jazzy, Kilted y Lyrical, con Fast DDS, Cyclone DDS o Zenoh, o con cualquier
   middleware a través de foxglove_bridge. Mira :doc:`requirements`.

¿Hay versión para PC?
   Sí, para Linux (Ubuntu 20.04 o posterior) y Windows (10 u 11), con las mismas pestañas:
   :doc:`../getting-started/desktop`.

¿Hay versión para Mac?
   Todavía no: solo Linux y Windows, en PCs x86-64.

¿Funciona por 4G o VPN?
   Sí, con un **Discovery Server** al que lleguen el móvil y el robot, con Discovery peers a
   través de la VPN, o con un router de Zenoh o un foxglove_bridge al que llegue el móvil.

¿Se envían los datos de mi robot a algún sitio?
   No. Los datos de ROS 2 van directamente entre tu móvil o tu PC y tus robots. Mira
   :doc:`privacy`.

¿Se puede usar en una tablet?
   Sí, en vertical o en horizontal.

¿Puedo usar un mando?
   En el móvil, sí: Bluetooth o USB, en las pestañas Teleop. En el PC se conduce con el teclado:
   :kbd:`W`/:kbd:`S` o las flechas arriba y abajo para avanzar y retroceder, :kbd:`A`/:kbd:`D` o
   las flechas izquierda y derecha para girar, y :kbd:`Espacio` para parar.

¿La demo toca mi red?
   No: usa su propio dominio (231) y solo la red interna del dispositivo (loopback), tanto en el
   móvil como en el PC.

¿Necesito una cuenta?
   No. Todo funciona sin ella. La cuenta es opcional y solo sincroniza tus perfiles de robot entre
   tus dispositivos: :doc:`../getting-started/account`.

¿Es gratis?
   Sí, y sin anuncios. Si te resulta útil, puedes apoyar el proyecto en
   `GitHub Sponsors <https://github.com/sponsors/Juancams>`_.

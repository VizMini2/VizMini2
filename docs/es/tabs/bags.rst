Bags
====

.. clip:: bags

Graba topics en ficheros MCAP y reprodúcelos.

Grabar
------

Pulsa **Grabar**, elige los topics (o **Todos**) y pulsa **Grabar**. Mientras graba ves el tiempo,
los mensajes y el tamaño, topic a topic. Pulsa **Detener** al terminar. La grabación y la
reproducción siguen aunque cambies a otra pestaña.

Los ficheros se guardan como ``vizmini2_<fecha>_<hora>.mcap`` (sin comprimir, perfil de ROS 2) y se
abren con ``ros2 bag`` y con cualquier herramienta que lea MCAP. Solo se pueden grabar los topics cuyo tipo conoce la app; los
demás no aparecen en la lista.

Reproducir
----------

Pulsa **Reproducir** en una grabación para volver a publicar sus mensajes a 0,5×, 1× o 2×, con
bucle y pausa. Arrastra la barra de progreso para saltar a cualquier instante, también en pausa. Al
saltar hacia delante se vuelve a publicar el último mensaje de los topics «latched»
(``/tf_static``, ``/map``, ``/robot_description``…) para que la vista 3D conserve el mapa y el
robot. Los topics cuyo tipo no conoce la app se omiten.

Con **Reproducir solo en este móvil**, el bag va directo a las pestañas de la app, sin DDS (y la
conexión pasa a una aislada, para que no se mezcle nada de la red): la vista 3D, las cámaras, las
gráficas, los indicadores… ven el mismo bag en el mismo instante, y no sale nada a la red ni llega
al robot. Los mensajes se entregan tal cual están en el bag, así que llegan enteras incluso las nubes
y las imágenes grandes, no se omiten los topics de tipos que la app no incluye, y las gráficas usan
el tiempo del bag. Mientras se reproduce en el móvil aparece una **línea de tiempo** sobre las
pestañas, en todas ellas: desde ahí se pausa, se salta a cualquier momento o se para. Al parar vuelve tu conexión.

Los dashboards también muestran una barra de reproducción mientras se reproduce cualquier bag, con pausa,
un control para saltar, la velocidad (tócala para pasar de 0,25× a 4×) y parar. Para ver un bag en
gráficas sin publicar nada, ábrelo en una pestaña :doc:`plot`.

Importar y compartir
--------------------

**Importar** abre ficheros ``.mcap``, sin comprimir o comprimidos con lz4 o zstd (el de por defecto
en muchas instalaciones de ROS 2). Las bags ``.db3`` (SQLite) no son compatibles. **Compartir**
envía una grabación a otra app (correo, Drive…) y **Eliminar** la borra.

En el PC las grabaciones están en ``~/.local/share/vizmini2/bags``: **Importar** abre un diálogo
para elegir el fichero y **Compartir** enseña dónde está. La opción *Reproducir solo en este móvil*
funciona igual allí, y la barra de reproducción se queda debajo de la pestaña que tengas abierta.

.. container:: vm-clear

   Mira :doc:`../tutorials/record-bag`.

VizMini2
========

**ROS 2 en tu bolsillo y en tu mesa.** VizMini2 te deja ver, conducir e inspeccionar robots con
ROS 2 desde un **móvil Android** o un **PC con Linux o Windows**: una vista 3D como RViz, cámaras y vídeo en
directo, joysticks, tus propios botones para servicios y acciones, MoveIt y ros2_control, mapas
para Nav2, gráficas, alertas, parámetros de nodos, nodos lifecycle, bags y mucho más. Habla
directamente con tus robots por tu red (DDS), sin puentes ni servidores en el robot (también
funciona con Zenoh y con foxglove_bridge).

No necesitas cuenta. ¿Aún no lo tienes claro? **Prueba la demo**: un robot simulado que funciona
solo dentro del móvil o del PC, con todo funcionando.

.. clip:: dashboard
   :align: center
   :caption: Un dashboard con la vista 3D, la cámara, el joystick y los diagnósticos.

Móvil o PC
----------

Las dos apps tienen las mismas pestañas y ajustes, y tus pestañas, perfiles y diseños pasan de una
a otra sin más. El manual cubre las dos: donde cambian (táctil o ratón, mando o teclado, menú de
compartir o diálogo de ficheros), cada página lo dice.

.. raw:: html

   <div class="vm-grid">
     <div class="vm-card vm-entry"><b>En el móvil (Android 8.0+)</b>Instálala desde Google Play o como APK, prueba el robot de demo y conéctate al tuyo. <a href="getting-started/install.html">Instalar en Android</a></div>
     <div class="vm-card vm-entry"><b>En el PC (Ubuntu 20.04+ o Windows 10/11)</b>Un <code>.deb</code>, un <code>.msi</code> o <code>ros2 run vizmini2 vizmini2</code>: una ventana con barra lateral, atajos de teclado y arrastrar y soltar. <a href="getting-started/desktop.html">La app de PC</a></div>
   </div>

Qué puede hacer
---------------

.. raw:: html

   <div class="vm-grid">
     <div class="vm-card"><b>Ver</b>Vista 3D con 34 tipos de display (los de RViz y los mapas de varias plantas de NavMap), cámaras y vídeo H.264/H.265, el árbol TF, el grafo de nodos, mapas GPS y tus propios dashboards. <a href="tabs/rviz.html">Pestaña RViz</a></div>
     <div class="vm-card"><b>Conducir</b>Joysticks en pantalla, un mando o el teclado, con paradas de seguridad; metas y waypoints de Nav2 desde el mapa. <a href="tabs/teleop.html">Teleop</a></div>
     <div class="vm-card"><b>Mover brazos</b>Planifica y ejecuta con MoveIt, y cambia o manda los controladores de ros2_control. <a href="tabs/moveit.html">MoveIt</a> · <a href="tabs/controllers.html">Controladores</a></div>
     <div class="vm-card"><b>Actuar</b>Botones y sliders que publican, llaman a servicios o envían goals. <a href="tabs/buttons.html">Botones</a></div>
     <div class="vm-card"><b>Mapas</b>Guarda mapas, maneja SLAM Toolbox, limpia costmaps y dibuja zonas prohibidas y de velocidad para Nav2. <a href="tabs/maps.html">Mapas</a></div>
     <div class="vm-card"><b>Graficar y vigilar</b>Gráficas con fórmulas y Lua, indicadores y alertas cuando un valor cruza un límite o un topic se calla. <a href="tabs/plot.html">Gráficas</a> · <a href="tabs/alerts.html">Alertas</a></div>
     <div class="vm-card"><b>Inspeccionar</b>Nodos y parámetros, topics con eco y frecuencia, servicios, acciones y nodos lifecycle, y los planes de PlanSys2 mientras se ejecutan. <a href="tabs/nodes.html">Nodos</a> · <a href="tabs/plansys2.html">PlanSys2</a></div>
     <div class="vm-card"><b>Grabar</b>Bags MCAP compatibles con ros2 bag, y vídeos de la pantalla para enseñar qué pasó. <a href="tabs/bags.html">Bags</a></div>
     <div class="vm-card"><b>Compartir y migrar</b>Perfiles como ficheros o códigos QR; pestañas desde y hacia ficheros de RViz 2 y layouts de otras herramientas. <a href="config/import-export.html">Importar y exportar</a></div>
   </div>

Por dónde empezar
-----------------

* ¿Eres nuevo? :doc:`getting-started/install` (móvil) o :doc:`getting-started/desktop` (PC), y
  después :doc:`getting-started/demo` y :doc:`getting-started/connect`.
* ¿Vienes de RViz o de otra herramienta de visualización? :doc:`tutorials/import-configs`.
* ¿Buscas una pantalla concreta? Cada pestaña está explicada en **Pestañas**, en el menú, con un
  vídeo.
* ¿Algo no funciona? Mira :doc:`help/troubleshooting` o :doc:`help/contact`.
* ¿Quieres compilarla o ayudar? :doc:`developers/index`.

.. toctree::
   :maxdepth: 2
   :caption: Primeros pasos
   :hidden:

   getting-started/install
   getting-started/desktop
   getting-started/account
   getting-started/demo
   getting-started/connect
   getting-started/interface

.. toctree::
   :maxdepth: 2
   :caption: Tutoriales
   :hidden:

   tutorials/drive-demo
   tutorials/visualize-robot
   tutorials/nav2
   tutorials/dashboard
   tutorials/buttons
   tutorials/record-bag
   tutorials/lifecycle-params
   tutorials/moveit
   tutorials/controllers
   tutorials/maps-zones
   tutorials/alerts
   tutorials/import-configs

.. toctree::
   :maxdepth: 2
   :caption: Pestañas
   :hidden:

   tabs/visualize
   tabs/control
   tabs/inspect
   tabs/record

.. toctree::
   :maxdepth: 2
   :caption: Configuración
   :hidden:

   config/network
   config/profiles
   config/import-export
   config/alerts
   config/background
   config/language

.. toctree::
   :maxdepth: 2
   :caption: Cuenta
   :hidden:

   accounts/delete

.. toctree::
   :maxdepth: 2
   :caption: Desarrollo
   :hidden:

   developers/index

.. toctree::
   :maxdepth: 2
   :caption: Ayuda
   :hidden:

   help/troubleshooting
   help/faq
   help/requirements
   help/limitations
   help/contact
   help/privacy
   changelog

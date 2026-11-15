Enviar metas a Nav2
===================

Objetivo: localizar un robot con Nav2 y mandarlo a un punto del mapa, como las herramientas *2D Pose
Estimate* y *Nav2 Goal* de RViz.

1. Conéctate al robot y abre una pestaña **RViz** con el fixed frame ``map`` y el display Map.
2. Pulsa **Pose**, toca la posición real del robot en el mapa y arrastra hacia donde mira. Esto
   publica ``/initialpose``; AMCL corrige la posición del robot (añade un display
   **PoseWithCovariance** en ``/amcl_pose`` para verla, o **PoseArray** en ``/particlecloud``).
3. Pulsa **Goal**, toca el destino y arrastra para elegir la orientación final. Esto publica
   ``/goal_pose`` y Nav2 se pone en marcha; el display **Path** en ``/plan`` muestra la ruta.

Si tu robot usa otros topics (por ejemplo con un namespace), cámbialos desde el menú, en *Topics de
las herramientas…*. Si los servidores de Nav2 no están activos, abre una pestaña :doc:`../tabs/lifecycle`
para ver su estado.

En el PC funciona igual con el ratón: haz clic en **Pose** o **Goal**, después haz clic en el mapa
y arrastra para fijar la orientación.

Grabar y reproducir un bag
==========================

Objetivo: grabar lo que ve el robot y revisarlo después en un ordenador.

1. Pulsa **+ → Bags**.
2. Pulsa **Grabar**, elige los topics (o **Todos**) y vuelve a pulsar **Grabar**. La tarjeta muestra
   el tiempo, el número de mensajes, el tamaño y los mensajes por topic.
3. Pulsa **Detener**. La grabación aparece en la lista.
4. **Compártela** con tu ordenador y ábrela con cualquier herramienta que lea MCAP, o con::

      ros2 bag info vizmini2_2026-10-09_12-30-05.mcap
      ros2 bag play vizmini2_2026-10-09_12-30-05.mcap

5. O pulsa **Reproducir** para volver a publicarla desde el móvil (0,5×, 1× o 2×, con bucle), por
   ejemplo para probar displays de RViz sin el robot. Con **Reproducir solo en este móvil** no sale
   nada a la red: solo la ven las pestañas de la propia app.

Solo se graban los topics cuyo tipo conoce la app. **Importar** añade bags MCAP grabados en otro
sitio (por ejemplo con ``ros2 bag record -s mcap``) para reproducirlos.

En el PC
--------

Los mismos pasos funcionan. Los bags se guardan en ``~/.local/share/vizmini2/bags`` en Linux y en
``%LOCALAPPDATA%\VizMini2 Data\bags`` en Windows; **Compartir** muestra dónde está el fichero, para
que lo abras ahí mismo con ``ros2 bag``.

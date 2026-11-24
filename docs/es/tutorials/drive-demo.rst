Conducir el robot de la demo
============================

Cinco minutos, sin robot. La demo funciona en el móvil o en el PC.

1. Arranca la demo (:doc:`../getting-started/demo`).
2. Abre **RViz demo**: el robot recorre la habitación solo, con su láser, su mapa y su ruta
   planificada.
3. Abre **Conducir** y mueve el joystick: el robot te sigue y se para al soltarlo. Pulsa **STOP**
   cuando quieras.
4. De vuelta en **RViz demo**, prueba las vistas del menú (en el PC, el botón de vista de la barra
   de herramientas; *TopDownOrtho* es ideal para mapas) y los gestos: un dedo gira, dos dedos
   desplazan, pellizcar hace zoom.
5. Abre **Cámara** para ver lo que ve el robot.
6. Abre **Botones** y pulsa **Volver a la base**: el robot vuelve a su base. **Fibonacci 10**
   ejecuta una acción y muestra su feedback.
7. Abre **Lifecycle** y pulsa **Deactivate** en ``/camera_driver``: la cámara se para. Vuelve a
   pulsar **Activate**.

Después, cambia cómo se comporta el robot: en una pestaña **Nodos** abre ``/demo_robot`` y baja
``max_linear_speed``, o pon ``autopilot`` a ``false`` para que te espere. Las pestañas **Sliders**,
**Indicadores**, **Gráficas** y **Mapa GPS** de la demo también están listas para probar.

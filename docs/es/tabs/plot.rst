Gráficas
========

.. clip:: plot

Gráficas de cualquier campo numérico: varios paneles con el mismo eje de tiempo,
series de distintos topics, gráficas XY y los datos de un bag grabado.

Paneles y series
----------------

Una pestaña tiene hasta tres paneles (menú ⋮ → *Añadir panel*) con hasta seis series cada uno. En
cada panel, **Añadir serie** pide el topic (sugiere los de la red, o los del bag abierto), el tipo de mensaje y el **campo**, que
se escribe como en los :doc:`indicators`: ``twist.twist.linear.x``, ``ranges[-1]``,
``status{name==motor}.level``… Cada serie lleva
un nombre, un color y una transformación:

* **Derivada**: cuánto cambia por segundo (de una posición, la velocidad).
* **Media móvil** de las últimas *N* muestras, para suavizar.
* **Integral**: la suma acumulada en el tiempo (de una velocidad, la distancia).
* **Escala** y **desplazamiento**: valor × escala + desplazamiento (57.2958 pasa radianes a grados).

Fórmulas
--------

Elige **Fórmula** en vez de *Campo de un topic* para calcular una serie con otras.
Cada serie va entre llaves con su nombre de la leyenda (en el diálogo, tócala
para meterla):

* ``{cmd} - {odom}``: el error entre una orden y la medida;
* ``hypot({vx}, {vy})``: la velocidad a partir de sus componentes;
* ``deg(atan2({y}, {x}))``, ``{velocidad} * 3.6``…

Se pueden usar ``+ - * / % ^``, paréntesis, ``pi``, ``e`` y ``abs sqrt exp log log10 sin cos tan
asin acos atan atan2 hypot min max floor ceil round sign deg rad``. El valor se calcula cada vez que
la primera serie de la fórmula tiene una muestra nueva, con el último valor de las demás. Una
fórmula puede usar otra, y sigue funcionando si cambias el nombre de una serie que usa. La
transformación, la escala y el desplazamiento también valen para las fórmulas.

Scripts Lua
-----------

Para lo que no da una fórmula (guardar un estado, condiciones, bucles), elige **Script (Lua)**.
Elige las **series de entrada** (la primera marca el tiempo),
escribe las **variables globales** (se ejecutan una vez) y el cuerpo de
``function(time, value, v1, v2…)``: ``value`` es la primera entrada, ``v1``, ``v2``… el último
valor de las demás y ``time`` va en segundos. Devuelve un número, o ``nil`` para saltarte esa
muestra. Por ejemplo, la distancia recorrida a partir de una velocidad, que vuelve a cero al
pararse::

   -- globales
   dist = 0
   last = nil

   -- función
   if last ~= nil then dist = dist + value * (time - last) end
   last = time
   if math.abs(value) < 0.01 then dist = 0 end
   return dist

Los scripts tienen ``math``, ``string`` y ``table``, sin acceso a archivos ni al sistema.

Paneles
-------

Toca una serie para editarla o quitarla. Desde el menú ⋮ del panel, **Ver como XY** enseña la
primera serie (x) frente a la segunda (y), por ejemplo la trayectoria de ``/odom``. Con **Misma
escala en x e y** la trayectoria no se deforma. **Ver en el tiempo** lo devuelve a un panel normal
y **Quitar panel** lo borra.

**Ver como transiciones de estado** dibuja cada
serie como una franja que cambia de color cada vez que cambia su valor, con el valor escrito encima:
un modo, el estado de la navegación, el de la batería… En estos paneles una serie también puede ser
un campo de **texto** (``status.message``, ``mode``): cada texto distinto tiene su color.

**Ver como espectro (FFT)** dibuja la amplitud de cada frecuencia de lo que hay en la ventana de
tiempo (la leyenda dice el pico), útil para encontrar vibraciones; **Ver como histograma** dibuja
cómo se reparten los valores, con la media y la desviación típica en la leyenda. En los paneles XY,
**Puntos en vez de una línea** dibuja una nube de puntos.

Moverse por el tiempo
---------------------

* **Pellizca** para cambiar la ventana de tiempo (también con el botón de la duración, de 5 s a 15 min).
* **Arrastra** hacia la derecha para ir atrás: la gráfica deja de seguir los datos nuevos. El botón
  de pausa hace lo mismo y el de reproducir vuelve a seguirlos.
* **Toca** un instante para poner el cursor: la leyenda de cada panel enseña el valor de todas las
  series en ese momento. El **doble toque** quita el cursor y vuelve a seguir los datos.
* **Mantén pulsado** para poner un segundo cursor y **medir**: se sombrea lo que hay entre los dos y
  la leyenda enseña el tiempo entre ellos (Δt) y, de cada serie, su valor en el segundo cursor y
  cuánto ha cambiado (Δ).

En el PC, la rueda del ratón cambia la ventana de tiempo, arrastrar mueve por el tiempo, un clic pone
el cursor, mantener pulsado el botón pone el segundo y un doble clic los quita.

**Estadísticas** (menú ⋮) añade bajo cada panel de tiempo el mínimo, el máximo, la media, la
desviación típica (σ), la frecuencia (Hz) y el número de muestras de cada serie, de lo que se ve o,
con dos cursores, de lo que hay entre ellos. **Borrar datos** vacía las gráficas en vivo.

El tiempo de cada muestra es cuando llegó. Con **Tiempo de header.stamp** (menú
⋮) es en cambio el ``header.stamp`` de cada mensaje (en los mensajes sin header, sigue siendo cuando
llegó). Es el momento en que se midió el dato, y alinea topics que llegan con retrasos distintos.
Si el robot va con tiempo simulado, la ventana sigue al último dato y no al reloj del dispositivo. En los
bags sustituye a la hora en que se grabó cada mensaje.

Bags
----

En el botón de la fuente (*En vivo*) elige un bag de los que hay en las pestañas :doc:`bags`. Se lee
entero sin publicar nada, con el tiempo de cada mensaje en el bag, y abajo aparece una línea de
tiempo para mover el cursor. El botón de reproducir lo recorre a velocidad real.

Los bags largos no tienen que caber en memoria: las series con más de 150 000 mensajes se enseñan
primero reducidas (el mínimo y el máximo de cada tramo, que es lo que se ve con la ventana abierta, y
el botón de la fuente pone *vista reducida*). Al acercarte se vuelve a leer solo el tramo visible a
resolución completa.

**Importar CSV…** en el mismo botón abre un CSV como fuente (en el PC, con el diálogo de archivos del
sistema): la columna que se llama ``time`` (o ``timestamp``, ``stamp``, ``t``, ``__time``; si no, la
primera) es el tiempo en segundos y las demás son series. Añádelas con ``csv`` como topic y el nombre
de la columna como campo.

Otras fuentes: UDP, MQTT y ZeroMQ
---------------------------------

Una serie también puede leer mensajes JSON que no vienen de
ROS. Escribe la fuente como topic, y el campo como una ruta dentro del JSON (``speed``,
``battery.pct``, ``motors[-1].current``, también con filtros):

* ``udp://0.0.0.0:9870``: datagramas con un JSON cada uno, enviados al móvil o al PC a ese puerto;
* ``mqtt://BROKER:1883/robot/#``: un broker MQTT y el topic (se admiten comodines), con
  ``mqtt://usuario:clave@BROKER/…`` si los pide y ``mqtts://`` (puerto 8883) para TLS, comprobado
  con los certificados del sistema; un mensaje que es solo un número se lee como ``value``;
* ``zmq://HOST:9872``: un socket PUB de ZeroMQ (``zmq://HOST:9872/prefijo`` para suscribirse solo a
  un prefijo; con varias partes, el JSON es la última).

Se reconectan solas, y si hay algún problema sale en la leyenda de la serie.

Exportar
--------

*Exportar CSV* en el menú ⋮ guarda todas las series en un CSV (una columna de tiempo y otra por
serie, con el último valor de cada una en cada instante). El móvil lo comparte; el PC lo guarda en
*~/Documents* y dice dónde. Un bag reducido se vuelve a leer para exportarlo a resolución completa.

Las pestañas de gráficas también se intercambian con otras herramientas de gráficas: sus layouts de
gráficas (gráficas, gráficas XY y series Lua personalizadas) se importan como pestañas de gráficas, y
una pestaña de gráficas se exporta como layout de gráficas. Mira :doc:`../config/import-export`.

La pestaña también se puede añadir a un :doc:`dashboard`; soltar un topic o un campo sobre un widget
de gráficas añade una serie. En el móvil, el modo demo trae una con las velocidades y la trayectoria
del robot simulado.

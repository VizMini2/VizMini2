Indicadores
===========

.. clip:: indicators

Valores en vivo de cualquier topic, como números, agujas, barras, luces, texto o tablas.
Cada indicador se suscribe a un topic y enseña **un campo** del mensaje, o todos en una tabla.

Pulsa **Añadir indicador**, escribe el topic o usa **Buscar en la red…**, elige el tipo de mensaje,
cómo mostrarlo y el **campo**. El campo se escribe como en ``ros2 topic echo --field``:
``data``, ``battery.percentage``, ``ranges[0]``, ``status[0].message``… Las sugerencias traen el
primer elemento de cada array; el índice se puede cambiar a mano. Los tiempos (``header.stamp``)
se ven en segundos.

Las rutas admiten también:

* **índices negativos**, contados desde el final: ``poses[-1].pose.position.x`` es la última pose;
* **filtros** entre llaves tras un array, que cogen el primer elemento que cumple:
  ``status{name=="motor"}.level``, ``detections{id==3}.score``;
* **filtros** al principio, que usan solo los mensajes que cumplen: ``{mode==2}.speed``. El
  indicador se queda con su último valor mientras los mensajes no cumplen.

Un filtro compara un campo con ``==``, ``!=``, ``<``, ``<=``, ``>`` o ``>=`` y un número,
``true``/``false`` o un texto (con o sin comillas).

Formas de mostrarlo
-------------------

* **Número**: el valor en grande, con su unidad.
* **Aguja**: un arco de *mínimo* a *máximo* con la aguja.
* **Barra**: una barra horizontal de *mínimo* a *máximo*.
* **Luz**: se enciende cuando el valor es verdadero (o distinto de cero, o un texto no vacío),
  cuando es mayor o igual que un umbral, menor o igual, o igual a un número o un texto. Eliges el
  color encendida y apagada (por ejemplo, rojo cuando la batería baja del 30 %).
* **Texto**: un string, un modo, un mensaje de estado…
* **Tabla**: todos los campos del mensaje, o de un submensaje (``pose.pose.position``). Los
  arrays se resumen con sus primeros elementos y cuántos hay.

Opciones
--------

* Un **nombre** (opcional: si no, el topic y el campo).
* **Unidad** y **decimales** (vacío: según el tipo del campo; los enteros sin decimales).
* **Recepción**: *Best effort* funciona con cualquier publicador; *Reliable* pide que llegue cada
  mensaje (no conecta con publicadores best effort); *Transient local* recibe además el último valor
  de los topics «latched» (como ``/map`` o ``/robot_description``).
* **Marcar sin datos tras** unos segundos: si el topic deja de publicar, la tarjeta se atenúa y
  dice cuánto tiempo lleva sin datos. Con 0 no se marca nunca.
* Un color para el valor, la aguja o la barra.

Las tarjetas ocupan tantas columnas como quepan a lo ancho; los textos y las tablas ocupan una fila
entera. El menú ⋮ de cada indicador tiene *Editar*, *Duplicar*, *Mover antes*, *Mover después* y
*Eliminar*.

Los indicadores quedan muy bien como widgets de un :doc:`dashboard`: en modo edición en el móvil, el
**+** del widget añade otro, y un widget vacío añade uno al tocarlo. El modo demo trae una
pestaña *Indicadores* con la batería, la velocidad y la posición del robot simulado.

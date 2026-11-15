Mensaje
=======

.. clip:: message

El último mensaje de cualquier topic como un árbol que se despliega. Toca el topic de arriba para elegir uno de la red: la pestaña se suscribe con su tipo
y enseña cuántos mensajes han llegado, con qué frecuencia y hace cuánto llegó el último.

Los submensajes del primer nivel salen desplegados; toca una fila con ▸ para desplegarla (los
arrays enseñan su tamaño y los 100 primeros elementos). Lo que despliegas se queda desplegado aunque
lleguen mensajes nuevos.

Escribe una **ruta** para ver solo una parte del mensaje, con la misma sintaxis que los
:doc:`indicators` y :doc:`plot`: ``pose.pose.position``, ``poses[-1]``, ``status{name==motor}``.
Con un filtro al principio (``{header.frame_id==odom}.twist``), un mensaje que no lo cumple muestra
*No hay nada en …*.

El botón de pausa congela lo que ves (los mensajes se siguen contando) y **Copiar como JSON** copia
el mensaje, o la parte de la ruta, para pegarlo en un chat o en el mensaje de un botón. Se puede
mostrar cualquier tipo de mensaje incluido en la app, y cualquier otro en cuanto lo definas
(*Definir el tipo…*, mira :ref:`tus propios tipos <custom-types>`).

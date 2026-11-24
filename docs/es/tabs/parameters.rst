Parámetros
==========

.. clip:: parameters

Los parámetros de un nodo, a mano para cambiarlos. Toca el
nodo de arriba para elegir uno de la red. Cada parámetro enseña su valor, su tipo y si es de solo
lectura; tócalo para ver su descripción y cambiarlo (los arrays se escriben como ``[1, 2, 3]``). El
filtro deja solo los parámetros cuyo nombre contiene lo que escribes, y se recuerda.

Es el mismo editor que el del inspector de nodos (:doc:`nodes`), pero siempre abierto en el mismo
nodo: ponlo en un :doc:`dashboard` junto a una pestaña :doc:`plot` para ajustar un controlador
mientras lo ves. **Actualizar** vuelve a leer los valores, y los cambios que haga cualquier otro
(``ros2 param set``, el propio nodo, otra app) aparecen al momento, por ``/parameter_events``.

Funciona con nodos en Fast DDS o Cyclone DDS, por Zenoh y a través de foxglove_bridge (mira
:doc:`../config/network`). Si el bridge no ofrece los servicios de parámetros del nodo, la app usa
el soporte de parámetros del propio bridge, que no da descripciones.

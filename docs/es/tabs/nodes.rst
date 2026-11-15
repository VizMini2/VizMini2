Nodos
=====

.. clip:: nodes

Todos los nodos de la red, como ``ros2 node list``, con sus parámetros y un filtro.

* Cada nodo muestra cuántos topics publica y a cuántos se suscribe, y sus servicios. Los nodos
  lifecycle van marcados, y cada nodo muestra el DDS en el que corre (*Fast DDS*, *Cyclone DDS*,
  *Zenoh*…), también en el título del inspector.
* Pulsa un nodo para abrir el **inspector**: sus parámetros, y lo que publica, a lo que se suscribe
  y los servicios que ofrece, con sus tipos. En el móvil, pulsa ahí un topic para abrirlo.
* Pulsa un parámetro para ver su descripción y cambiarlo. Los de solo lectura no se pueden cambiar.
  Los arrays se escriben como ``[1, 2, 3]``.

Los parámetros funcionan con nodos en Fast DDS o Cyclone DDS, por Zenoh y a través de
foxglove_bridge (mira :doc:`../config/network`). En el PC el inspector se abre dentro de la pestaña;
la flecha de arriba vuelve a la lista.

.. container:: vm-clear

   Mira :doc:`../tutorials/lifecycle-params`.

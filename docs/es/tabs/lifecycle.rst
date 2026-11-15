Lifecycle
=========

.. clip:: lifecycle

Mira y cambia el estado de los nodos gestionados (lifecycle), como los servidores de Nav2 o tus
drivers.

* Al crear la pestaña ofrece añadir los nodos lifecycle que encuentra; también puedes añadirlos
  después con **Añadir nodo**, desde la red o por nombre.
* Cada nodo muestra su estado en directo (*unconfigured*, *inactive*, *active*, *finalized*) y
  botones para las transiciones que permite en ese momento (configure, activate, deactivate,
  cleanup, shutdown…). Shutdown pide confirmación antes, porque un nodo *finalized* hay que
  reiniciarlo en el robot para volver a usarlo.
* El estado se actualiza en cuanto el nodo cambia (también si lo cambia otro) y se vuelve a leer
  cada 5 segundos. **Actualizar** en cada nodo, o **Actualizar todo**, lo lee al momento.
* Cada nodo tiene además *Parámetros e info*, que abre su inspector (:doc:`nodes`), y **×** para
  quitarlo de la pestaña.

Funciona con nodos en Fast DDS o Cyclone DDS, por Zenoh y a través de foxglove_bridge (mira
:doc:`../config/network`). En el PC, *Parámetros e info* abre el inspector del nodo dentro de la
pestaña (la flecha de arriba vuelve).

.. container:: vm-clear

   Mira :doc:`../tutorials/lifecycle-params`.

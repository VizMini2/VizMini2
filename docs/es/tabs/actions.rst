Actions
=======

.. clip:: actions

Todas las acciones de la red, como ``ros2 action list``; las que no tienen servidor van marcadas.
Pulsa una para enviar un goal:

1. Rellena el formulario del **Goal** y pulsa **Enviar goal**.
2. El **Feedback** se actualiza en directo mientras se ejecuta; **Cancelar** lo detiene.
3. El **Resultado** aparece con su estado final (*SUCCEEDED*, *CANCELED*, *ABORTED*) y lo que ha
   tardado.

El tipo de la acción tiene que venir con la app o haberlo definido tú: si no, *Definir el tipo…* te
lo pide (:ref:`tus propios tipos <custom-types>`). En esta pantalla se ejecuta un goal cada vez, y
al salir de ella se cancela el que esté en marcha (girar el móvil no lo cancela). Las acciones
funcionan con los mismos middlewares que :doc:`services`; a través de foxglove_bridge, el bridge
tiene que arrancarse con ``include_hidden:=true`` (mira :doc:`../config/network`). Para enviar a
menudo el mismo goal, hazte un botón en la pestaña :doc:`buttons`.

En el PC la pantalla del goal se abre dentro de la pestaña; la flecha de arriba vuelve a la lista.

Nodos lifecycle y parámetros
============================

Objetivo: arrancar un nodo lifecycle y ajustar un parámetro, sin terminal.

Lifecycle
---------

1. Pulsa **+ → Lifecycle**, deja marcado **Añadir los nodos lifecycle encontrados ahora** y pulsa
   **Crear** (en la demo: ``/camera_driver``). Después, **Añadir nodo** añade más.
2. Cada nodo muestra su estado. Pulsa **Configure** y después **Activate** (confirma cada una). El
   estado cambia en directo, también cuando lo cambia otro programa (como el lifecycle manager de
   Nav2).
3. **Deactivate** pausa el nodo; **Cleanup** lo devuelve a *unconfigured*; **Shutdown** lo finaliza.

Parámetros
----------

1. Pulsa **+ → Nodos** y toca un nodo (en la demo: ``/demo_robot``).
2. El inspector muestra sus parámetros con su tipo, valor y descripción.
3. Toca uno para cambiarlo. El nodo puede rechazar un valor (fuera de rango, solo lectura…): la app
   muestra el motivo que da.

Una pestaña **Parámetros** muestra lo mismo para el nodo que elijas.

En la demo, pon ``max_linear_speed`` a ``0.2`` y conduce: el robot va más lento al momento.

En el PC funciona igual, haciendo clic en vez de pulsar (**Añadir** en la barra lateral es el
**+**).

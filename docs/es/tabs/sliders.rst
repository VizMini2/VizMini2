Sliders
=======

.. clip:: sliders

Sliders que publican un número: una velocidad, un ángulo, un brillo, una consigna… Cada slider
mueve **un campo numérico** de cualquier mensaje (``data`` de ``std_msgs/Float64``, ``linear.x`` de
un ``Twist``…) y envía el resto de campos con valores fijos, igual que la pestaña :doc:`buttons`.

Pulsa **Añadir slider**, escribe el topic o usa **Buscar en la red…** (solo muestra los topics
cuyo tipo tiene algún campo numérico), elige el tipo de mensaje y el **campo** que mueve el slider.
Debajo, rellena el resto de campos del mensaje si necesitan un valor distinto del de por defecto.

Opciones
--------

* **Mínimo**, **máximo**, **paso** (0 = continuo) y **valor inicial**. Los campos enteros se
  redondean y el rango tiene que caber en su tipo (por ejemplo 0–255 para ``uint8``). Para los
  campos ``linear.*`` y ``angular.*`` se propone un rango de velocidad alrededor de 0.
* **Cuándo publica**:

  * *Mientras se arrastra*: al ritmo que elijas (1–50 Hz, 10 por defecto, también con el dedo
    quieto) y una vez más al soltar. Lo adecuado para velocidades, que la mayoría de robots paran si
    dejan de recibirlas.
  * *Solo al soltar*: un único mensaje con el valor final. Lo adecuado para consignas.

* **Volver al valor inicial al soltar** (solo mientras se arrastra): al soltar se publica el valor
  inicial, así que con 0 el robot se para. También pasa si sales de la pestaña mientras arrastras.
* Un color para la barra y el control.

Toca el número grande (o *Escribir valor exacto* en el menú) para escribir un valor: se ajusta al
rango y al paso y se publica una vez. El menú ⋮ de cada slider tiene además *Editar*, *Duplicar*,
*Mover antes*, *Mover después* y *Eliminar*. En el PC, el slider se arrastra con el ratón.

La pestaña nunca publica mientras está oculta, y los sliders también se pueden añadir a un
:doc:`dashboard`. El modo demo trae una pestaña *Sliders* que mueve el robot simulado
con *Avanzar* y *Girar*.

Tipos de mensaje
----------------

Sirve cualquier tipo de mensaje incluido en la app (la misma lista que en :doc:`buttons`), y
:ref:`tus propios tipos <custom-types>`.

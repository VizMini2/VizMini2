Árbol de comportamiento
=======================

.. clip:: behavior_tree

Mira un árbol de comportamiento en directo, como en el modo monitor de Groot2: qué nodo se está
ejecutando, qué salió bien y qué falló. El árbol se dibuja de arriba abajo. Cada nodo es una caja
con una franja del color de su categoría (control, decorador, acción, condición, subárbol), su
nombre y su tipo. El borde indica su estado:

* **naranja**: ejecutándose;
* **verde**: éxito;
* **rojo**: fallo;
* verde o rojo atenuado: el nodo ha vuelto a estar en espera, y así terminó la última vez. Se ve
  qué rama acaba de ejecutarse aunque el árbol ya haya vuelto a empezar.

Arrastra para moverte, pellizca para hacer zoom y toca dos veces (o el botón de encuadrar) para ver
el árbol entero. Toca un nodo para ver su tipo, su estado y sus puertos (los atributos del XML).
Toca un **SubTree** para plegarlo o desplegarlo. El botón del libro muestra en su lugar los últimos
cambios de estado, del más reciente al más antiguo, y el de la **pizarra** los valores de las
pizarras (blackboards) del árbol (solo con Groot2), dos veces por segundo.

En el PC, arrastra con el ratón para moverte, haz zoom con la rueda y doble clic para encuadrar el
árbol.

**Breakpoints** (solo con Groot2): elige un nodo y toca **Breakpoint aquí**. Cuando el árbol va a
ejecutar ese nodo se para (el nodo sale con un borde rojo discontinuo y arriba se dice dónde está
parado) hasta que tocas **Continuar**; en la siguiente vuelta se vuelve a parar ahí, hasta que quitas
el breakpoint. Mientras haya breakpoints, los cambios de estado se deducen de los estados en vez de
grabarse. Al salir de la pestaña o cambiar de origen el árbol sigue y se quitan, así que el robot
nunca se queda parado.

Toca el origen, arriba, para elegir de dónde sale el árbol.

Groot2 (BehaviorTree.CPP 4)
---------------------------

El programa del robot tiene que crear un ``BT::Groot2Publisher`` para su árbol, el mismo que usa
Groot2:

.. code-block:: cpp

   auto tree = factory.createTree("MainTree");
   BT::Groot2Publisher publisher(tree, 1667);  // 1667 es el puerto por defecto

Escribe la IP del robot (o ``IP:puerto`` si no es el 1667). La primera vez se propone la dirección
de los ajustes de conexión: la del bridge o el primer peer de descubrimiento. La pestaña pide una
vez el árbol entero, con sus subárboles, y luego el estado de todos los nodos diez veces por
segundo. Si el programa carga otro árbol, se vuelve a pedir. Si se corta la conexión, la pestaña
sigue reintentando.

Es una conexión TCP directa (ZeroMQ) con el robot, no ROS: no usa el dominio ni el bridge, así que
el móvil o el PC tiene que llegar a ese puerto. Groot2Publisher atiende a un solo visor a la vez: cierra
Groot2 si está conectado.

Topic de log de Nav2
--------------------

El ``bt_navigator`` de Nav2 no lleva Groot2Publisher, pero publica cada cambio de estado en
``/behavior_tree_log`` (``nav2_msgs/BehaviorTreeLog``). Con este origen la pestaña se suscribe a
ese topic (por DDS o por el bridge, como cualquier otro). El log no trae la forma del árbol, así que
los nodos se listan en una columna según aparecen, en el orden del árbol, cada uno con su estado.

Para ver el árbol de verdad, **carga su XML** en el mismo diálogo (el ``.xml`` que ejecuta
bt_navigator, como ``navigate_to_pose_w_replanning_and_recovery.xml``; en el PC, con el diálogo de
archivos del sistema): se dibuja como con Groot2 y cada cambio del log ilumina su nodo. En **Humble** marca la opción: su log no lleva id de nodo, así
que cada cambio va al primer nodo con ese nombre.

PlanSys2
========

.. clip:: plansys2

Sigue un plan de `PlanSys2 <https://plansys2.github.io/>`_ mientras se ejecuta, como su monitor de
terminal (``plansys2_tui``), en el móvil o en el PC.

* **Resumen:** los cuatro recuadros del monitor juntos. Pulsa el título de uno para abrirlo solo.
* **Plan:** el plan en ejecución, en orden, cada acción con su estado (pendiente, ejecutando, hecha,
  fallida, cancelada), su avance, su inicio y duración previstos y lo que dice quien la ejecuta
  (``executing_plan`` y ``action_execution_info``).
* **Acciones:** las acciones en curso, el nodo que hace cada una, su último mensaje en
  ``actions_hub`` (petición, feedback, fin…) y cuánto lleva.
* **Performers:** cada action performer con su estado (listo, ejecutando, fallo) y su acción
  (``performers_status``).
* **Conocimiento:** lo que sabe el problem expert (``problem_expert/knowledge``): instancias,
  predicados, funciones con sus valores, el objetivo con cada uno de sus predicados marcado si ya se
  cumple, la métrica y las restricciones. En esta vista un filtro encuentra un predicado o un objeto.
* **Registro del plan:** lo que hace el ejecutor mientras corre el plan, y por qué, una línea por
  evento (``executor/execution_trace``): cada acción que empieza y termina a tiempo, con retraso o
  adelantada, el plan que se desplaza, los fallos y los replanes. *Seguir* mantiene a la vista la
  última línea; *Borrar* lo vacía.

Solo escucha topics, así que no cambia nada en el robot. Si los nodos de PlanSys2 están en un
namespace, escríbelo en *Namespace*. Entiende los mensajes de PlanSys2 rolling y los de las
versiones publicadas (Humble, Jazzy); el registro del plan necesita un PlanSys2 que publique la traza
de ejecución.

El robot de la demo tiene su propio PlanSys2: un robot de limpieza que limpia habitaciones y vuelve a
cargar, un plan tras otro.

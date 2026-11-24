Contribuir
==========

Los avisos de fallos, las ideas y los pull requests son bienvenidos.

* **Licencia.** El código está bajo la Apache License 2.0 (``LICENSE`` y ``NOTICE``), igual que
  cada contribución (sección 5 de la licencia): no hay que firmar ningún acuerdo aparte. El nombre
  "VizMini2" y su logo no están cubiertos por la licencia: los forks deben usar los suyos.
* **Cabeceras.** Los ficheros nuevos empiezan con la misma cabecera de copyright y
  ``SPDX-License-Identifier: Apache-2.0`` que los demás (``tools/license_headers.py --check`` lo
  comprueba).
* **Idioma.** Código y comentarios en inglés. Los textos que ve el usuario van en
  ``app/src/main/res/values*/strings*.xml``, en inglés y en español; la app de escritorio lee los
  mismos ficheros.
* **Commits.** En inglés, ``Área: descripción`` (por ejemplo ``RViz: resizable image panels``).
* **Tests.** Añade un test junto a lo que cambies (:doc:`building`); la CI los pasa en cada pull
  request.


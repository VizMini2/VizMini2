Contribuir
==========

Los avisos de fallos, las ideas y los pull requests son bienvenidos.

* **Licencia.** El código está bajo la GNU GPL versión 3, con un permiso adicional para combinarlo
  con Google Play Services y Firebase (``LICENSE`` y ``NOTICE``). El nombre "VizMini2" y su logo
  no están cubiertos por la licencia: los forks deben usar los suyos.
* **Acuerdo de contribución.** Cada contribución se acepta bajo ``CLA.md``: añade la línea que pide
  a tu primer pull request.
* **Cabeceras.** Los ficheros nuevos empiezan con la misma cabecera de copyright y
  ``SPDX-License-Identifier: GPL-3.0-only`` que los demás (``tools/license_headers.py`` lo
  comprueba).
* **Idioma.** Código y comentarios en inglés. Los textos que ve el usuario van en
  ``app/src/main/res/values*/strings*.xml``, en inglés y en español; la app de escritorio lee los
  mismos ficheros.
* **Commits.** En inglés, ``Área: descripción`` (por ejemplo ``RViz: resizable image panels``).
* **Tests.** Añade un test junto a lo que cambies (:doc:`building`); la CI los pasa en cada pull
  request.

Si necesitas VizMini2 con otras condiciones (por ejemplo dentro de un producto cerrado), mira
:doc:`../help/contact`.

Versiones y CI/CD
=================

Integración continua
--------------------

Cada push y pull request pasa ``.github/workflows/ci.yml``: las comprobaciones de licencia y
comentarios, los tests unitarios y el lint, las compilaciones de Android y de escritorio, la
comprobación de glibc de las librerías nativas, la app de escritorio arrancada en Ubuntu 20.04,
22.04 y 24.04 limpios, este manual, los tests de extremo a extremo del escritorio contra servidores
ROS 2 de Humble, Jazzy y Lyrical (cada uno en su Ubuntu) y los tests de dispositivo en un emulador
Android.

Publicar una versión
--------------------

1. Pon la misma versión en ``app/build.gradle.kts`` (``versionName``, y un ``versionCode`` mayor),
   ``gradle.properties`` (``vizmini2.version``) y ``ros/vizmini2/package.xml``.
2. Añade su entrada a los dos historiales de cambios (``app/src/main/assets/CHANGELOG.md`` y
   ``CHANGELOG.es.md``): la app los enseña en *Historial de cambios* y este manual en :doc:`../changelog`.
3. ``python3 tools/release/version.py check v1.2.3`` comprueba que todo cuadra.
4. Etiqueta y sube: ``git tag v1.2.3 && git push origin v1.2.3``.

``.github/workflows/release.yml`` compila entonces todos los ficheros, los comprueba y deja un
**borrador** de versión en GitHub para revisarlo y publicarlo a mano:

* los APK (uno por arquitectura y el universal), firmados con la clave
  de los secretos del repositorio ``ANDROID_KEYSTORE_BASE64``, ``ANDROID_KEYSTORE_PASSWORD``,
  ``ANDROID_KEY_ALIAS`` y ``ANDROID_KEY_PASSWORD``;
* el ``.deb`` y un ``.tar.gz`` de la app de escritorio, después de instalar el ``.deb`` con apt en
  Ubuntu 20.04 y 24.04 limpios y arrancarla;
* este manual y la referencia de la API en zips, ``SHA256SUMS`` y la entrada del historial como
  notas de la versión.

No se sube nada a Google Play ni a Firebase. El bundle de Google Play (``.aab``, firmado con la
misma clave) no va en la versión, porque nadie puede instalarlo: descárgalo del artefacto
``android-bundle`` de la ejecución (30 días) y súbelo a mano. Los mappings de R8 se guardan en el
artefacto ``android-mapping`` (90 días). El workflow también se puede lanzar a mano desde la pestaña
*Actions* para probarlo (sin claves genera ficheros ``-debugsigned``).

La web
------

Cuando publicas esa versión, ``.github/workflows/site.yml`` compila este manual y la referencia de
la API desde su etiqueta y los sube al repositorio
`VizMini2/VizMini2.github.io <https://github.com/VizMini2/VizMini2.github.io>`_, que GitHub Pages
sirve en https://vizmini2.github.io (el manual en la raíz, el español en ``es/`` y la referencia de
la API en ``api/``). También se puede lanzar a mano, desde cualquier rama o etiqueta, para corregir
la web entre versiones; *dry run* solo la compila y enseña qué cambiaría.

Necesita el secreto ``SITE_DEPLOY_KEY``: crea una clave con ``ssh-keygen -t ed25519 -N "" -f
site_key``, añade ``site_key.pub`` en *Settings › Deploy keys* del repositorio de la web con *Allow
write access*, y ``site_key`` en *Settings › Secrets and variables › Actions* de este repositorio.

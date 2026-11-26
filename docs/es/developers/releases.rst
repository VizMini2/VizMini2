Versiones y CI/CD
=================

Integración continua
--------------------

Los push a ``main`` y ``develop`` y cada pull request pasan ``.github/workflows/ci.yml`` (no cuando
solo tocan ficheros ``*.md``, ``LICENSE``, ``NOTICE`` o las plantillas de GitHub): las
comprobaciones de licencia y comentarios, los tests unitarios y el lint, las compilaciones de
Android y de escritorio, la comprobación de glibc de las librerías nativas, la app de escritorio
arrancada en Ubuntu 20.04, 22.04 y 24.04 limpios, este manual, los tests de extremo a extremo del
escritorio contra servidores ROS 2 de Humble, Jazzy y Lyrical (cada uno en su Ubuntu) y los tests de
dispositivo en un emulador Android.

Los pull requests también pasan ``desktop-windows.yml`` (que también se puede lanzar a mano): los
tests de escritorio en Windows, el ``.msi`` y la app arrancada (``--check``, y la vista 3D con el
OpenGL por software de Mesa, porque los runners no tienen GPU). ``windows-natives.yml`` compila las
librerías nativas de Windows (el JNI de Filament y Fast DDS, con MSVC) cuando cambian sus scripts,
o a mano.

Publicar una versión
--------------------

1. Pon la misma versión en ``app/build.gradle.kts`` (``versionName``, y un ``versionCode`` mayor),
   ``gradle.properties`` (``vizmini2.version``) y ``package.xml``.
2. Añade su entrada a los dos historiales de cambios (``app/src/main/assets/CHANGELOG.md`` y
   ``CHANGELOG.es.md``): la app los enseña en *Historial de cambios* y este manual en :doc:`../changelog`.
   Después, ``python3 tools/release/version.py rst > CHANGELOG.rst``: el historial del paquete de
   ROS 2, hecho a partir del inglés.
3. ``python3 tools/release/version.py check v1.2.3`` comprueba que todo cuadra. Pon también como
   fecha de las entradas del historial el día de la versión (eso no se comprueba).
4. Si has recompilado librerías nativas, publícalas antes (``tools/prebuilt/publish.sh``) y sube a
   git ``tools/prebuilt/prebuilt.txt``: la versión se compila con los ficheros que lista.
5. Arráncala con ``tools/release/start.sh 1.2.3`` (desde ``main``, al día y sin nada sin
   commit): vuelve a comprobar las versiones, crea el **borrador** de versión en GitHub a tu nombre
   (título y notas de los changelogs) y crea y sube la etiqueta ``v1.2.3``. Un simple
   ``git tag v1.2.3 && git push origin v1.2.3`` también vale, pero entonces el workflow crea el
   borrador por su cuenta y la versión aparece como de *github-actions*, no tuya (ni en el feed de
   quien te sigue).

``.github/workflows/release.yml`` compila entonces todos los ficheros, los comprueba y los sube a
ese **borrador** de versión en GitHub, para revisarlo y publicarlo a mano:

* los APK (uno por arquitectura y el universal), firmados con la clave
  de los secretos del repositorio ``ANDROID_KEYSTORE_BASE64``, ``ANDROID_KEYSTORE_PASSWORD``,
  ``ANDROID_KEY_ALIAS`` y ``ANDROID_KEY_PASSWORD``;
* el ``.deb`` y un ``.tar.gz`` de la app de escritorio, después de instalar el ``.deb`` con apt en
  Ubuntu 20.04, 22.04 y 24.04 limpios y arrancarla (``--check`` y una captura del 3D);
* el ``.msi`` y el ``.zip`` de Windows, compilados en Windows; el ``.msi`` se instala para el usuario
  actual, se arranca (``--check``, y la vista 3D con el OpenGL por software de Mesa) y se
  desinstala;
* este manual y la referencia de la API en zips, ``SHA256SUMS`` y la entrada del historial como
  notas de la versión.

No se sube nada a Google Play ni a Firebase. El bundle de Google Play (``.aab``, firmado con la
misma clave) no va en la versión, porque nadie puede instalarlo: descárgalo del artefacto
``android-bundle`` de la ejecución (30 días) y súbelo a mano. Los mappings de R8 se guardan en el
artefacto ``android-mapping`` (90 días). El workflow también se puede lanzar a mano desde la pestaña
*Actions* para probarlo (sin claves genera ficheros ``-debugsigned``).

El repositorio apt
------------------

Cuando publicas una versión ``v*``, ``.github/workflows/apt.yml`` añade su ``.deb`` al repositorio
apt de VizMini2 y lo vuelve a firmar (``tools/apt/update-repo.sh``). El repositorio es la release
*apt* de GitHub (una prerelease, nunca la última): sus ficheros son los ``.deb``, el índice
``Packages``, el ``InRelease`` firmado y la clave pública. Necesita el secreto ``APT_SIGNING_KEY``,
la clave privada de firma (``gpg --armor --export-secret-keys <id>``, sin contraseña); su mitad
pública es ``desktopApp/packaging/linux/vizmini2-archive-keyring.gpg``, que lleva el ``.deb`` para
añadir el repositorio la primera vez que se instala. A mano desde la pestaña *Actions*, añade el
``.deb`` de la etiqueta que le digas.

La web
------

Cuando publicas esa versión (una etiqueta ``v*``: publicar la release *prebuilt* de los binarios
nativos no despliega nada), ``.github/workflows/site.yml`` compila este manual y la referencia de
la API desde su etiqueta y los sube al repositorio
`VizMini2/VizMini2.github.io <https://github.com/VizMini2/VizMini2.github.io>`_, que GitHub Pages
sirve en https://vizmini2.github.io (el manual en la raíz, el español en ``es/`` y la referencia de
la API en ``api/``). También se puede lanzar a mano, desde cualquier rama o etiqueta, para corregir
la web entre versiones; *dry run* solo la compila y enseña qué cambiaría.

Necesita el secreto ``SITE_DEPLOY_KEY``: crea una clave con ``ssh-keygen -t ed25519 -N "" -f
site_key``, añade ``site_key.pub`` en *Settings › Deploy keys* del repositorio de la web con *Allow
write access*, y ``site_key`` en *Settings › Secrets and variables › Actions* de este repositorio.

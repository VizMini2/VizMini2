Compilar y probar
=================

Necesitas un JDK 21, el SDK de Android (para la app Android) y, para los tests contra ROS, una
instalación de ROS 2 en el PC. Ningún script necesita sudo ni Docker.

Compilar
--------

.. code-block:: bash

   ./gradlew installDirectDebug             # la app Android en un móvil conectado (debug)
   ./gradlew :desktopApp:run                # la app de escritorio desde el código
   ./gradlew :desktopApp:packageDeb         # el .deb (desktopApp/build/compose/binaries/main/deb)
   ./gradlew dokkaGenerate                  # referencia de la API en build/dokka/html
   docs/build.sh                            # este manual en docs/_build/site

La app Android tiene dos variantes: *direct* (ficheros APK) y *play* (Google Play). Las
compilaciones release necesitan una clave de firma en ``keystore.properties`` (mira
``keystore.properties.example``).

Las librerías nativas de escritorio vienen ya compiladas en el repositorio. Para recompilarlas
(tras cambiar Fast DDS o Filament), ``tools/compat/build-desktop-natives.sh`` lo hace dentro de un
Ubuntu 20.04, y ``tools/compat/check-glibc.sh`` comprueba que siguen funcionando en 20.04.

Tests
-----

.. list-table::
   :header-rows: 1
   :widths: 45 55

   * - Comando
     - Qué prueba
   * - ``./gradlew :core:jvmTest testDirectDebugUnitTest lintDirectDebug``
     - Tests unitarios de la lógica compartida y de la app Android, y el lint de Android.
   * - ``tools/e2e/run.sh``
     - La app de escritorio de extremo a extremo contra servidores ROS 2 reales en este PC (robot
       falso, Nav2, MoveIt, ros2_control, vídeo, mapas, interactive markers…): los arranca, pasa
       los tests y los para.
   * - ``tools/e2e/android_device_tests.sh``
     - Tests en un móvil o un emulador con el robot de demo.
   * - ``tools/pc_tests.sh -b``
     - El móvil contra los servidores del PC (misma red). Guarda y restaura las pestañas del
       móvil.
   * - ``tools/compat/try-desktop.sh 22.04 [fichero.deb]``
     - La app (o instalar el ``.deb``) en otra versión de Ubuntu.
   * - ``python3 tools/license_headers.py`` y ``python3 tools/check_english_comments.py``
     - Que cada fichero tiene la cabecera de licencia y comentarios en inglés.

Los servidores de prueba de ``tools/`` también vienen bien a mano: ``python3 tools/fake_robot.py``
publica casi todos los tipos de mensaje que enseña la app, y ``vizmini2 --showcase`` añade un
dashboard pensado para él. Para el display NavMap, ``tools/navmap_demo.py`` publica un edificio
pequeño de dos plantas con actualizaciones de capas en directo y ``tools/navmap_file_pub.py``
cualquier fichero ``.navmap`` (los playgrounds de EasyNavigation tienen mapas reales, como un parking
de varias plantas); ábrelos con ``vizmini2 --import tools/navmap_demo.rviz``. Los dos necesitan
``navmap_ros_interfaces``: ``source "$(tools/e2e/build_msgs.sh)"`` compila una copia.

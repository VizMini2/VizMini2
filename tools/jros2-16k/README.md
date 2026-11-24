# jros2-android 1.5.1 con páginas de 16 KB

El AAR oficial de jros2-android 1.5.1 trae sus librerías nativas (Fast-DDS, Fast-CDR y el JNI)
alineadas a 4 KB. En dispositivos con páginas de 16 KB (Android 15+) la app no puede cargarlas, y
Google Play exige compatibilidad con 16 KB.

`build.sh` recompila esas librerías desde el fork [VizMini2/jros2](https://github.com/VizMini2/jros2)
(rama `develop`: el `0c41e6f` de ihmcrobotics/jros2 más cambios propios) y
publica el resultado como `us.ihmc:jros2-android:1.5.1-16k-graph` en `repo/`, que es lo que usa la
app. Se sustituyen las `.so` del AAR oficial y las clases Java que cambia el fork.

El commit del fork añade:

- el parche de `scripts/cppbuild.bash` para `-z max-page-size=16384`;
- un `DomainParticipantListener` en cada participante que guarda los endpoints y participantes
  descubiertos (con sus nombres e IPs), para listar topics, servicios y acciones como
  `ros2 topic/service/action list`;
- la identidad DDS de cada muestra escrita y la `related_sample_identity` de las recibidas, para
  emparejar cada respuesta de servicio con su petición; la `related_sample_identity` de la siguiente
  muestra que escribe un escritor (las respuestas de los servidores de servicios llevan la de su
  petición, y las peticiones el GUID del lector de respuestas del cliente, como `rmw_fastrtps`), si un
  escritor está emparejado con un lector y el GUID de un lector, para que un servidor espere a que el
  lector del cliente esté emparejado antes de responder;
- peers unicast de descubrimiento (como `ROS_STATIC_PEERS`) y Discovery Server (como
  `ROS_DISCOVERY_SERVER`, en modo SUPER_CLIENT).

y además, en las clases Java, cambia las APIs de Java que Android no tiene en `minSdk` 26. La más
grave: `FloatBuffer.put(int, FloatBuffer, int, int)` y sus parecidas son de la API 35, y sin ellas
las secuencias de números (`float32[]`, `uint8[]`…) no podían crecer, así que en Android 8–14 no
llegaban ni `LaserScan`, ni `PointCloud2`, ni los mapas. Esas clases se compilan contra las clases de
Android (como hace AGP; contra un JDK, `FloatBuffer.position(int)` devolvería `FloatBuffer`, que
Android no tiene) y sustituyen a las del `classes.jar` oficial. Después, `check_android_api.py`
revisa todas las llamadas a `java.*` del AAR con el `api-versions.xml` del SDK y para el script si
alguna no existe en Android 8.

Las `.so` siempre se compilan desde el código (las que hay guardadas en `fab8158` no se corresponden
con él: les falta `participantGetDiscoveredParticipantAddresses`). Al terminar, el
script comprueba que cada método nativo de `fastddsjavaGraph` está en `libjnifastddsjava.so`.

Sus funciones JNI están en la clase `us.ihmc.fastddsjava.natives.fastddsjavaGraph`, que no está en el
AAR oficial: la app lleva su propia copia (`app/src/main/java/us/ihmc/fastddsjava/natives/`).

Requisitos: NDK r28 o posterior, una plataforma de Android del SDK (`platforms;android-36`), JDK 17
o posterior, python3, cmake, ninja, git, curl, readelf, nm y zip. Con `JROS2_REPO=/ruta/al/fork` y
`JROS2_COMMIT=<commit>` se compila desde una copia local o un commit concreto (el script fija
`fab8158`, al que aún le falta el parche Java: hasta subirlo, la comprobación de APIs lo para).

```bash
ANDROID_NDK=/ruta/al/ndk-r28 tools/jros2-16k/build.sh            # las 3 ABIs
ANDROID_NDK=/ruta/al/ndk-r28 tools/jros2-16k/build.sh arm64-v8a  # solo una
```

Cuando IHMC publique una versión ya alineada a 16 KB, basta con cambiar la dependencia en
`app/build.gradle.kts` y borrar esta carpeta.

## Desktop natives

The Linux x86-64 libraries in `desktop/jros2-desktop.jar` are built inside Ubuntu 20.04 (so they run on 20.04 and
newer), with GCC 9's libstdc++ linked in statically: `tools/compat/build-desktop-natives.sh fastdds`. Do not
replace them with ones built on a newer system (`tools/compat/check-glibc.sh` and the CI check it).

## Where the built files go

`repo/.../jros2-android-*.aar` and `desktop/jros2-desktop.jar` are not kept in git: the build downloads them from the
repository's "prebuilt" release (`tools/prebuilt/prebuilt.txt`). After rebuilding them, `tools/prebuilt/publish.sh`
uploads them and updates `prebuilt.txt`; commit that file. `classes/jros2-classes.jar` (small) stays in git.

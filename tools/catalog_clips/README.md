# Vídeos del catálogo "+"

Cada tipo de pestaña tiene un vídeo corto en bucle en su ficha (`res/raw/clip_*.mp4` en inglés y
`res/raw-es/` en español), grabado con el robot de la demo.

1. Instala la app y los tests en el móvil (con la sesión iniciada): `./gradlew installDirectDebug installDirectDebugAndroidTest`.
   En Xiaomi/HyperOS hace falta "Depuración USB (Ajustes de seguridad)" y reiniciar el móvil tras activarla.
2. `tools/catalog_clips/record.sh` graba los 16 en los dos idiomas (unos 8 min; no toques el móvil).
   Para repetir algunos: `record.sh camera,nodes`. El del árbol de comportamiento necesita
   `tools/bt_groot/bt_groot_server` en marcha en el PC y su IP: `PC_IP=192.168.1.10 record.sh behavior_tree`.
3. Revisa las grabaciones de `build/catalog_clips/` (que no salga ninguna notificación encima).
4. `tools/catalog_clips/encode.sh` (o `encode.sh camera nodes`) las recorta con el encuadre de `crops.txt`.

El test `CatalogClips` (androidTest) abre cada pestaña, hace los gestos y graba con `screenrecord`;
deja las pestañas, la conexión y el estado de la demo como estaban.

El de MoveIt no sale de `CatalogClips` (necesita MoveIt en el PC): con `ros2 launch tools/moveit_panda_headless.launch.py`
y `python3 tools/mesh_server.py --port 8000` en marcha en el dominio 77, `adb shell am instrument -w -e class
com.juancams.rviz2mini.MoveItPcTest#clip -e clips true -e pc true -e lang en|es -e peers <IP del PC> -e pcIp <IP del PC>
com.juancams.vizmini2.test/com.juancams.rviz2mini.TestRunner`, `adb pull /sdcard/clips/<lang>/clip_moveit.mp4
build/catalog_clips/<lang>/` y `encode.sh moveit`.

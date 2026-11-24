Cámara
======

.. clip:: camera

Imagen en directo de los topics de cámara, hasta cuatro a la vez.

* Funciona con ``sensor_msgs/Image`` y ``sensor_msgs/CompressedImage`` (JPEG/PNG), con
  ``compressedDepth`` para los topics cuyo nombre contiene *depth*, y con **vídeo H.264 / H.265**:
  ``foxglove_msgs/CompressedVideo`` (``.../foxglove``) y ``ffmpeg_image_transport_msgs/FFMPEGPacket``
  (``.../ffmpeg``), que gastan una fracción de la WiFi que necesita una cámara en JPEG. El móvil los
  decodifica por hardware; el PC, con ``ffmpeg`` (Linux: ``sudo apt install ffmpeg``; Windows:
  ``winget install ffmpeg``), y sin él la imagen dice cómo conseguirlo. Los displays *Image* y
  *Camera* del RViz también los aceptan.
* Codificaciones sin comprimir: ``rgb8``, ``bgr8``, ``rgba8``, ``bgra8``, ``mono8``, ``mono16``,
  ``8UC1/3/4``, ``16UC1``, ``32FC1``, ``yuv422`` (``uyvy``) y ``yuyv``.
* **Buscar** encuentra los topics de imagen de la red (también lo hace solo al conectarse ROS 2). Si
  el tuyo no sale, escribe su nombre al final de la lista y pulsa **Añadir**.
* Toca topics de la lista para añadirlos o quitarlos. Una cámara ocupa toda la pestaña, dos van una
  encima de otra y tres o cuatro se reparten en una rejilla de 2×2.
* Encima de cada imagen se ven el topic, la resolución, la codificación y los fps.
* **Anotaciones**: si hay un topic ``visualization_msgs/ImageMarker`` o
  ``foxglove_msgs/ImageAnnotations`` junto a la imagen (en su namespace), sus cajas, círculos, líneas
  y textos se dibujan solos sobre la imagen. El botón del ojo de la imagen los enseña u oculta.

Al crear la pestaña puedes poner ya el topic (en el móvil, el campo sugiere los topics de imagen que
encuentra). Cada cámara se suscribe con *Best effort* y guarda solo la última imagen, así que nunca se
queda atrás.

En un widget de :doc:`dashboard` las imágenes ocupan todo el widget, y el botón de arriba a la
derecha abre la lista de topics.

.. container:: vm-clear

   Consejo: pon varias cámaras juntas en un :doc:`dashboard`.

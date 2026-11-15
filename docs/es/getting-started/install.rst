Instalación
===========

VizMini2 funciona en **móviles y tablets Android** (Android 8.0 o superior) y en **PCs con Linux**
(Ubuntu 20.04 o posterior). Esta página trata del móvil; para el PC mira :doc:`desktop`.

En el móvil
-----------

VizMini2 todavía no está en Google Play: se instala desde un fichero APK.

1. Abre en el móvil la `página de versiones <releases page_>`_ del proyecto y descarga
   ``VizMini2-<versión>-universal.apk`` (sirve para cualquier móvil; el ``arm64-v8a`` es más
   pequeño y vale para casi todos los móviles recientes).
2. Ábrelo. Si Android lo pide, permite instalar apps desde ese origen (el navegador o el gestor de
   archivos).
3. Abre **VizMini2**. Se abre en Inicio, sin necesidad de cuenta; la primera vez, un recorrido
   rápido te enseña lo básico.

.. shot:: phone_home
   :caption: Inicio, aquí con el robot de demo.

.. note::
   El móvil y el robot tienen que estar en la **misma red** (o alcanzarse por una VPN o un
   Discovery Server) para que la app vea el robot. La demo funciona en cualquier sitio: nunca sale
   del móvil.

La app pide poco: permiso para mostrar notificaciones (alertas y la conexión en segundo plano) y,
cada vez que grabas la pantalla, el aviso de captura de pantalla de Android.

.. raw:: html

   <div class="vm-clear"></div>

En el PC
--------

La app de escritorio tiene las mismas pestañas: el |deb| de la misma
`página de versiones <releases page_>`_ (``sudo apt install ./vizmini2_*_amd64.deb``) o
``ros2 run vizmini2 vizmini2`` desde el código fuente. Todos los detalles están en :doc:`desktop`.

Actualizar
----------

Descarga el APK (o el ``.deb``) más reciente e instálalo encima del que tienes: se conservan tus
pestañas, tus perfiles y tus ajustes. El :doc:`../changelog` recoge qué cambia en cada versión.

Siguiente: :doc:`demo` o :doc:`connect`. La :doc:`cuenta <account>` es opcional.

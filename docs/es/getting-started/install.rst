Instalación
===========

VizMini2 funciona en **móviles y tablets Android** (Android 8.0 o superior) y en **PCs** con Linux
(Ubuntu 20.04 o posterior) o Windows (10 u 11). Esta página trata del móvil; para el PC mira
:doc:`desktop`.

En el móvil
-----------

.. shot:: phone_home
   :caption: Inicio, aquí con el robot de demo.

La misma app, de dos formas:

* **Desde Google Play** (lo más fácil): instala VizMini2 desde `Google Play`_. Se actualiza sola.
* **Como APK**, desde la `página de versiones <releases page_>`_ del proyecto: para móviles sin
  Google Play, o para tener una versión antes de que llegue a la tienda.

  1. En el móvil, descarga ``VizMini2-<versión>-universal.apk`` (sirve para cualquier móvil; el
     ``arm64-v8a`` es más pequeño y vale para casi todos los móviles recientes).
  2. Ábrelo. Si Android lo pide, permite instalar apps desde ese origen (el navegador o el gestor
     de archivos).

Elige una de las dos y quédate con ella: cada una se actualiza desde donde vino (mira *Actualizar*
más abajo).

Abre **VizMini2**. Se abre en Inicio, sin necesidad de cuenta; la primera vez, un recorrido rápido
te enseña lo básico.

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

La app de escritorio tiene las mismas pestañas. En Ubuntu, con apt desde el repositorio de VizMini2
(tres comandos, en :doc:`desktop`), o de la misma `página de versiones <releases page_>`_ el |deb|
(``sudo apt install ./vizmini2_*_amd64.deb``) o el ``.tar.gz``; en Windows el ``.msi`` (para tu usuario, sin necesidad de administrador) o el
``.zip``. En Linux también se puede compilar desde el código fuente y ejecutar con
``ros2 run vizmini2 vizmini2``. Todos los detalles están en :doc:`desktop`.

Actualizar
----------

Descarga el APK (o el ``.deb`` o el ``.msi``) más reciente e instálalo encima del que tienes: se
conservan tus pestañas, tus perfiles y tus ajustes. En Ubuntu con el repositorio apt de VizMini2,
lo hace ``sudo apt upgrade``. Con el ``.tar.gz`` o el ``.zip``, sustituye la
carpeta. El :doc:`../changelog` recoge qué cambia en cada versión.

.. note::
   Si la instalaste desde **Google Play**, actualízala ahí: Google Play firma la app con su propia
   clave, así que un APK de la página de versiones no se puede instalar encima (ni al revés). Para
   pasar de uno a otro, desinstala antes; exporta tus pestañas o guarda un
   :doc:`perfil <../config/profiles>` para llevártelas.

Siguiente: :doc:`demo` o :doc:`connect`. La :doc:`cuenta <account>` es opcional.

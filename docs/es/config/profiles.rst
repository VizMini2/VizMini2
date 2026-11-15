Perfiles de robot
=================

Un perfil guarda **una conexión y un conjunto de pestañas**: dominio, nombre del nodo, peers,
Discovery Server, foxglove_bridge, router de Zenoh, interfaces, formato de servicios y cada pestaña
con sus ajustes. Cambia de robot con un toque.

* **Guardar como…** guarda la conexión y las pestañas actuales (nombres de hasta 32 caracteres).
* **Cargar…** se conecta con el perfil y sustituye tus pestañas. Desde la misma lista puedes
  **Eliminar** un perfil.

En la nube
----------

Los perfiles siempre se quedan en el dispositivo. Si vinculas una cuenta en Inicio
(:doc:`../getting-started/account`), además se sincronizan con ella, así que aparecen en todos tus
móviles y PC. Si el mismo perfil se cambió en dos dispositivos, gana el cambio más reciente.
**Sincronizar ahora** fuerza la sincronización.

Sin conexión a internet (por ejemplo, conectado solo a la Wi-Fi del robot) puedes seguir guardando y
borrando perfiles: se quedan en el móvil, la app te avisa y la tarjeta indica que hay cambios
pendientes. Se suben solos a la nube en cuanto vuelves a tener conexión.

Ficheros
--------

**Exportar…** guarda un perfil como fichero JSON para compartirlo; **Importar…** lee uno (un perfil
con el mismo nombre se sustituye).

Códigos QR
----------

**Compartir como QR** muestra un código de la configuración actual o de un perfil guardado, con el
*perfil completo* o *solo la conexión*. Un perfil completo que no cabe en un código se reparte en
varios que cambian solos: mantén la cámara del otro móvil sobre ellos. **Escanear QR** los lee con
el escáner de Google Play services: un código de solo conexión pregunta *¿Conectar a…?* y mantiene
tus pestañas.

En el PC
--------

**Configuración → Perfiles de robot** tiene **Guardar perfil**, **Cargar perfil** (con un QR y un
botón para eliminar junto a cada perfil), **QR** (la configuración actual, para escanearla con el
móvil; un perfil completo en varias partes tiene flechas para recorrerlas) e **Importar…**, que lee
el fichero JSON de un perfil o una imagen (PNG o JPEG) de su código QR. Los perfiles se sincronizan
con tu cuenta igual que en el móvil.

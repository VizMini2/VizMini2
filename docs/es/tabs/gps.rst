Mapa GPS
========

.. clip:: gps

Tu robot sobre un mapa de calles. La pestaña se suscribe a un
topic ``sensor_msgs/NavSatFix`` (por defecto ``/gps/fix``) y dibuja sobre los mapas de
`OpenStreetMap <https://www.openstreetmap.org/copyright>`_:

* la **posición** actual;
* el **círculo de precisión**, a partir de la covarianza del mensaje (si el driver la rellena);
* el **rastro**, con un punto cada 30 cm como mínimo.

La tarjeta de arriba enseña la latitud, la longitud, la altitud, la precisión, el tipo de fix (GPS,
SBAS o GBAS/RTK) y cuánto hace del último mensaje. Los mensajes sin fix (estado *NO_FIX*) o con
coordenadas no válidas (NaN o fuera de rango) no mueven nada; la tarjeta avisa cuando llegan mensajes
sin fix.

Toca el nombre del topic para cambiarlo: escríbelo o elige uno de los ``NavSatFix`` de la red que
aparecen en la lista. Al cambiarlo se borra el rastro. El botón de seguir centra el mapa en el robot;
al mover el mapa deja de seguirlo. Pellizca o toca dos veces para acercarte. La papelera borra el
rastro.

En el PC, arrastra el mapa con el ratón y haz zoom con la rueda o con doble clic.

Las teselas del mapa se descargan de OpenStreetMap (hace falta Internet) y se guardan en caché una
semana para no volver a pedirlas. El modo demo trae una pestaña con el robot simulado
paseando por la Puerta del Sol de Madrid.

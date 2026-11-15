# Reglas de R8 para la release: ofusca (renombra) el código de la app y reduce las librerías.
# Solo se conserva con su nombre lo que se busca por nombre en tiempo de ejecución: código nativo,
# vistas creadas desde los layouts XML y los enums que se guardan por su nombre.

# --- Ofuscación agresiva: menos pistas en un jadx/apktool ---
-repackageclasses ''        # todas las clases a un paquete raíz sin nombre
-allowaccessmodification     # deja a R8 cambiar visibilidades para ofuscar mejor

# jros2 / Fast DDS: JNI y reflexión (GraphAccess entra en sus campos privados; los mensajes se
# crean por reflexión a partir del nombre del tipo).
-keep class us.ihmc.** { *; }
-keepclassmembers class us.ihmc.** { native <methods>; }
-dontwarn us.ihmc.**

# Filament / gltfio / filamat: JNI llama a métodos y campos Java por nombre.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# zstd-jni
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Trazas legibles en Crashlytics (el plugin sube el mapping de R8 para desofuscarlas en la consola).
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# Dependencias opcionales que no están en Android.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Mensajes ROS (generados en paquetes como std_msgs.msg, geometry_msgs.msg...): jros2 lee su campo
# estático "name" y los instancia por reflexión; MessageIntrospection recorre sus campos.
-keep class * implements us.ihmc.jros2.ROS2Message { *; }
-keep class **.msg.** { *; }
-keep class **.srv.** { *; }
-keep class **.action.** { *; }

# JavaCPP (carga de las librerías nativas de Fast DDS por reflexión).
-keep class org.bytedeco.** { *; }
-dontwarn org.bytedeco.**

# Firebase / ML Kit: los registrars se crean por reflexión.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }

# --- Código de la app (com.juancams.rviz2mini): se ofusca, salvo lo que sigue ---

# Vistas personalizadas usadas en los layouts XML: el nombre de la clase está en el XML y el
# sistema las crea con el constructor (Context, AttributeSet). Se conservan ellas y ese constructor.
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Enums de la app: algunos se guardan por su nombre (p. ej. el tipo de pestaña en el JSON de perfiles,
# TabType.name). Si R8 renombrara las constantes, se romperían las pestañas y perfiles ya guardados.
-keepclassmembers enum com.juancams.rviz2mini.** { *; }

# WorkManager (lo trae AdMob): Room crea su base de datos por reflexión ("WorkDatabase_Impl"); con
# -repackageclasses se renombraría y la app se cerraría al arrancar.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.work.impl.** { *; }
-dontwarn androidx.work.**

# LuaJ (scripts de Plot): su motor JSR-223 (javax.script) no existe en Android ni se usa, y las
# funciones de sus librerías pueden crearse por reflexión.
-dontwarn javax.script.**
-keep class org.luaj.vm2.lib.** { *; }

# P2PRemoteFiles release keep rules.
# Keep only components whose class names are consumed by Android/Shizuku/libsu at runtime.

# Shizuku UserService is launched from a ComponentName/class name.
-keep class com.p2premotefiles.app.shizuku.PrivilegedFileService { *; }
-keep interface com.p2premotefiles.app.shizuku.IPrivilegedFileService { *; }
-keep class com.p2premotefiles.app.shizuku.IPrivilegedFileService$* { *; }

# libsu starts this class in a dedicated UID 0 process.
-keep class com.p2premotefiles.app.root.RootFileService { *; }

# Foreground service/application/activity are manifest entry points. R8 normally keeps
# manifest components, but explicit rules make the release boundary unambiguous.
-keep class com.p2premotefiles.app.RemoteFilesApplication { *; }
-keep class com.p2premotefiles.app.MainActivity { *; }
-keep class com.p2premotefiles.app.service.RemoteAccessService { *; }

# AIDL/Binder generated types cross process boundaries.
-keepclassmembers class * extends android.os.Binder { *; }

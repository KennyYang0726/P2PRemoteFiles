package com.p2premotefiles.app.shizuku;

import android.os.ParcelFileDescriptor;

interface IPrivilegedFileService {
    String listDirectory(String path) = 1;
    String stat(String path) = 2;
    boolean createDirectory(String path) = 3;
    boolean copy(String source, String destination) = 4;
    boolean move(String source, String destination) = 5;
    boolean rename(String source, String destination) = 6;
    boolean deleteRecursively(String path) = 7;
    ParcelFileDescriptor openRead(String path) = 8;
    ParcelFileDescriptor openWrite(String path, boolean append) = 9;
    int serviceUid() = 10;
    String permissions(String path) = 11;
    boolean setPermissions(String path, int mode, boolean applyToFiles, boolean applyToDirectories) = 12;
    String setPermissionsDetailed(String path, int mode, boolean applyToFiles, boolean applyToDirectories) = 13;
    void destroy() = 16777114;
}

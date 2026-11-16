package com.droidhost.launcher.core;

import java.io.File;
import java.io.IOException;

public final class ArchivePaths {
    private ArchivePaths() {}

    public static File inside(File destination, String name) throws IOException {
        String base = destination.getCanonicalPath();
        File file = new File(destination, name);
        // The resolved path decides whether the member stays inside: a link that points out of the
        // tree must never be followed on the host.
        String resolved = file.getCanonicalPath();
        if (!resolved.equals(base) && !resolved.startsWith(base + File.separator)) return null;
        // Return the name normalized lexically, not resolved. A member like "./usr/bin/sh" (GNU tar
        // -C dir .) must lose its "/./" segment so later permission writes land and the rootfs is not
        // locked; but the path must keep an existing symbolic link as the link itself, so a file
        // extracted over it replaces the link instead of writing through to its target.
        return file.toPath().normalize().toFile();
    }
}

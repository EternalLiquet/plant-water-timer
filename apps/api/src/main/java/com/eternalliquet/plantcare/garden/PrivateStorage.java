package com.eternalliquet.plantcare.garden;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

final class PrivateStorage {
  static void ensureDirectory(Path path) throws IOException {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
      throw new IllegalStateException(
          "This release requires POSIX private storage. Validate equivalent private ACLs before"
              + " porting it.");
    Files.createDirectories(
        path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    if (Files.isSymbolicLink(path)
        || Files.getPosixFilePermissions(path).stream()
            .anyMatch(
                permission ->
                    permission.name().startsWith("GROUP_")
                        || permission.name().startsWith("OTHERS_")))
      throw new IllegalStateException(
          "Garden data directories must be private to their owner (0700). Choose a private"
              + " location; existing permissions are not changed automatically.");
  }
}

package com.eternalliquet.plantcare.garden;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateStorageTests {
  @TempDir Path temporary;

  @Test
  void newDataDirectoryIsOwnerOnly() throws Exception {
    var path = temporary.resolve("private");
    PrivateStorage.ensureDirectory(path);
    assertThat(Files.getPosixFilePermissions(path))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
  }

  @Test
  void existingSharedDirectoryIsRejectedRatherThanChanged() throws Exception {
    var path =
        Files.createDirectory(
            temporary.resolve("shared"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
    assertThatThrownBy(() -> PrivateStorage.ensureDirectory(path))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Files.getPosixFilePermissions(path))
        .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}

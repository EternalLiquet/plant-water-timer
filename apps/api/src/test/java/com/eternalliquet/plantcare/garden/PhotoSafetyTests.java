package com.eternalliquet.plantcare.garden;

import static org.assertj.core.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PhotoSafetyTests {
  @Test
  void refusesFilesThatAreNotPictures() {
    assertThatThrownBy(() -> PhotoService.sanitize("<script>bad</script>".getBytes()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reencodesPngAsJpegWithoutCopyingMetadata() throws Exception {
    var input = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "png", input);
    byte[] output = PhotoService.sanitize(input.toByteArray());
    assertThat(output[0]).isEqualTo((byte) 0xff);
    assertThat(output[1]).isEqualTo((byte) 0xd8);
    assertThat(ImageIO.read(new java.io.ByteArrayInputStream(output)).getWidth()).isEqualTo(30);
  }

  @Test
  void rejectsEmptyAndOversizedFiles() {
    assertThatThrownBy(() -> PhotoService.sanitize(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PhotoService.sanitize(new byte[5 * 1024 * 1024 + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void phoneOrientationIsAppliedBeforeMetadataIsRemoved() throws Exception {
    var original = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "jpeg", original);
    byte[] jpeg = original.toByteArray();
    var tagged = new ByteArrayOutputStream();
    tagged.write(jpeg, 0, 2);
    // EXIF little-endian TIFF, one orientation field: rotate 90 degrees clockwise.
    byte[] exif = {
      69, 120, 105, 102, 0, 0, 73, 73, 42, 0, 8, 0, 0, 0, 1, 0, 18, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0,
      0, 0, 0, 0
    };
    tagged.write(new byte[] {(byte) 255, (byte) 225, 0, (byte) (exif.length + 2)});
    tagged.write(exif);
    tagged.write(jpeg, 2, jpeg.length - 2);
    var clean =
        ImageIO.read(new java.io.ByteArrayInputStream(PhotoService.sanitize(tagged.toByteArray())));
    assertThat(clean.getWidth()).isEqualTo(20);
    assertThat(clean.getHeight()).isEqualTo(30);
  }

  @Test
  void exifDescriptionCannotSurviveSanitization() throws Exception {
    var original = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "jpeg", original);
    byte[] jpeg = original.toByteArray();
    var tagged = new ByteArrayOutputStream();
    tagged.write(jpeg, 0, 2);
    byte[] marker =
        "Exif\0\0PRIVATE_GPS_LOCATION_AND_ORIGINAL_FILENAME.jpg"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    tagged.write(new byte[] {(byte) 255, (byte) 225, 0, (byte) (marker.length + 2)});
    tagged.write(marker);
    tagged.write(jpeg, 2, jpeg.length - 2);
    byte[] output = PhotoService.sanitize(tagged.toByteArray());
    assertThat(new String(output, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain("PRIVATE_GPS_LOCATION", "ORIGINAL_FILENAME");
    var metadata =
        com.drew.imaging.ImageMetadataReader.readMetadata(new java.io.ByteArrayInputStream(output));
    assertThat(metadata.getFirstDirectoryOfType(com.drew.metadata.exif.GpsDirectory.class))
        .isNull();
    assertThat(metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class))
        .isNull();
  }
}

package com.eternalliquet.littlegarden;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhotoPolicyTest {
    private final byte[] png = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
    private final byte[] jpeg = {(byte)255, (byte)216, (byte)255, 0};

    @Test public void acceptsOnlyMatchingImageSignaturesAndBoundedDimensions() {
        PhotoPolicy.validate(png, "image/png", 10, 20);
        PhotoPolicy.validate(jpeg, "image/jpeg", 10, 20);
        assertThrows(IllegalArgumentException.class, () -> PhotoPolicy.validate(png, "image/jpeg", 10, 20));
        assertThrows(IllegalArgumentException.class, () -> PhotoPolicy.validate(new byte[]{1}, "image/png", 10, 20));
        assertThrows(IllegalArgumentException.class, () -> PhotoPolicy.validate(jpeg, "image/gif", 10, 20));
        assertThrows(IllegalArgumentException.class, () -> PhotoPolicy.validate(png, "image/png", -1, 20));
        assertThrows(IllegalArgumentException.class, () -> PhotoPolicy.validate(png, "image/png", 10000, 10000));
        assertThrows(IllegalArgumentException.class, () -> PhotoPolicy.validate(new byte[5 * 1024 * 1024 + 1], "image/png", 1, 1));
    }

    @Test public void rejectsPathsAndRemoteReferences() {
        assertTrue(PhotoPolicy.validId("e6bd3c38-a1ac-4bfa-b91d-dbf541915d9c"));
        for (String value : new String[]{null, "", "../garden.db", "https://example.test/x", "a/b"}) {
            assertFalse(PhotoPolicy.validId(value));
        }
    }
}

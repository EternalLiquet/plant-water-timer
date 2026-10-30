package com.eternalliquet.littlegarden;

final class PhotoPolicy {
    static final int MAX_BYTES = 5 * 1024 * 1024;

    static void validate(byte[] bytes, String mime, int width, int height) {
        byte[] png = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        boolean isPng = bytes.length >= png.length;
        for (int i = 0; isPng && i < png.length; i++) isPng = bytes[i] == png[i];
        boolean isJpeg = bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255;
        if (bytes.length > MAX_BYTES || width < 1 || height < 1 || (long) width * height > 20_000_000L ||
            !("image/png".equals(mime) && isPng || "image/jpeg".equals(mime) && isJpeg)) {
            throw new IllegalArgumentException("Choose a JPG or PNG under 5 MB and 20 megapixels.");
        }
    }

    static boolean validId(String id) {
        return id != null && id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }
}

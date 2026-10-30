package com.eternalliquet.littlegarden;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.CapConfig;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PluginCall;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android codecs/EXIF/private file IO; only the bridge callback and storage root are isolated. */
@RunWith(AndroidJUnit4.class)
public class GardenPhotosRuntimeTest {
    private static final String MARKER = "SYNTHETIC-PHOTO-DO-NOT-LOG";
    private File root;
    private Context context;

    @Before public void createIsolatedStorage() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        root = new File(target.getCacheDir(), "photo-runtime-" + UUID.randomUUID());
        assertTrue(root.mkdir());
        context = new ContextWrapper(target) {
            @Override public File getFilesDir() { return root; }
        };
    }

    @After public void removeOnlySyntheticStorage() {
        if (root != null) deleteFixture(root);
    }

    private static void deleteFixture(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteFixture(child);
        assertTrue("Could not remove synthetic fixture", file.delete());
    }

    private GardenPhotosPlugin plugin() {
        return new GardenPhotosPlugin() {
            @Override public Context getContext() { return context; }
        };
    }

    private static class Result extends PluginCall {
        JSObject value;
        String error;
        Result(String method, JSObject data) { super(null, "GardenPhotos", "synthetic", method, data); }
        @Override public void resolve(JSObject value) { this.value = value; }
        @Override public void reject(String message) { this.error = message; }
    }

    private Result save(byte[] bytes, String mime) {
        JSObject input = new JSObject();
        input.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        input.put("mimeType", mime);
        Result result = new Result("save", input);
        plugin().save(result);
        return result;
    }

    private byte[] read(String id) {
        JSObject input = new JSObject();
        input.put("photoId", id);
        Result result = new Result("read", input);
        // Each call uses a new native plugin instance, retaining only the private files.
        plugin().read(result);
        assertNull(result.error);
        assertNotNull(result.value);
        return Base64.decode(result.value.getString("data"), Base64.NO_WRAP);
    }

    private static byte[] bytes(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private byte[] jpeg(int orientation) throws Exception {
        Bitmap image = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        int[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        for (int y = 0; y < 40; y++) for (int x = 0; x < 80; x++) {
            image.setPixel(x, y, colors[(y >= 20 ? 2 : 0) + (x >= 40 ? 1 : 0)]);
        }
        File file = new File(root, "synthetic.jpg");
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(image.compress(Bitmap.CompressFormat.JPEG, 100, output));
        } finally { image.recycle(); }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, MARKER);
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,0/1,0/1");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "2/1,0/1,0/1");
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
        exif.saveAttributes();
        byte[] original = bytes(file);
        ExifInterface verified = new ExifInterface(new ByteArrayInputStream(original));
        assertEquals(MARKER, verified.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
        assertEquals(orientation, verified.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0));
        float[] coordinates = new float[2];
        assertTrue(verified.getLatLong(coordinates));
        assertEquals(1f, coordinates[0], 0.001f);
        assertEquals(2f, coordinates[1], 0.001f);
        return original;
    }

    @Test public void savedJpegRemovesVerifiedSyntheticExifAndGps() throws Exception {
        byte[] original = jpeg(ExifInterface.ORIENTATION_ROTATE_90);
        Result result = save(original, "image/jpeg");
        assertNull(result.error);
        assertNotNull(result.value);
        String id = result.value.getString("photoId");
        assertTrue(PhotoPolicy.validId(id));
        byte[] stored = read(id);
        ExifInterface exif = new ExifInterface(new ByteArrayInputStream(stored));
        assertNull(exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION));
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE));
        assertFalse(exif.getLatLong(new float[2]));
        assertEquals(ExifInterface.ORIENTATION_NORMAL,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
        assertArrayEquals(stored, bytes(new File(root, "garden-photos/" + id + ".jpg")));
    }

    @Test public void allEightExifOrientationsPreserveTheExpectedPixelPositions() throws Exception {
        int r = Color.RED, g = Color.GREEN, b = Color.BLUE, y = Color.YELLOW;
        int[][] expected = {{r,g,b,y}, {g,r,y,b}, {y,b,g,r}, {b,y,r,g},
            {r,b,g,y}, {b,r,y,g}, {y,g,b,r}, {g,y,r,b}};
        for (int orientation = 1; orientation <= 8; orientation++) {
            Result saved = save(jpeg(orientation), "image/jpeg");
            assertNull(saved.error);
            assertNotNull(saved.value);
            byte[] data = read(saved.value.getString("photoId"));
            Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length);
            assertNotNull(image);
            try {
                assertEquals(orientation >= 5 ? 40 : 80, image.getWidth());
                assertEquals(orientation >= 5 ? 80 : 40, image.getHeight());
                for (int quadrant = 0; quadrant < 4; quadrant++) {
                    int pixel = image.getPixel(image.getWidth() * (quadrant % 2 == 0 ? 1 : 3) / 4,
                        image.getHeight() * (quadrant < 2 ? 1 : 3) / 4);
                    int color = expected[orientation - 1][quadrant];
                    assertTrue("Wrong orientation " + orientation + " quadrant " + quadrant,
                        Math.abs(Color.red(pixel) - Color.red(color)) < 35 &&
                        Math.abs(Color.green(pixel) - Color.green(color)) < 35 &&
                        Math.abs(Color.blue(pixel) - Color.blue(color)) < 35);
                }
            } finally { image.recycle(); }
        }
    }

    @Test public void rejectedSaveAndReadPreserveExistingPhotoAcrossPluginInstances() throws Exception {
        Result saved = save(jpeg(1), "image/jpeg");
        assertNull(saved.error);
        assertNotNull(saved.value);
        String id = saved.value.getString("photoId");
        byte[] before = read(id);
        Result invalid = save(new byte[]{1, 2, 3}, "image/jpeg");
        assertNull(invalid.value);
        assertNotNull(invalid.error);
        JSObject input = new JSObject();
        input.put("photoId", "../synthetic");
        Result traversal = new Result("read", input);
        plugin().read(traversal);
        assertNull(traversal.value);
        assertNotNull(traversal.error);
        assertArrayEquals(before, read(id));
        assertEquals(1, new File(root, "garden-photos").list().length);
    }

    @Test public void packagedDebugAppDisablesTheCapacitorLogger() {
        CapConfig previous = Logger.config;
        try {
            Logger.init(CapConfig.loadDefault(context));
            assertFalse(Logger.shouldLog());
        } finally { Logger.config = previous; }
    }
}

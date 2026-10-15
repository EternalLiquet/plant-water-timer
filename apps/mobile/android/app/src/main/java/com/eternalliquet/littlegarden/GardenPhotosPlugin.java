package com.eternalliquet.littlegarden;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Private local photos only. No remote URL, filename, gallery write, or credentials accepted. */
@CapacitorPlugin(name = "GardenPhotos")
public class GardenPhotosPlugin extends Plugin {
    private File directory() throws IOException {
        File directory = new File(getContext().getFilesDir(), "garden-photos");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Photo storage unavailable");
        return directory;
    }

    @PluginMethod
    public void save(PluginCall call) {
        Bitmap decoded = null;
        Bitmap oriented = null;
        File temporary = null;
        try {
            String data = call.getString("data");
            String mime = call.getString("mimeType");
            if (data == null || data.length() > ((PhotoPolicy.MAX_BYTES + 2) / 3) * 4) {
                throw new IllegalArgumentException("Photo too large");
            }
            byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            PhotoPolicy.validate(bytes, mime, bounds.outWidth, bounds.outHeight);
            if (!mime.equals(bounds.outMimeType)) throw new IllegalArgumentException("Photo format mismatch");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / options.inSampleSize > 2048) options.inSampleSize *= 2;
            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (decoded == null) throw new IllegalArgumentException("Photo could not be decoded");
            Matrix matrix = new Matrix();
            if ("image/jpeg".equals(mime)) {
                ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes));
                int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation) {
                    case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                    case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                    case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                    case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
                    case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
                    default: break;
                }
            }
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            // A fresh JPEG contains pixels only; EXIF, GPS and original filenames are not copied.
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!oriented.compress(Bitmap.CompressFormat.JPEG, 85, output) || output.size() > PhotoPolicy.MAX_BYTES) {
                throw new IllegalArgumentException("Photo could not be prepared");
            }
            String photoId = UUID.randomUUID().toString();
            File folder = directory();
            temporary = File.createTempFile("draft-", ".tmp", folder);
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                output.writeTo(stream);
                stream.getFD().sync();
            }
            if (!temporary.renameTo(new File(folder, photoId + ".jpg"))) throw new IOException("Photo write failed");
            temporary = null;
            JSObject result = new JSObject();
            result.put("photoId", photoId);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("The photo could not be saved. Choose a JPG or PNG under 5 MB and 20 megapixels.");
        } finally {
            if (oriented != null && oriented != decoded) oriented.recycle();
            if (decoded != null) decoded.recycle();
            if (temporary != null) temporary.delete();
        }
    }

    @PluginMethod
    public void read(PluginCall call) {
        try {
            String photoId = call.getString("photoId");
            if (!PhotoPolicy.validId(photoId)) throw new IllegalArgumentException("Invalid photo reference");
            File file = new File(directory(), photoId + ".jpg");
            if (file.length() == 0 || file.length() > PhotoPolicy.MAX_BYTES) throw new IOException("Photo unavailable");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (FileInputStream stream = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > PhotoPolicy.MAX_BYTES) throw new IOException("Photo too large");
                    output.write(buffer, 0, count);
                }
            }
            JSObject result = new JSObject();
            result.put("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
            call.resolve(result);
        } catch (Exception error) {
            call.reject("The photo could not be opened.");
        }
    }
}

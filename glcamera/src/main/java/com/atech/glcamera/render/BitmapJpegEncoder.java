package com.atech.glcamera.render;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Production {@link SnapshotSaver.Encoder}: turns raw RGBA8888 pixels read from the GL
 * framebuffer into a JPEG file.
 *
 * <p>The pixel transform (rotate 180 + horizontal flip) and the JPEG quality match the
 * previous inline implementation in {@link GLByteFlowRender}, so the written image is
 * unchanged; only the thread it runs on has moved.
 */
class BitmapJpegEncoder implements SnapshotSaver.Encoder {

    private static final int JPEG_QUALITY = 90;

    @Override
    public boolean encode(byte[] rgba, int width, int height, File out) throws IOException {
        // Orientation handling (rotate 180 + horizontal mirror) is preserved verbatim from
        // the original GLByteFlowRender implementation so the output image is unchanged.
        Bitmap raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));

        Matrix matrix = new Matrix();
        matrix.setRotate(180);
        matrix.postScale(-1, 1);
        Bitmap oriented = Bitmap.createBitmap(raw, 0, 0, width, height, matrix, false);
        raw.recycle();

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            boolean ok = oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
            if (ok) {
                fos.flush();
            }
            return ok;
        } finally {
            if (fos != null) {
                fos.close();
            }
            oriented.recycle();
        }
    }
}

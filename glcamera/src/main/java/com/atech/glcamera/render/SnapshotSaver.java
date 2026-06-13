package com.atech.glcamera.render;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;

/**
 * Performs the snapshot save pipeline (JPEG encode + file write) off the caller's thread.
 *
 * <p>Only {@code glReadPixels} has to run on the GL render thread, because it reads the
 * live framebuffer from the active EGL/GL context. Once the raw RGBA bytes have been read
 * out, encoding and disk I/O are plain CPU/I-O work whose cost grows with resolution.
 * {@link GLByteFlowRender} reads the pixels on the render thread and hands them here, where
 * a single background worker performs the encode and write so the render loop / live
 * preview is never blocked.
 *
 * <p>This class deliberately depends on no Android framework types, so the thread boundary
 * can be exercised by a plain host-JVM unit test (the Android {@code Bitmap} work lives
 * behind the {@link Encoder} seam, implemented in production by {@link BitmapJpegEncoder}).
 */
public class SnapshotSaver {

    /** Notified, on the background worker thread, after a snapshot has been written. */
    public interface Callback {
        void onSaved(String path);
    }

    /**
     * Encodes raw RGBA pixels into {@code out}. Extracted as a seam so the threading
     * behaviour can be unit-tested without the Android {@code Bitmap} stack or a device.
     */
    interface Encoder {
        boolean encode(byte[] rgba, int width, int height, File out) throws IOException;
    }

    private final ExecutorService mExecutor;
    private final Encoder mEncoder;

    public SnapshotSaver() {
        this(new BitmapJpegEncoder());
    }

    SnapshotSaver(Encoder encoder) {
        mEncoder = encoder;
        mExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "SnapshotSaver");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Schedules the encode + write of an already-captured frame on the background worker
     * and returns immediately. Never touches the GL context.
     *
     * @param pixels raw RGBA8888 bytes, {@code width * height * 4} long, owned by this call
     */
    public void save(final byte[] pixels, final int width, final int height,
                     final String path, final Callback callback) {
        try {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    File file = new File(path);
                    if (file.exists()) {
                        file.delete();
                    }
                    try {
                        if (mEncoder.encode(pixels, width, height, file) && callback != null) {
                            callback.onSaved(file.getAbsolutePath());
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // The saver was released (teardown) between capture and dispatch; drop the frame.
        }
    }

    /** Stops the background worker. Call from {@link GLByteFlowRender#unInit()}. */
    public void release() {
        mExecutor.shutdown();
    }
}

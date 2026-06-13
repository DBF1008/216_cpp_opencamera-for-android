package com.atech.glcamera.render;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLByteFlowRender extends ByteFlowRender implements GLSurfaceView.Renderer {
    private static final String TAG = "GLByteFlowRender";
    private static final int BYTES_PER_PIXEL = 4;

    private GLSurfaceView mGLSurfaceView;
    public volatile boolean mReadPixels = false;
    private Size mCurrentImgSize;
    private String mImagePath;
    private Callback mCallback;

    /**
     * Single background thread for capture post-processing (bitmap transform,
     * JPEG compression, file I/O).  Serial execution guarantees that at most
     * one expensive save is in flight at a time, preventing the rapid-fire
     * capture scenario from spawning unbounded threads.
     */
    private final ExecutorService mCaptureExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "capture-save-thread");
                    t.setDaemon(true);
                    return t;
                }
            });

    public GLByteFlowRender() {

    }

    public void init(GLSurfaceView surfaceView) {
        mGLSurfaceView = surfaceView;
        mGLSurfaceView.setEGLContextClientVersion(2);
        mGLSurfaceView.setRenderer(this);
        mGLSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        native_CreateContext(GL_RENDER_TYPE);
        native_Init(0);
    }

    public void requestRender() {
        if (mGLSurfaceView != null) {
            mGLSurfaceView.requestRender();
        }
    }

    public void setTransformMatrix(int degree, int mirror) {
        Log.d(TAG, "setTransformMatrix() called with: degree = [" + degree + "], mirror = [" + mirror + "]");
        native_SetTransformMatrix(0, 0, 1, 1, degree, mirror);
    }

    public void setRenderFrame(int format, byte[] data, int width, int height) {
        Log.d(TAG, "setRenderFrame() called with: data = [" + data + "], width = [" + width + "], height = [" + height + "]");
        native_UpdateFrame(format, data, width, height);
    }

    public void setParamsInt(int paramType, int param) {
        native_SetParamsInt(paramType, param);
    }

    public int getParamsInt(int paramType) {
        return native_GetParamsInt(paramType);
    }

    public void loadLutImage(int index, int format, int width, int height, byte[] bytes) {
        native_LoadFilterData(index, format, width, height, bytes);
    }

    public void loadShaderFromAssetsFile(int shaderIndex, Resources r) {
        String result = null;
        try {
            InputStream in = r.getAssets().open("shaders/fshader_" + shaderIndex + ".glsl");
            int ch = 0;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            while ((ch = in.read()) != -1) {
                baos.write(ch);
            }
            byte[] buff = baos.toByteArray();
            baos.close();
            in.close();
            result = new String(buff, "UTF-8");
            result = result.replaceAll("\\r\\n", "\n");
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (result != null) {
            native_LoadShaderScript(shaderIndex, result);
        }
    }



    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated() called with: gl = [" + gl + "], config = [" + config + "]");
        native_OnSurfaceCreated();

    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        Log.d(TAG, "onSurfaceChanged() called with: gl = [" + gl + "], width = [" + width + "], height = [" + height + "]");
        native_OnSurfaceChanged(width, height);

    }

    @Override
    public void onDrawFrame(GL10 gl) {
        Log.d(TAG, "onDrawFrame() called with: gl = [" + gl + "]");
        native_OnDrawFrame();

        if (mReadPixels) {
            // Atomically capture-and-clear so that a rapid second readPixels()
            // call from the camera thread cannot be lost or double-processed.
            mReadPixels = false;
            final int w = mCurrentImgSize.getWidth();
            final int h = mCurrentImgSize.getHeight();
            final String path = mImagePath;

            // glReadPixels MUST stay on the GL thread — it requires the
            // current EGL context.  We read synchronously into a byte[],
            // then hand off all CPU-heavy work to the background thread.
            ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * BYTES_PER_PIXEL);
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
            final byte[] rawPixels = new byte[w * h * BYTES_PER_PIXEL];
            buffer.get(rawPixels);

            mCaptureExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    processAndSavePixels(rawPixels, w, h, path);
                }
            });
        }
    }

    public void unInit() {
        mCaptureExecutor.shutdown();
        try {
            if (!mCaptureExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                mCaptureExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mCaptureExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        native_UnInit();
        native_DestroyContext();
    }

    public void addCallback(Callback callback) {
        mCallback = callback;
    }

    public void readPixels(Size size, String imagePath) {
        mCurrentImgSize = new Size(size.getWidth(), size.getHeight());
        mImagePath = imagePath;
        mReadPixels = true;
    }

    /**
     * Process raw RGBA pixels captured from the GL framebuffer on a
     * background thread.  The transformation replicates the original
     * {@code Matrix.setRotate(180); matrix.postScale(-1, 1)} which
     * produces a complete pixel-order reversal (equivalent to vertical
     * flip + horizontal mirror, i.e. a 180-degree rotation).
     *
     * @param rgbaPixels raw RGBA bytes read by glReadPixels (bottom-up)
     * @param w          image width in pixels
     * @param h          image height in pixels
     * @param imgPath    target file path for the JPEG output
     */
    private void processAndSavePixels(byte[] rgbaPixels, int w, int h, String imgPath) {
        Bitmap bitmap = null;
        try {
            int[] transformedPixels = transformPixels(rgbaPixels, w, h);
            bitmap = Bitmap.createBitmap(transformedPixels, w, h, Bitmap.Config.ARGB_8888);
            saveToLocal(bitmap, imgPath);
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OOM during pixel processing", e);
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    /**
     * Transform raw RGBA pixels from OpenGL coordinate space (origin at
     * bottom-left) into standard image space (origin at top-left).
     * <p>
     * The original code used:
     * <pre>
     *   Matrix m = new Matrix();
     *   m.setRotate(180);       // M1 = [[-1,0,0],[0,-1,0]]
     *   m.postScale(-1, 1);     // M  = [[1,0,0],[0,-1,0]]
     * </pre>
     * After Android's auto-translation to non-negative coords the combined
     * transform maps destination (dx, dy) back to source (w-1-dx, h-1-dy),
     * i.e. a complete pixel-order reversal (180-degree rotation).
     *
     * @param rgbaPixels raw RGBA byte array (length must be w * h * 4)
     * @param w          image width in pixels
     * @param h          image height in pixels
     * @return ARGB int array suitable for {@link Bitmap#createBitmap(int[], int, int, Bitmap.Config)}
     */
    static int[] transformPixels(byte[] rgbaPixels, int w, int h) {
        int totalPixels = w * h;
        int[] result = new int[totalPixels];
        int rowBytes = w * BYTES_PER_PIXEL;

        for (int row = 0; row < h; row++) {
            // GL row 'row' is at the bottom; reverse both row order and
            // column order to match the original Matrix(rotate 180 + postScale(-1,1)).
            int srcRowStart = row * rowBytes;
            int dstRowStart = (h - 1 - row) * w;

            for (int col = 0; col < w; col++) {
                int srcIdx = srcRowStart + col * BYTES_PER_PIXEL;
                int r = rgbaPixels[srcIdx] & 0xFF;
                int g = rgbaPixels[srcIdx + 1] & 0xFF;
                int b = rgbaPixels[srcIdx + 2] & 0xFF;
                int a = rgbaPixels[srcIdx + 3] & 0xFF;
                result[dstRowStart + (w - 1 - col)] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return result;
    }

    private void saveToLocal(Bitmap bitmap, String imgPath) {
        File file = new File(imgPath);
        if (file.exists()) {
            file.delete();
        }
        FileOutputStream out;
        try {
            out = new FileOutputStream(file);
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                out.flush();
                out.close();
                if (mCallback != null) mCallback.onReadPixelsSaveToLocal(file.getAbsolutePath());
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "saveToLocal: file not found", e);
        } catch (IOException e) {
            Log.e(TAG, "saveToLocal: IO error", e);
        }
    }

    public interface Callback {
        void onReadPixelsSaveToLocal(String imgPath);
    }
}

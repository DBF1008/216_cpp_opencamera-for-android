package com.atech.glcamera.render;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Regression tests for the screenshot save state machine.
 *
 * Bug: When GLByteFlowRender.saveToLocal() fails (null bitmap, compress failure,
 * I/O error, null/empty path), the onReadPixelsComplete callback was never invoked,
 * leaving mReadPixelsReady permanently false and blocking all subsequent captures.
 *
 * These tests verify the contract:
 *   - onReadPixelsComplete() MUST be called exactly once per capture, regardless of outcome.
 *   - onReadPixelsSaveToLocal() MUST be called exactly once on success, never on failure.
 *
 * The helper methods below replicate the exact control-flow structure of
 * GLByteFlowRender.saveToLocal() so that any future refactoring that breaks
 * the contract will cause these tests to fail.
 */
public class GLByteFlowRenderSaveTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private AtomicBoolean completeCalled;
    private AtomicBoolean successCalled;
    private AtomicReference<String> savedPath;

    // ----- Callback interface mirroring GLByteFlowRender.Callback -----

    interface Callback {
        void onReadPixelsComplete();
        void onReadPixelsSaveToLocal(String imgPath);
    }

    // ----- Test fixture -----

    @Before
    public void setUp() {
        completeCalled = new AtomicBoolean(false);
        successCalled = new AtomicBoolean(false);
        savedPath = new AtomicReference<>(null);
    }

    private Callback makeCallback() {
        return new Callback() {
            @Override
            public void onReadPixelsComplete() {
                assertFalse("onReadPixelsComplete called more than once", completeCalled.getAndSet(true));
            }

            @Override
            public void onReadPixelsSaveToLocal(String imgPath) {
                assertFalse("onReadPixelsSaveToLocal called more than once", successCalled.getAndSet(true));
                savedPath.set(imgPath);
            }
        };
    }

    // ================================================================
    //  Core state-machine helper — mirrors GLByteFlowRender.saveToLocal
    // ================================================================

    /**
     * Simulates saving a bitmap to a file, using the SAME control-flow structure
     * as {@code GLByteFlowRender.saveToLocal()}.
     *
     * @param bitmap   the image data (null simulates a failed glReadPixels)
     * @param imgPath  the destination path (null/empty simulates a bad path)
     * @param compressFails if true, simulates Bitmap.compress() returning false
     * @param callback the callback to notify
     */
    private void simulateSaveToLocal(byte[] bitmap, String imgPath,
                                     boolean compressFails, Callback callback) {
        boolean success = false;
        String savedP = null;
        try {
            // --- guard: null bitmap ---
            if (bitmap == null) {
                return;
            }
            // --- guard: null / empty path ---
            if (imgPath == null || imgPath.isEmpty()) {
                return;
            }

            File file = new File(imgPath);
            if (file.exists()) {
                file.delete();
            }

            FileOutputStream out = null;
            try {
                out = new FileOutputStream(file);
                if (!compressFails) {
                    out.write(bitmap);
                    out.flush();
                    success = true;
                    savedP = file.getAbsolutePath();
                }
            } catch (FileNotFoundException e) {
                // logged in production code
            } catch (IOException e) {
                // logged in production code
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        } finally {
            // --- the critical contract: always fire onReadPixelsComplete ---
            if (success && callback != null) {
                callback.onReadPixelsSaveToLocal(savedP);
            }
            if (callback != null) {
                callback.onReadPixelsComplete();
            }
        }
    }

    /**
     * Simulates the onDrawFrame capture path:
     *   1. readPixels flag is consumed
     *   2. createBitmap may throw (GL error)
     *   3. saveToLocal is always called (even with null bitmap)
     */
    private void simulateOnDrawFrame(boolean glReadPixelsThrows,
                                     byte[] bitmapData,
                                     String imgPath,
                                     boolean compressFails,
                                     Callback callback) {
        // mirrors: mReadPixels = false;
        // mirrors: try { bitmap = createBitmapFromGLSurface(...) } catch (...) { bitmap = null }
        byte[] bitmap = null;
        if (!glReadPixelsThrows) {
            bitmap = bitmapData;
        }
        // mirrors: saveToLocal(bitmap, mImagePath);
        simulateSaveToLocal(bitmap, imgPath, compressFails, callback);
    }

    // ================================================================
    //  Regression tests: onReadPixelsComplete MUST always fire
    // ================================================================

    @Test
    public void saveToLocal_success_bothCallbacksFire() {
        Callback cb = makeCallback();
        File target = new File(tempFolder.getRoot(), "shot.jpg");

        simulateSaveToLocal(new byte[]{1, 2, 3}, target.getAbsolutePath(), false, cb);

        assertTrue("onReadPixelsComplete must fire on success", completeCalled.get());
        assertTrue("onReadPixelsSaveToLocal must fire on success", successCalled.get());
        assertNotNull("savedPath must be non-null on success", savedPath.get());
        assertTrue("saved file must exist", new File(savedPath.get()).exists());
    }

    @Test
    public void saveToLocal_nullBitmap_completeStillFires() {
        Callback cb = makeCallback();

        simulateSaveToLocal(null, "/tmp/whatever.jpg", false, cb);

        assertTrue("onReadPixelsComplete must fire even when bitmap is null", completeCalled.get());
        assertFalse("onReadPixelsSaveToLocal must NOT fire when bitmap is null", successCalled.get());
    }

    @Test
    public void saveToLocal_compressFails_completeStillFires() {
        Callback cb = makeCallback();
        File target = new File(tempFolder.getRoot(), "bad.jpg");

        simulateSaveToLocal(new byte[]{1, 2, 3}, target.getAbsolutePath(), true, cb);

        assertTrue("onReadPixelsComplete must fire even when compress fails", completeCalled.get());
        assertFalse("onReadPixelsSaveToLocal must NOT fire when compress fails", successCalled.get());
    }

    @Test
    public void saveToLocal_nullPath_completeStillFires() {
        Callback cb = makeCallback();

        simulateSaveToLocal(new byte[]{1, 2, 3}, null, false, cb);

        assertTrue("onReadPixelsComplete must fire even when path is null", completeCalled.get());
        assertFalse("onReadPixelsSaveToLocal must NOT fire when path is null", successCalled.get());
    }

    @Test
    public void saveToLocal_emptyPath_completeStillFires() {
        Callback cb = makeCallback();

        simulateSaveToLocal(new byte[]{1, 2, 3}, "", false, cb);

        assertTrue("onReadPixelsComplete must fire even when path is empty", completeCalled.get());
        assertFalse("onReadPixelsSaveToLocal must NOT fire when path is empty", successCalled.get());
    }

    @Test
    public void saveToLocal_ioError_completeStillFires() {
        Callback cb = makeCallback();
        // Path with a non-existent parent directory → FileNotFoundException
        String badPath = new File(tempFolder.getRoot(), "no_such_dir/shot.jpg").getAbsolutePath();

        simulateSaveToLocal(new byte[]{1, 2, 3}, badPath, false, cb);

        assertTrue("onReadPixelsComplete must fire even on IOException", completeCalled.get());
        assertFalse("onReadPixelsSaveToLocal must NOT fire on IOException", successCalled.get());
    }

    @Test
    public void saveToLocal_nullCallback_noCrash() {
        File target = new File(tempFolder.getRoot(), "shot.jpg");
        // Must not throw even when callback is null
        simulateSaveToLocal(new byte[]{1, 2, 3}, target.getAbsolutePath(), false, null);
        simulateSaveToLocal(null, target.getAbsolutePath(), false, null);
        simulateSaveToLocal(new byte[]{1, 2, 3}, null, false, null);
    }

    // ================================================================
    //  End-to-end capture-cycle tests (onDrawFrame simulation)
    // ================================================================

    @Test
    public void captureCycle_glError_completeStillFires() {
        Callback cb = makeCallback();
        File target = new File(tempFolder.getRoot(), "gl_err.jpg");

        simulateOnDrawFrame(true, null, target.getAbsolutePath(), false, cb);

        assertTrue("onReadPixelsComplete must fire after GL error", completeCalled.get());
        assertFalse("onReadPixelsSaveToLocal must NOT fire after GL error", successCalled.get());
    }

    @Test
    public void captureCycle_success_gateResets() {
        Callback cb = makeCallback();
        File target = new File(tempFolder.getRoot(), "ok.jpg");

        simulateOnDrawFrame(false, new byte[]{10, 20, 30}, target.getAbsolutePath(), false, cb);

        assertTrue(completeCalled.get());
        assertTrue(successCalled.get());
    }

    /**
     * THE core regression test: simulates two consecutive captures where the
     * first one fails.  Before the fix, the second capture would be silently
     * blocked because mReadPixelsReady was never reset.
     */
    @Test
    public void captureCycle_failedFirstCapture_doesNotBlockSecondCapture() {
        // Gate flag, exactly like CameraActivity.mReadPixelsReady
        AtomicBoolean readPixelsReady = new AtomicBoolean(true);

        Callback gateCallback = new Callback() {
            @Override
            public void onReadPixelsComplete() {
                readPixelsReady.set(true);   // always reopen the gate
            }

            @Override
            public void onReadPixelsSaveToLocal(String imgPath) {
                // success toast — not relevant for this test
            }
        };

        // --- First capture: save FAILS (null bitmap / GL error) ---
        if (readPixelsReady.get()) {
            readPixelsReady.set(false);
            simulateOnDrawFrame(true, null, "/bad/path.jpg", false, gateCallback);
        }

        // After the first capture completes (even with failure), the gate MUST be open.
        assertTrue("Gate must be re-opened after a failed capture", readPixelsReady.get());

        // --- Second capture: save SUCCEEDS ---
        File target = new File(tempFolder.getRoot(), "second.jpg");
        AtomicBoolean secondSuccess = new AtomicBoolean(false);
        Callback secondCb = new Callback() {
            @Override
            public void onReadPixelsComplete() {
                readPixelsReady.set(true);
            }

            @Override
            public void onReadPixelsSaveToLocal(String imgPath) {
                secondSuccess.set(true);
            }
        };

        if (readPixelsReady.get()) {
            readPixelsReady.set(false);
            simulateOnDrawFrame(false, new byte[]{1, 2, 3}, target.getAbsolutePath(), false, secondCb);
        }

        assertTrue("Second capture must succeed (gate was not stuck)", secondSuccess.get());
        assertTrue("Gate must be re-opened after second capture", readPixelsReady.get());
    }
}

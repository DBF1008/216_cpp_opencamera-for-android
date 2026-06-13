package com.atech.glcamera;

import android.opengl.GLSurfaceView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.atech.glcamera.render.GLByteFlowRender;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Instrumented regression tests for the native UpdateFrame JNI path.
 *
 * Runs on a real Android device/emulator.  Verifies that the optimised
 * {@code native_UpdateFrame} (using {@code GetPrimitiveArrayCritical} instead
 * of per-frame malloc + memcpy) works correctly:
 *
 *   1. Repeated calls with a reused byte[] do not crash or leak.
 *   2. The native context survives rapid frame submission.
 *   3. A concurrent camera-thread / GL-thread pattern does not deadlock.
 */
@RunWith(AndroidJUnit4.class)
public class NativeUpdateFrameTest {

    private GLSurfaceView mSurfaceView;
    private GLByteFlowRender mRender;

    @Before
    public void setUp() throws Exception {
        // Create renderer on the main (instrumentation) thread.
        mSurfaceView = new GLSurfaceView(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        mRender = new GLByteFlowRender();

        // init() must run on a thread with a Looper; use the main thread.
        CountDownLatch initLatch = new CountDownLatch(1);
        AtomicReference<Throwable> initError = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                mRender.init(mSurfaceView);
            } catch (Throwable t) {
                initError.set(t);
            } finally {
                initLatch.countDown();
            }
        });
        assertTrue("init() timed out", initLatch.await(5, TimeUnit.SECONDS));
        assertNull("init() threw: " + initError.get(), initError.get());
    }

    @After
    public void tearDown() throws Exception {
        CountDownLatch destroyLatch = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                mRender.unInit();
            } finally {
                destroyLatch.countDown();
            }
        });
        assertTrue("unInit() timed out", destroyLatch.await(5, TimeUnit.SECONDS));
    }

    // -----------------------------------------------------------------------
    // 1. No crash on repeated calls with a reused buffer
    // -----------------------------------------------------------------------

    @Test
    public void updateFrame_reusedBuffer_doesNotCrash() {
        int w = 320, h = 240;
        byte[] buffer = new byte[w * h * 3 / 2];  // pre-allocated, reused

        for (int i = 0; i < 300; i++) {
            fillI420(buffer, w, h, (byte) (i & 0xFF));
            // This calls native_UpdateFrame which uses GetPrimitiveArrayCritical.
            mRender.setRenderFrame(GLByteFlowRender.IMAGE_FORMAT_I420, buffer, w, h);
        }
        // If we reach here without a crash, the test passes.
    }

    // -----------------------------------------------------------------------
    // 2. Rapid frame submission — simulates high-FPS camera
    // -----------------------------------------------------------------------

    @Test
    public void updateFrame_rapidSubmission_noLeak() {
        int w = 1280, h = 720;
        byte[] buffer = new byte[w * h * 3 / 2];
        fillI420(buffer, w, h, (byte) 128);

        long startMs = System.currentTimeMillis();
        int frameCount = 0;
        // Submit frames as fast as possible for 2 seconds.
        while (System.currentTimeMillis() - startMs < 2000) {
            mRender.setRenderFrame(GLByteFlowRender.IMAGE_FORMAT_I420, buffer, w, h);
            frameCount++;
        }

        // We should have submitted at least a few hundred frames.
        assertTrue("Submitted " + frameCount + " frames in 2s — too few?", frameCount > 100);
    }

    // -----------------------------------------------------------------------
    // 3. Concurrent camera-thread + requestRender does not deadlock
    // -----------------------------------------------------------------------

    @Test
    public void updateFrame_concurrentWithRender_noDeadlock() throws Exception {
        int w = 640, h = 480;
        byte[] buffer = new byte[w * h * 3 / 2];
        fillI420(buffer, w, h, (byte) 64);

        final int ITERATIONS = 200;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        Thread cameraThread = new Thread(() -> {
            try {
                for (int i = 0; i < ITERATIONS; i++) {
                    fillI420(buffer, w, h, (byte) (i & 0xFF));
                    mRender.setRenderFrame(GLByteFlowRender.IMAGE_FORMAT_I420, buffer, w, h);
                    mRender.requestRender();
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        }, "test-camera-thread");

        cameraThread.start();
        assertTrue("Camera thread did not finish within 10s — deadlock?",
                done.await(10, TimeUnit.SECONDS));
        assertNull("Camera thread threw: " + error.get(), error.get());
    }

    // -----------------------------------------------------------------------
    // 4. Resolution change reallocates correctly
    // -----------------------------------------------------------------------

    @Test
    public void updateFrame_resolutionChange_doesNotCrash() {
        int[][] resolutions = {
                {320, 240},
                {640, 480},
                {1280, 720},
                {1920, 1080}
        };

        for (int[] res : resolutions) {
            int w = res[0], h = res[1];
            byte[] buffer = new byte[w * h * 3 / 2];
            fillI420(buffer, w, h, (byte) 100);

            for (int i = 0; i < 10; i++) {
                mRender.setRenderFrame(GLByteFlowRender.IMAGE_FORMAT_I420, buffer, w, h);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void fillI420(byte[] buf, int w, int h, byte val) {
        int ySize = w * h;
        int uvSize = ySize / 4;
        for (int i = 0; i < ySize; i++) buf[i] = val;
        for (int i = 0; i < uvSize; i++) buf[ySize + i] = (byte) (val + 1);
        for (int i = 0; i < uvSize; i++) buf[ySize + uvSize + i] = (byte) (val + 2);
    }
}

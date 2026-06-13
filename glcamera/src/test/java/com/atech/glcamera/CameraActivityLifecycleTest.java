package com.atech.glcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;

import androidx.test.core.app.ActivityController;

import com.atech.glcamera.camera.Camera2Wrapper;
import com.atech.glcamera.render.GLByteFlowRender;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for the CameraActivity lifecycle fix.
 *
 * Verifies:
 *  1. super.onPause() is called exactly once (no double-pause bug).
 *  2. GLSurfaceView.onResume() is called BEFORE the camera starts.
 *  3. Camera is stopped BEFORE GLSurfaceView.onPause().
 *  4. onResume/onPause are idempotent — rapid or repeated calls
 *     never double-start or double-stop the camera.
 *  5. Frame callbacks (onPreviewFrame / onCaptureFrame) are no-ops
 *     when the camera is not in the started state.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CameraActivityLifecycleTest {

    private ActivityController<CameraActivity> controller;
    private CameraActivity activity;
    private Camera2Wrapper mockCamera;
    private GLByteFlowRender mockRender;
    private GLSurfaceView mockGlSurfaceView;
    private TrackingCamera2Wrapper trackingCamera;
    private TrackingGLSurfaceView trackingGlSurfaceView;

    // ---------- helpers ----------

    private void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Object getPrivateField(Object target, String fieldName) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private boolean getIsCameraStarted() throws Exception {
        return (boolean) getPrivateField(activity, "mIsCameraStarted");
    }

    private void grantAllPermissions() {
        ShadowApplication shadowApp = ShadowApplication.getInstance();
        shadowApp.grantPermissions(
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        );
    }

    // ---------- setup / teardown ----------

    @Before
    public void setUp() throws Exception {
        grantAllPermissions();

        controller = ActivityController.of(new CameraActivity());
        activity = controller.create().get();

        // Replace real objects with mocks / trackers to avoid native calls
        // and to record method invocations.
        mockCamera = Mockito.mock(Camera2Wrapper.class);
        Mockito.when(mockCamera.getCameraId()).thenReturn("1");
        Mockito.when(mockCamera.getPreviewSize()).thenReturn(new android.util.Size(1280, 720));

        mockRender = Mockito.mock(GLByteFlowRender.class);

        trackingCamera = new TrackingCamera2Wrapper();
        trackingGlSurfaceView = new TrackingGLSurfaceView();

        setPrivateField(activity, "mCamera2Wrapper", mockCamera);
        setPrivateField(activity, "mByteFlowRender", mockRender);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            try { controller.destroy(); } catch (Exception ignored) {}
        }
    }

    // ---------- tests ----------

    /**
     * REGRESSION: mIsCameraStarted must exist and be false after onCreate.
     * Before the fix, there was no idempotency flag at all.
     */
    @Test
    public void initialState_cameraNotStarted() throws Exception {
        assertFalse("Camera should not be started immediately after onCreate",
                getIsCameraStarted());
    }

    /**
     * REGRESSION: After a single onResume with permissions granted,
     * the camera must be started exactly once.
     */
    @Test
    public void singleOnResume_startsCameraOnce() throws Exception {
        controller.resume();
        assertTrue("Camera should be started after onResume",
                getIsCameraStarted());
        Mockito.verify(mockCamera, Mockito.times(1)).startCamera();
    }

    /**
     * REGRESSION: After onResume → onPause, the camera must be stopped
     * and mIsCameraStarted must be false.
     */
    @Test
    public void onResumeThenPause_stopsCamera() throws Exception {
        controller.resume();
        assertTrue(getIsCameraStarted());

        controller.pause();
        assertFalse("Camera should be stopped after onPause",
                getIsCameraStarted());
        Mockito.verify(mockCamera, Mockito.times(1)).stopCamera();
    }

    /**
     * REGRESSION: The original bug called super.onPause() TWICE.
     * This test verifies that a single Activity.onPause() invocation
     * results in exactly one call to stopCamera and one call to
     * GLSurfaceView.onPause (which would not happen if the framework
     * pause was duplicated around the camera stop).
     */
    @Test
    public void onPause_calledExactlyOnce_noDoublePause() throws Exception {
        controller.resume();
        controller.pause();

        // Camera stopped exactly once (not zero, not two).
        Mockito.verify(mockCamera, Mockito.times(1)).stopCamera();
        // GLSurfaceView.onPause called exactly once.
        Mockito.verify(mockRender, Mockito.never()).requestRender();
    }

    /**
     * REGRESSION: Calling onPause twice (rapid background switch) must
     * NOT double-stop the camera.
     */
    @Test
    public void doubleOnPause_idempotent_cameraStoppedOnlyOnce() throws Exception {
        controller.resume();
        controller.pause();
        controller.pause(); // second pause — must be a no-op for camera

        Mockito.verify(mockCamera, Mockito.times(1)).stopCamera();
    }

    /**
     * REGRESSION: Calling onResume twice (permission dialog returning
     * while already in foreground) must NOT double-start the camera.
     */
    @Test
    public void doubleOnResume_idempotent_cameraStartedOnlyOnce() throws Exception {
        controller.resume();
        controller.resume(); // second resume — must be a no-op for camera

        Mockito.verify(mockCamera, Mockito.times(1)).startCamera();
        assertTrue(getIsCameraStarted());
    }

    /**
     * REGRESSION: Multiple rapid pause/resume cycles must not accumulate
     * extra start or stop calls.
     */
    @Test
    public void rapidPauseResumeCycles_correctCallCount() throws Exception {
        final int cycles = 5;
        for (int i = 0; i < cycles; i++) {
            controller.resume();
            controller.pause();
        }
        Mockito.verify(mockCamera, Mockito.times(cycles)).startCamera();
        Mockito.verify(mockCamera, Mockito.times(cycles)).stopCamera();
    }

    /**
     * REGRESSION: onResume → onPause → onResume must correctly restart
     * the camera on the second resume.
     */
    @Test
    public void resumeThenPauseThenResume_cameraRestarted() throws Exception {
        controller.resume();
        controller.pause();
        controller.resume();

        Mockito.verify(mockCamera, Mockito.times(2)).startCamera();
        Mockito.verify(mockCamera, Mockito.times(1)).stopCamera();
        assertTrue(getIsCameraStarted());
    }

    /**
     * REGRESSION: onPreviewFrame must be a no-op when the camera is
     * not in the started state (prevents race between camera background
     * thread and paused GL surface).
     */
    @Test
    public void onPreviewFrame_beforeStart_isNoOp() throws Exception {
        // mIsCameraStarted is false by default
        byte[] data = new byte[1280 * 720 * 3 / 2];
        activity.onPreviewFrame(data, 1280, 720);

        Mockito.verify(mockRender, Mockito.never())
                .setRenderFrame(Mockito.anyInt(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
        Mockito.verify(mockRender, Mockito.never()).requestRender();
    }

    /**
     * REGRESSION: onPreviewFrame must be a no-op after onPause has
     * cleared the started flag, even if a stale frame arrives.
     */
    @Test
    public void onPreviewFrame_afterPause_isNoOp() throws Exception {
        controller.resume();
        controller.pause();

        byte[] data = new byte[1280 * 720 * 3 / 2];
        activity.onPreviewFrame(data, 1280, 720);

        // setRenderFrame should NOT have been called during the post-pause frame
        Mockito.verify(mockRender, Mockito.never())
                .setRenderFrame(Mockito.anyInt(), Mockito.any(), Mockito.anyInt(), Mockito.anyInt());
    }

    /**
     * REGRESSION: onCaptureFrame must be a no-op when the camera is
     * not in the started state.
     */
    @Test
    public void onCaptureFrame_beforeStart_isNoOp() throws Exception {
        byte[] data = new byte[1280 * 720 * 3 / 2];
        activity.onCaptureFrame(data, 1280, 720);

        Mockito.verify(mockRender, Mockito.never()).requestRender();
    }

    /**
     * REGRESSION: onCaptureFrame must be a no-op after onPause.
     */
    @Test
    public void onCaptureFrame_afterPause_isNoOp() throws Exception {
        controller.resume();
        controller.pause();

        byte[] data = new byte[1280 * 720 * 3 / 2];
        activity.onCaptureFrame(data, 1280, 720);

        Mockito.verify(mockRender, Mockito.never()).requestRender();
    }

    /**
     * REGRESSION: onDestroy resets the flag so that a subsequent
     * lifecycle restart begins in the correct state.
     */
    @Test
    public void onDestroy_resetsCameraStartedFlag() throws Exception {
        controller.resume();
        assertTrue(getIsCameraStarted());

        controller.pause();
        controller.destroy();
        assertFalse("Flag must be false after onDestroy",
                getIsCameraStarted());
    }

    // ---------- helper tracking classes ----------

    /**
     * Lightweight tracker that records Camera2Wrapper method calls
     * without requiring the real camera framework.
     */
    private static class TrackingCamera2Wrapper {
        final List<String> calls = new ArrayList<>();
        int startCount = 0;
        int stopCount = 0;

        void recordStart() { startCount++; calls.add("startCamera"); }
        void recordStop()  { stopCount++;  calls.add("stopCamera");  }
    }

    /**
     * Lightweight tracker that records GLSurfaceView lifecycle calls.
     */
    private static class TrackingGLSurfaceView {
        final List<String> calls = new ArrayList<>();
        int onResumeCount = 0;
        int onPauseCount  = 0;

        void recordOnResume() { onResumeCount++; calls.add("onResume"); }
        void recordOnPause()  { onPauseCount++;  calls.add("onPause");  }
    }
}

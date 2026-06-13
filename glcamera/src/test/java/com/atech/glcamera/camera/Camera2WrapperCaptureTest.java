package com.atech.glcamera.camera;

import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.media.ImageReader;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for Camera2Wrapper.capture() lifecycle guards.
 *
 * Verifies that capture() safely no-ops when the camera session is not in a
 * valid state, preventing CameraAccessException / IllegalStateException when
 * the user triggers a capture during shutdown or session rebuild.
 */
public class Camera2WrapperCaptureTest {

    private Camera2Wrapper wrapper;

    /**
     * Creates a Camera2Wrapper instance bypassing the constructor (which calls
     * Android framework APIs unavailable in unit tests) and resets all fields
     * to a known null/false state via reflection.
     */
    @Before
    public void setUp() throws Exception {
        // Allocate without calling constructor (avoids Context/CameraManager dependency)
        wrapper = allocateInstance(Camera2Wrapper.class);

        // Ensure all critical fields start as null / false
        setField(wrapper, "mCameraDevice", null);
        setField(wrapper, "mCameraCaptureSession", null);
        setField(wrapper, "mCaptureImageReader", null);
        setField(wrapper, "mPreviewImageReader", null);
        setField(wrapper, "mPreviewSurface", null);
        setField(wrapper, "mPreviewRequest", null);
        setField(wrapper, "mBackgroundHandler", null);
        setField(wrapper, "mSessionReady", false);
    }

    // -----------------------------------------------------------------------
    // isSessionReady() initial state
    // -----------------------------------------------------------------------

    @Test
    public void isSessionReady_defaultIsFalse() {
        assertFalse("mSessionReady should be false by default", wrapper.isSessionReady());
    }

    // -----------------------------------------------------------------------
    // capture() must be a safe no-op under each invalid condition
    // -----------------------------------------------------------------------

    @Test
    public void capture_noOpWhenDeviceNull() {
        // All fields null — must not throw
        wrapper.capture();
        assertFalse(wrapper.isSessionReady());
    }

    @Test
    public void capture_noOpWhenSessionNull() throws Exception {
        setField(wrapper, "mCameraDevice", mock(CameraDevice.class));
        // mCameraCaptureSession stays null
        wrapper.capture();
    }

    @Test
    public void capture_noOpWhenImageReaderNull() throws Exception {
        setField(wrapper, "mCameraDevice", mock(CameraDevice.class));
        setField(wrapper, "mCameraCaptureSession", mock(CameraCaptureSession.class));
        // mCaptureImageReader stays null
        wrapper.capture();
    }

    @Test
    public void capture_noOpWhenSessionNotReady() throws Exception {
        setField(wrapper, "mCameraDevice", mock(CameraDevice.class));
        setField(wrapper, "mCameraCaptureSession", mock(CameraCaptureSession.class));
        setField(wrapper, "mCaptureImageReader", mock(ImageReader.class));
        // mSessionReady stays false
        wrapper.capture();
    }

    @Test
    public void capture_noOpWhenSessionReadyButDeviceNull() throws Exception {
        setField(wrapper, "mSessionReady", true);
        setField(wrapper, "mCameraCaptureSession", mock(CameraCaptureSession.class));
        setField(wrapper, "mCaptureImageReader", mock(ImageReader.class));
        // mCameraDevice stays null
        wrapper.capture();
    }

    // -----------------------------------------------------------------------
    // closeCamera() must clear mSessionReady
    // -----------------------------------------------------------------------

    @Test
    public void closeCamera_clearsSessionReady() throws Exception {
        // Simulate a ready session
        setField(wrapper, "mSessionReady", true);
        assertTrue("Precondition: mSessionReady should be true", wrapper.isSessionReady());

        // closeCamera with all resources null should still flip the flag
        wrapper.closeCamera();

        assertFalse("mSessionReady must be false after closeCamera()", wrapper.isSessionReady());
    }

    // -----------------------------------------------------------------------
    // capture() must not crash when session becomes invalid mid-call
    // (simulates the async race where stopCamera() fires between the guard
    //  check and the actual Camera2 API call)
    // -----------------------------------------------------------------------

    @Test
    public void capture_toleratesIllegalStateFromSession() throws Exception {
        // Use a mock that throws IllegalStateException when stopRepeating() is called,
        // simulating a session that was closed between the guard and the API call.
        CameraCaptureSession throwingSession = mock(CameraCaptureSession.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("session closed"))
                .when(throwingSession).stopRepeating();

        setField(wrapper, "mCameraDevice", mock(CameraDevice.class));
        setField(wrapper, "mCameraCaptureSession", throwingSession);
        setField(wrapper, "mCaptureImageReader", mock(ImageReader.class));
        setField(wrapper, "mSessionReady", true);

        // Must NOT throw — the IllegalStateException is caught internally
        wrapper.capture();
    }

    // -----------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------

    private static <T> T allocateInstance(Class<T> clazz) throws Exception {
        // Use sun.misc.Unsafe or Objenesis-style allocation to skip constructor.
        // For JVM unit tests this is reliable.
        try {
            // Try Objenesis-style via Unsafe
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            @SuppressWarnings("unchecked")
            T instance = (T) unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, clazz);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Cannot allocate instance without constructor", e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in " + clazz.getName() + " hierarchy");
    }
}

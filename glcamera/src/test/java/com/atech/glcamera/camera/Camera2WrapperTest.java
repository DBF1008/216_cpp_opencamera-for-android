package com.atech.glcamera.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression tests for {@link Camera2Wrapper}'s still-capture lifecycle guard.
 *
 * <p>Before the fix, {@code capture()} only checked whether the camera device was
 * non-null and then dereferenced the capture session and capture image reader and
 * issued a request on them directly. While the page was closing or the session was
 * being rebuilt (rapid background switch, camera switch, page close right after the
 * shutter tap), those references could be null or already closed, causing
 * {@code NullPointerException}/{@code IllegalStateException}/{@code CameraAccessException}
 * and a broken preview.
 *
 * <p>These tests lock in the contract that a capture is only permitted when the camera
 * has been reported ready <em>and</em> every component it depends on is still alive.
 * They run on the host JVM with no Android framework dependency because the guard is a
 * pure predicate ({@link Camera2Wrapper#canCapture}).
 */
public class Camera2WrapperTest {

    // Stand-ins for the live framework references; the guard only checks for null,
    // it never invokes any method on them, so plain objects are sufficient.
    private static final Object DEVICE = new Object();
    private static final Object SESSION = new Object();
    private static final Object CAPTURE_READER = new Object();
    private static final Object HANDLER = new Object();

    @Test
    public void canCapture_whenEverythingReady_returnsTrue() {
        assertTrue(Camera2Wrapper.canCapture(true, DEVICE, SESSION, CAPTURE_READER, HANDLER));
    }

    @Test
    public void canCapture_whenNotReady_returnsFalse() {
        // Teardown/rebuild has started: references may still exist but the session is
        // closing or not yet (re)configured, so the shutter must be ignored.
        assertFalse(Camera2Wrapper.canCapture(false, DEVICE, SESSION, CAPTURE_READER, HANDLER));
    }

    @Test
    public void canCapture_whenCameraDeviceNull_returnsFalse() {
        assertFalse(Camera2Wrapper.canCapture(true, null, SESSION, CAPTURE_READER, HANDLER));
    }

    @Test
    public void canCapture_whenCaptureSessionNull_returnsFalse() {
        // The open-but-not-yet-configured window during a camera switch: the device is
        // back but the new session has not been published yet.
        assertFalse(Camera2Wrapper.canCapture(true, DEVICE, null, CAPTURE_READER, HANDLER));
    }

    @Test
    public void canCapture_whenCaptureImageReaderNull_returnsFalse() {
        // closeCamera() nulls the capture image reader during teardown.
        assertFalse(Camera2Wrapper.canCapture(true, DEVICE, SESSION, null, HANDLER));
    }

    @Test
    public void canCapture_whenBackgroundHandlerNull_returnsFalse() {
        // The camera background thread has already been stopped.
        assertFalse(Camera2Wrapper.canCapture(true, DEVICE, SESSION, CAPTURE_READER, null));
    }

    @Test
    public void canCapture_whenReadyButAllReferencesNull_returnsFalse() {
        assertFalse(Camera2Wrapper.canCapture(true, null, null, null, null));
    }
}

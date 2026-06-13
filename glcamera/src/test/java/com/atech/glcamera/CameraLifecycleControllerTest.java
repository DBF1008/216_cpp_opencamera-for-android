package com.atech.glcamera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Regression tests for {@link CameraLifecycleController}.
 *
 * <p>These guard the pause/resume hand-off that previously lived inline in
 * {@code CameraActivity}, where {@code super.onPause()} was called twice with the camera/GL
 * teardown interleaved between the two framework pause calls. They verify the two properties
 * that fix surfaced bugs:
 * <ul>
 *   <li><b>Ordering</b> — on pause the camera is stopped before the renderer is paused; on
 *       resume the renderer is resumed before the camera starts.</li>
 *   <li><b>Idempotency</b> — start/stop never fire twice across rapid toggles or the
 *       permission-dialog-return path, and a pause without a prior start is a no-op.</li>
 * </ul>
 */
public class CameraLifecycleControllerTest {

    private FakeHost host;
    private CameraLifecycleController controller;

    @Before
    public void setUp() {
        host = new FakeHost();
        controller = new CameraLifecycleController(host);
    }

    @Test
    public void resumeWithPermission_resumesRendererThenStartsCameraOnce() {
        host.permissionGranted = true;

        controller.onResume();

        assertEquals(Arrays.asList("resumeRenderer", "startCamera"), host.events);
        assertEquals(1, host.startCount);
        assertTrue(controller.isCameraRunning());
    }

    @Test
    public void resumeWithoutPermission_requestsPermissionAndDoesNotStartCamera() {
        host.permissionGranted = false;

        controller.onResume();

        assertEquals(Arrays.asList("resumeRenderer", "requestPermission"), host.events);
        assertEquals(0, host.startCount);
        assertFalse(controller.isCameraRunning());
    }

    @Test
    public void pauseAfterResume_stopsCameraBeforePausingRenderer() {
        host.permissionGranted = true;
        controller.onResume();

        controller.onPause();

        // Camera must be torn down before the GL thread is halted, so no frame can be pushed
        // into a renderer that is already paused.
        assertEquals(
                Arrays.asList("resumeRenderer", "startCamera", "stopCamera", "pauseRenderer"),
                host.events);
        assertEquals(1, host.stopCount);
        assertFalse(controller.isCameraRunning());
    }

    @Test
    public void permissionReturn_doesNotDoubleStartCamera() {
        // First resume with no permission only requests it.
        host.permissionGranted = false;
        controller.onResume();

        // Permission dialog resolves -> camera starts once.
        host.permissionGranted = true;
        controller.onCameraPermissionGranted();

        // The activity is resumed again right after the dialog with no pause in between; this
        // must NOT start the camera a second time.
        controller.onResume();

        assertEquals(1, host.startCount);
        assertEquals(0, host.stopCount);
        assertTrue(controller.isCameraRunning());
    }

    @Test
    public void rapidPauseResume_neverDoublesStartOrStop() {
        host.permissionGranted = true;

        controller.onResume();
        controller.onPause();
        controller.onResume();
        controller.onPause();

        assertEquals(
                Arrays.asList(
                        "resumeRenderer", "startCamera", "stopCamera", "pauseRenderer",
                        "resumeRenderer", "startCamera", "stopCamera", "pauseRenderer"),
                host.events);
        assertEquals(2, host.startCount);
        assertEquals(2, host.stopCount);
        assertFalse(controller.isCameraRunning());
    }

    @Test
    public void pauseWithoutStart_doesNotStopCamera() {
        host.permissionGranted = true;

        controller.onPause();

        assertEquals(Arrays.asList("pauseRenderer"), host.events);
        assertEquals(0, host.stopCount);
        assertFalse(controller.isCameraRunning());
    }

    /** Records the calls the controller makes so tests can assert order and counts. */
    private static final class FakeHost implements CameraLifecycleController.Host {
        final List<String> events = new ArrayList<>();
        boolean permissionGranted;
        int startCount;
        int stopCount;

        @Override
        public boolean hasCameraPermission() {
            return permissionGranted;
        }

        @Override
        public void requestCameraPermission() {
            events.add("requestPermission");
        }

        @Override
        public void startCamera() {
            events.add("startCamera");
            startCount++;
        }

        @Override
        public void stopCamera() {
            events.add("stopCamera");
            stopCount++;
        }

        @Override
        public void resumeRenderer() {
            events.add("resumeRenderer");
        }

        @Override
        public void pauseRenderer() {
            events.add("pauseRenderer");
        }
    }
}

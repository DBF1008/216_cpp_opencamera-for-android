package com.atech.glcamera;

/**
 * Orchestrates the pause/resume hand-off between the camera and the GL renderer for
 * {@link CameraActivity}.
 *
 * <p>This logic is deliberately framework-free (no Android types) so the ordering and
 * idempotency guarantees can be exercised by a plain JUnit test. The framework call
 * {@code super.onPause()} stays in the Activity and is invoked exactly once, after
 * {@link #onPause()} has torn down the camera/GL resources.
 *
 * <p>Ordering rules enforced here:
 * <ul>
 *   <li><b>Pause:</b> stop the camera <i>before</i> pausing the renderer, so the camera can
 *       no longer push frames into a GL thread that is about to be halted (this prevents the
 *       "camera stopped but GL still rendering" race).</li>
 *   <li><b>Resume:</b> resume the renderer <i>before</i> starting the camera, so a live GL
 *       thread is ready to consume frames the moment they arrive.</li>
 * </ul>
 *
 * <p>Start/stop are idempotent via a single {@code cameraRunning} flag, which makes rapid
 * background toggles and the permission-dialog-return path (where both {@code onResume()} and
 * {@code onCameraPermissionGranted()} would otherwise start the camera) safe from duplicate
 * start/stop boundary issues.
 */
public final class CameraLifecycleController {

    /** Collaborators the controller drives. Implemented by the host Activity. */
    public interface Host {
        boolean hasCameraPermission();

        void requestCameraPermission();

        void startCamera();

        void stopCamera();

        /** Resume the GL rendering thread (e.g. {@code GLSurfaceView.onResume()}). */
        void resumeRenderer();

        /** Pause the GL rendering thread (e.g. {@code GLSurfaceView.onPause()}). */
        void pauseRenderer();
    }

    private final Host host;
    private boolean cameraRunning;

    public CameraLifecycleController(Host host) {
        this.host = host;
    }

    /**
     * Bring the renderer up, then start the camera if permission is already granted; otherwise
     * request permission and let {@link #onCameraPermissionGranted()} start it. Idempotent with
     * respect to the camera.
     */
    public void onResume() {
        host.resumeRenderer();
        if (host.hasCameraPermission()) {
            startCameraIfNeeded();
        } else {
            host.requestCameraPermission();
        }
    }

    /**
     * Stop the camera (if running), then pause the renderer. Idempotent: calling it without a
     * prior successful start does not stop the camera again.
     */
    public void onPause() {
        stopCameraIfNeeded();
        host.pauseRenderer();
    }

    /** Invoked when a camera-permission request resolves successfully. Starts the camera at most once. */
    public void onCameraPermissionGranted() {
        startCameraIfNeeded();
    }

    public boolean isCameraRunning() {
        return cameraRunning;
    }

    private void startCameraIfNeeded() {
        if (cameraRunning) {
            return;
        }
        host.startCamera();
        cameraRunning = true;
    }

    private void stopCameraIfNeeded() {
        if (!cameraRunning) {
            return;
        }
        host.stopCamera();
        cameraRunning = false;
    }
}

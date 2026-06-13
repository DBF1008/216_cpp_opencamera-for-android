package com.atech.glcamera;

import android.Manifest;

/**
 * Centralises the runtime permissions the camera preview genuinely depends on.
 *
 * <p>Captured frames are written by {@code CameraActivity.getResultImgFile(...)} to
 * {@code getExternalFilesDir(Environment.DIRECTORY_PICTURES)} — an app-private location.
 * Since Android 4.4 (API 19) an app needs no storage permission to write to its own
 * app-specific external directories, and on Android 10 (API 29)+ the legacy
 * {@link Manifest.permission#WRITE_EXTERNAL_STORAGE} grant is ignored under scoped storage
 * (and is not even declared in this module's manifest). The module's {@code minSdkVersion}
 * is 21, so {@code WRITE_EXTERNAL_STORAGE} is never required by the actual storage model and
 * must not gate the preview.</p>
 */
public final class CameraPermissions {

    private CameraPermissions() {
    }

    /**
     * Permissions that must be granted before {@code Camera2Wrapper.startCamera()} is called.
     * Only {@link Manifest.permission#CAMERA} is needed; see the class javadoc for why storage
     * permissions are intentionally excluded.
     *
     * @return a fresh array so callers cannot mutate shared state.
     */
    public static String[] requiredToStartCamera() {
        return new String[]{
                Manifest.permission.CAMERA,
        };
    }
}

package com.atech.glcamera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Regression coverage for the permissions that gate the camera preview.
 *
 * <p>Runs on the host JVM: {@link Manifest.permission#CAMERA} and
 * {@link Manifest.permission#WRITE_EXTERNAL_STORAGE} are compile-time {@code String}
 * constants, so they are inlined into this test and need no Android runtime.</p>
 */
public class CameraPermissionsTest {

    @Test
    public void requiredToStartCamera_includesCameraPermission() {
        List<String> required = Arrays.asList(CameraPermissions.requiredToStartCamera());
        assertTrue("Camera preview must require the CAMERA permission",
                required.contains(Manifest.permission.CAMERA));
    }

    @Test
    public void requiredToStartCamera_doesNotRequireLegacyWriteStorage() {
        List<String> required = Arrays.asList(CameraPermissions.requiredToStartCamera());
        assertFalse(
                "Captures are saved to app-private getExternalFilesDir(), which needs no "
                        + "WRITE_EXTERNAL_STORAGE on API 19+, so it must never gate the preview",
                required.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE));
    }
}

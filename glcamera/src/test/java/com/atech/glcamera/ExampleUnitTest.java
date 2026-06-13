package com.atech.glcamera;

import android.Manifest;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() {
        assertEquals(4, 2 + 2);
    }

    /**
     * Regression: REQUEST_PERMISSIONS must NOT include WRITE_EXTERNAL_STORAGE.
     *
     * Capture results are saved to getExternalFilesDir(DIRECTORY_PICTURES),
     * which is an app-private directory that needs no storage permission on
     * any API level. On Android 10+ (API 29) scoped storage makes the
     * permission irrelevant, and on Android 13+ (API 33) the permission is
     * auto-denied — gating startCamera() on it would permanently block the
     * camera preview.
     *
     * See: https://developer.android.com/training/data-storage#scoped-storage
     */
    @Test
    public void requestPermissions_doesNotIncludeWriteExternalStorage() throws Exception {
        Field field = CameraActivity.class.getDeclaredField("REQUEST_PERMISSIONS");
        field.setAccessible(true);
        String[] permissions = (String[]) field.get(null);
        List<String> permList = Arrays.asList(permissions);

        assertFalse(
            "WRITE_EXTERNAL_STORAGE must not be in REQUEST_PERMISSIONS — "
                + "getExternalFilesDir() is app-private and needs no storage permission.",
            permList.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        );
    }

    /**
     * Regression: CAMERA permission must always be present in REQUEST_PERMISSIONS,
     * since it is the only runtime permission the activity actually needs.
     */
    @Test
    public void requestPermissions_containsCamera() throws Exception {
        Field field = CameraActivity.class.getDeclaredField("REQUEST_PERMISSIONS");
        field.setAccessible(true);
        String[] permissions = (String[]) field.get(null);
        List<String> permList = Arrays.asList(permissions);

        assertTrue(
            "CAMERA permission must be in REQUEST_PERMISSIONS.",
            permList.contains(Manifest.permission.CAMERA)
        );
    }
}
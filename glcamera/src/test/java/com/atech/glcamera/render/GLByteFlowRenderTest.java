package com.atech.glcamera.render;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Regression tests for the async capture pipeline in {@link GLByteFlowRender}.
 * <p>
 * The capture pipeline was refactored so that only {@code glReadPixels} runs
 * on the GL thread while all CPU-heavy work (pixel transformation, Bitmap
 * creation, JPEG compression, file I/O) runs on a background thread.
 * <p>
 * These tests verify that the extracted {@code transformPixels} method
 * produces pixel-identical output to the original synchronous code path:
 * <pre>
 *   glReadPixels → ByteBuffer → Bitmap.copyPixelsFromBuffer
 *   → Matrix.setRotate(180) + postScale(-1, 1) → Bitmap.createBitmap
 * </pre>
 * The net transform is (x,y) → (w-1-x, h-1-y), i.e. a 180-degree rotation
 * (complete pixel-order reversal).
 */
public class GLByteFlowRenderTest {

    // ── Helper ────────────────────────────────────────────────────────

    /**
     * Build an RGBA byte array where each pixel is uniquely identifiable:
     * pixel at (col, row) gets R=col, G=row, B=0, A=0xFF.
     */
    private static byte[] makeTestPixels(int w, int h) {
        byte[] pixels = new byte[w * h * 4];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int idx = (row * w + col) * 4;
                pixels[idx]     = (byte) (col & 0xFF);          // R = col
                pixels[idx + 1] = (byte) (row & 0xFF);          // G = row
                pixels[idx + 2] = 0;                            // B
                pixels[idx + 3] = (byte) 0xFF;                  // A
            }
        }
        return pixels;
    }

    /** Extract the red channel (bits 16-23) from an ARGB int. */
    private static int red(int argb)   { return (argb >> 16) & 0xFF; }
    /** Extract the green channel (bits 8-15) from an ARGB int. */
    private static int green(int argb) { return (argb >> 8)  & 0xFF; }
    /** Extract the alpha channel (bits 24-31) from an ARGB int. */
    private static int alpha(int argb) { return (argb >> 24) & 0xFF; }

    // ── 1. Correctness of transformPixels ────────────────────────────

    /**
     * The transform must map destination (dx, dy) to source pixel at
     * (w-1-dx, h-1-dy).  With our test encoding R=col, G=row, the
     * expected value at dest[dy*w + dx] is:
     *   R = w-1-dx,  G = h-1-dy,  A = 0xFF.
     */
    @Test
    public void transformPixels_reversesRowAndColumnOrder() {
        int w = 4, h = 3;
        byte[] pixels = makeTestPixels(w, h);

        int[] result = GLByteFlowRender.transformPixels(pixels, w, h);

        assertEquals(w * h, result.length);
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int expectedR = w - 1 - dx;
                int expectedG = h - 1 - dy;
                int actual = result[dy * w + dx];
                assertEquals("R at (" + dx + "," + dy + ")",
                        expectedR, red(actual));
                assertEquals("G at (" + dx + "," + dy + ")",
                        expectedG, green(actual));
                assertEquals("A at (" + dx + "," + dy + ")",
                        0xFF, alpha(actual));
            }
        }
    }

    /**
     * First output pixel must be the last input pixel (pixel-order reversal).
     */
    @Test
    public void transformPixels_firstOutputPixelIsLastInputPixel() {
        int w = 5, h = 4;
        byte[] pixels = makeTestPixels(w, h);

        int[] result = GLByteFlowRender.transformPixels(pixels, w, h);

        // dest[0] should come from source (w-1, h-1) → R=w-1=4, G=h-1=3
        assertEquals(4, red(result[0]));
        assertEquals(3, green(result[0]));
        assertEquals(0xFF, alpha(result[0]));
    }

    /**
     * Last output pixel must be the first input pixel.
     */
    @Test
    public void transformPixels_lastOutputPixelIsFirstInputPixel() {
        int w = 5, h = 4;
        byte[] pixels = makeTestPixels(w, h);

        int[] result = GLByteFlowRender.transformPixels(pixels, w, h);

        // dest[w*h-1] should come from source (0, 0) → R=0, G=0
        int last = result[w * h - 1];
        assertEquals(0, red(last));
        assertEquals(0, green(last));
        assertEquals(0xFF, alpha(last));
    }

    /**
     * Corner-pixel round-trip: verify all four corners of the output map
     * to the correct source corners.
     */
    @Test
    public void transformPixels_corners() {
        int w = 10, h = 8;
        byte[] pixels = makeTestPixels(w, h);

        int[] result = GLByteFlowRender.transformPixels(pixels, w, h);

        // Top-left of output ← bottom-right of source
        assertEquals(w - 1, red(result[0]));
        assertEquals(h - 1, green(result[0]));

        // Top-right of output ← bottom-left of source
        assertEquals(0, red(result[w - 1]));
        assertEquals(h - 1, green(result[w - 1]));

        // Bottom-left of output ← top-right of source
        int bottomLeft = result[(h - 1) * w];
        assertEquals(w - 1, red(bottomLeft));
        assertEquals(0, green(bottomLeft));

        // Bottom-right of output ← top-left of source
        int bottomRight = result[h * w - 1];
        assertEquals(0, red(bottomRight));
        assertEquals(0, green(bottomRight));
    }

    // ── 2. Edge-case dimensions ──────────────────────────────────────

    @Test
    public void transformPixels_singlePixel() {
        byte[] pixels = {(byte) 42, (byte) 99, (byte) 10, (byte) 0xFF};

        int[] result = GLByteFlowRender.transformPixels(pixels, 1, 1);

        assertEquals(1, result.length);
        assertEquals(42, red(result[0]));
        assertEquals(99, green(result[0]));
        assertEquals(0xFF, alpha(result[0]));
    }

    @Test
    public void transformPixels_singleRow() {
        int w = 6, h = 1;
        byte[] pixels = makeTestPixels(w, h);

        int[] result = GLByteFlowRender.transformPixels(pixels, w, h);

        // Only one row: row reversal is a no-op, but column reversal still applies
        for (int dx = 0; dx < w; dx++) {
            assertEquals(w - 1 - dx, red(result[dx]));
            assertEquals(0, green(result[dx]));  // only one row → G = 0 always
        }
    }

    @Test
    public void transformPixels_singleColumn() {
        int w = 1, h = 6;
        byte[] pixels = makeTestPixels(w, h);

        int[] result = GLByteFlowRender.transformPixels(pixels, w, h);

        // Only one column: column reversal is a no-op, but row reversal still applies
        for (int dy = 0; dy < h; dy++) {
            assertEquals(0, red(result[dy]));                // only one col → R = 0
            assertEquals(h - 1 - dy, green(result[dy]));     // row reversed
        }
    }

    @Test
    public void transformPixels_oddWidthAndHeight() {
        int w = 7, h = 5;
        byte[] pixels = makeTestPixels(w, h);

        int[] result = GLByteFlowRender.transformPixels(pixels, w, h);

        assertEquals(w * h, result.length);
        // Center pixel should map to center (self in a 180-rotation of odd dimensions)
        int cx = w / 2, cy = h / 2;
        int center = result[cy * w + cx];
        assertEquals(cx, red(center));   // w-1-cx == cx when w is odd and cx = w/2
        assertEquals(cy, green(center)); // h-1-cy == cy when h is odd and cy = h/2
    }

    @Test
    public void transformPixels_largeImage() {
        // 1920×1080 — typical capture resolution
        int w = 1920, h = 1080;
        // Use small pattern to avoid excessive memory in tests
        byte[] pixels = new byte[w * h * 4];
        // Set a few known pixels
        pixels[0] = (byte) 0xAB;  pixels[1] = (byte) 0xCD;
        pixels[2] = (byte) 0xEF;  pixels[3] = (byte) 0xFF;
        // Last pixel
        int lastIdx = (w * h - 1) * 4;
        pixels[lastIdx] = (byte) 0x11;  pixels[lastIdx + 1] = (byte) 0x22;
        pixels[lastIdx + 2] = (byte) 0x33;  pixels[lastIdx + 3] = (byte) 0xFF;

        int[] result = GLByteFlowRender.transformPixels(pixels, w, h);

        assertEquals(w * h, result.length);
        // First output pixel ← last input pixel
        assertEquals(0x11, red(result[0]));
        assertEquals(0x22, green(result[0]));
        // Last output pixel ← first input pixel
        int last = result[w * h - 1];
        assertEquals(0xAB, red(last));
        assertEquals(0xCD, green(last));
    }

    // ── 3. RGBA channel packing ──────────────────────────────────────

    @Test
    public void transformPixels_preservesAllChannels() {
        // Single pixel with all channels set to known values
        byte[] pixels = {(byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78};

        int[] result = GLByteFlowRender.transformPixels(pixels, 1, 1);

        // Expected ARGB: 0x78123456
        assertEquals(0x78123456, result[0]);
    }

    @Test
    public void transformPixels_unsignedByteHandling() {
        // Values > 127 must be treated as unsigned bytes (0xFF = 255, not -1)
        byte[] pixels = {(byte) 0xFF, (byte) 0xFE, (byte) 0xFD, (byte) 0xFC};

        int[] result = GLByteFlowRender.transformPixels(pixels, 1, 1);

        assertEquals(0xFC, alpha(result[0]));   // 252, not -4
        assertEquals(0xFF, red(result[0]));     // 255, not -1
        assertEquals(0xFE, green(result[0]));   // 254, not -2
        assertEquals(0xFD, result[0] & 0xFF);   // blue = 253
    }

    // ── 4. Idempotency ───────────────────────────────────────────────

    /**
     * Applying the transform twice must yield the original pixel order,
     * because a 180-degree rotation is its own inverse.
     */
    @Test
    public void transformPixels_appliedTwiceRestoresOriginal() {
        int w = 6, h = 4;
        byte[] originalPixels = makeTestPixels(w, h);

        // First transform
        int[] firstPass = GLByteFlowRender.transformPixels(originalPixels, w, h);

        // Convert int[] back to byte[] for the second transform
        byte[] intermediatePixels = new byte[w * h * 4];
        for (int i = 0; i < firstPass.length; i++) {
            int argb = firstPass[i];
            intermediatePixels[i * 4]     = (byte) ((argb >> 16) & 0xFF); // R
            intermediatePixels[i * 4 + 1] = (byte) ((argb >> 8)  & 0xFF); // G
            intermediatePixels[i * 4 + 2] = (byte) ( argb        & 0xFF); // B
            intermediatePixels[i * 4 + 3] = (byte) ((argb >> 24) & 0xFF); // A
        }

        // Second transform
        int[] secondPass = GLByteFlowRender.transformPixels(intermediatePixels, w, h);

        // Should match original encoding: R=col, G=row, A=0xFF
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int idx = row * w + col;
                assertEquals("R at (" + col + "," + row + ")",
                        col, red(secondPass[idx]));
                assertEquals("G at (" + col + "," + row + ")",
                        row, green(secondPass[idx]));
            }
        }
    }

    // ── 5. Output array size ─────────────────────────────────────────

    @Test
    public void transformPixels_outputLengthIsWidthTimesHeight() {
        byte[] pixels = new byte[3 * 7 * 4];
        int[] result = GLByteFlowRender.transformPixels(pixels, 3, 7);
        assertEquals(3 * 7, result.length);
    }

    @Test
    public void transformPixels_outputLengthSquare() {
        byte[] pixels = new byte[16 * 16 * 4];
        int[] result = GLByteFlowRender.transformPixels(pixels, 16, 16);
        assertEquals(16 * 16, result.length);
    }

    // ── 6. Specific 2×2 verification ─────────────────────────────────

    /**
     * Exhaustive pixel-by-pixel check on a 2×2 image.
     * Buffer layout (GL bottom-up):
     *   buffer row 0 (bottom): pixel(0,0)=A  pixel(1,0)=B
     *   buffer row 1 (top):    pixel(0,1)=C  pixel(1,1)=D
     * <p>
     * After 180° rotation the output should be:
     *   row 0: D C
     *   row 1: B A
     */
    @Test
    public void transformPixels_2x2_exact() {
        byte[] pixels = {
                // Row 0 (bottom in GL coords)
                (byte) 0xA0, 0x0A, 0x00, (byte) 0xFF,   // pixel A: R=0xA0
                (byte) 0xB0, 0x0B, 0x00, (byte) 0xFF,   // pixel B: R=0xB0
                // Row 1 (top in GL coords)
                (byte) 0xC0, 0x0C, 0x00, (byte) 0xFF,   // pixel C: R=0xC0
                (byte) 0xD0, 0x0D, 0x00, (byte) 0xFF,   // pixel D: R=0xD0
        };

        int[] result = GLByteFlowRender.transformPixels(pixels, 2, 2);

        // Output row 0: D C
        assertEquals(0xD0, red(result[0]));  // D at (0,0)
        assertEquals(0xC0, red(result[1]));  // C at (1,0)
        // Output row 1: B A
        assertEquals(0xB0, red(result[2]));  // B at (0,1)
        assertEquals(0xA0, red(result[3]));  // A at (1,1)
    }
}

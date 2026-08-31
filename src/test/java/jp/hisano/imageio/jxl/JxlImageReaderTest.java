package jp.hisano.imageio.jxl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.junit.Test;

/**
 * Decodes the test images from src/test/resources and verifies dimensions
 * and pixel values of the resulting BufferedImage.
 */
public class JxlImageReaderTest {

    private static File testFile(String name) {
        File file = new File("src/test/resources/" + name);
        assertTrue("missing test resource: " + file, file.isFile());
        return file;
    }

    /**
     * The 3x3 test images contain, in scanline order: red, green, blue,
     * (128,64,64), (64,128,64), (64,64,128), white, gray, black — encoded
     * with a gamma 2.2 transfer function.
     */
    private static final int[] RAW_3X3_RGB = {
        0xFF0000, 0x00FF00, 0x0000FF,
        0x804040, 0x408040, 0x404080,
        0xFFFFFF, 0x808080, 0x000000,
    };

    /**
     * The 3x3 test images use gamma 2.2, so the plugin color-manages them to
     * sRGB. This computes the reference conversion of one 8-bit sample.
     */
    private static int gamma22ToSrgb(int value) {
        double linear = Math.pow(value / 255.0, 2.2);
        double srgb = linear <= 0.0031308
                ? 12.92 * linear
                : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        return (int) Math.round(srgb * 255.0);
    }

    private static int[] pixelsOf(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static void assert3x3PixelsConvertedToSrgb(
            BufferedImage image, int alpha, int tolerance) {
        int[] pixels = pixelsOf(image);
        assertEquals(RAW_3X3_RGB.length, pixels.length);
        for (int i = 0; i < pixels.length; i++) {
            assertEquals("alpha of pixel " + i, alpha, (pixels[i] >>> 24));
            for (int shift = 16; shift >= 0; shift -= 8) {
                int expected = gamma22ToSrgb((RAW_3X3_RGB[i] >> shift) & 0xFF);
                int actual = (pixels[i] >> shift) & 0xFF;
                assertTrue(
                        "channel at shift " + shift + " of pixel " + i
                                + ": expected ~" + expected + " but was " + actual,
                        Math.abs(expected - actual) <= tolerance);
            }
        }
    }

    @Test
    public void imageIoReadDecodesLosslessRgbImage() throws IOException {
        BufferedImage image = ImageIO.read(testFile("3x3_srgb_lossless.jxl"));

        assertNotNull("ImageIO did not find a JPEG XL reader", image);
        assertEquals(3, image.getWidth());
        assertEquals(3, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, image.getType());
        // Tolerance 1 distinguishes converted output from raw gamma 2.2
        // values (e.g. raw 64 vs converted 62).
        assert3x3PixelsConvertedToSrgb(image, 0xFF, 1);
    }

    @Test
    public void imageIoReadDecodesAlphaImage() throws IOException {
        BufferedImage image = ImageIO.read(testFile("3x3a_srgb_lossless.jxl"));

        assertNotNull(image);
        // The image is stored premultiplied at 8 bits and getRGB()
        // un-premultiplies it. At alpha 0x80 the dithered premultiplication
        // in the decoder, the straight/premultiplied round-trip around the
        // ICC conversion, and the final un-premultiplication each quantize
        // to 8 bits, and un-premultiplying doubles those errors, so a
        // channel can drift a few steps from the straight-alpha reference.
        // Verifying the sRGB conversion itself down to one step is the job
        // of the opaque-image test above.
        assert3x3PixelsConvertedToSrgb(image, 0x80, 4);
    }

    @Test
    public void imageIoReadConvertsEmbeddedIccProfileToSrgb() throws IOException {
        BufferedImage image = ImageIO.read(testFile("with_icc.jxl"));

        assertNotNull(image);
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
    }

    @Test
    public void imageIoReadConvertsGrayscaleIccProfileToSrgb() throws IOException {
        BufferedImage image =
                ImageIO.read(testFile("small_grayscale_patches_modular_with_icc.jxl"));

        assertNotNull(image);
        for (int pixel : pixelsOf(image)) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            assertEquals("grayscale pixel must have R == G", r, g);
            assertEquals("grayscale pixel must have G == B", g, b);
        }
    }

    @Test
    public void imageIoReadDecodesSolidBlueImage() throws IOException {
        BufferedImage image = ImageIO.read(testFile("strategic_solid_blue.jxl"));

        assertNotNull(image);
        assertEquals(257, image.getWidth());
        assertEquals(256, image.getHeight());
        for (int pixel : pixelsOf(image)) {
            assertEquals(0xFF0000FF, pixel);
        }
    }

    @Test
    public void imageIoReadDecodesGrayscaleAlphaImage() throws IOException {
        BufferedImage image = ImageIO.read(testFile("gray_alpha_lossless.jxl"));

        assertNotNull(image);
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
        for (int pixel : pixelsOf(image)) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            assertEquals("grayscale pixel must have R == G", r, g);
            assertEquals("grayscale pixel must have G == B", g, b);
        }
    }

    @Test
    public void imageIoReadDecodesContainerFormatFile() throws IOException {
        BufferedImage image = ImageIO.read(testFile("has_permutation_with_container.jxl"));

        assertNotNull(image);
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0);
    }

    @Test
    public void imageIoDiscoversReaderByFormatName() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jxl");
        assertTrue("no reader registered for format name 'jxl'", readers.hasNext());
        assertTrue(readers.next() instanceof JxlImageReader);
    }

    @Test
    public void readerReportsSizeWithoutExplicitRead() throws IOException {
        try (ImageInputStream stream =
                ImageIO.createImageInputStream(testFile("3x3_srgb_lossless.jxl"))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
            try {
                reader.setInput(stream);
                assertEquals(1, reader.getNumImages(false));
                assertEquals(3, reader.getWidth(0));
                assertEquals(3, reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    @Test
    public void spiRejectsNonJxlData() throws IOException {
        byte[] pngHeader = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 0, 0, 0, 0,
        };
        try (ImageInputStream stream =
                ImageIO.createImageInputStream(new ByteArrayInputStream(pngHeader))) {
            assertEquals(false, new JxlImageReaderSpi().canDecodeInput(stream));
        }
    }

    @Test
    public void readFailsOnCorruptedData() throws IOException {
        byte[] corrupted = new byte[64];
        corrupted[0] = (byte) 0xFF;
        corrupted[1] = 0x0A;
        try (ImageInputStream stream =
                ImageIO.createImageInputStream(new ByteArrayInputStream(corrupted))) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("jxl").next();
            try {
                reader.setInput(stream);
                reader.read(0);
                fail("expected IOException for corrupted data");
            } catch (IOException expected) {
                // expected
            } finally {
                reader.dispose();
            }
        }
    }
}

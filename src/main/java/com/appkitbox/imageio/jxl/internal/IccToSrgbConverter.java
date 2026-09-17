package com.appkitbox.imageio.jxl.internal;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.ColorConvertOp;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Converts decoded pixels from the image's own color space (described by an
 * ICC profile) to sRGB using Java 2D's color management
 * ({@link ICC_ColorSpace} + {@link ColorConvertOp}).
 */
final class IccToSrgbConverter {

    private IccToSrgbConverter() {
    }

    /**
     * Converts premultiplied BGRA pixel bytes from the color space described
     * by {@code iccBytes} to sRGB and packs them as premultiplied ARGB
     * integers. {@link ColorConvertOp} expects straight (non-premultiplied)
     * samples, so the color samples are un-premultiplied before the
     * conversion and re-premultiplied afterwards; the alpha channel itself is
     * passed through unchanged.
     *
     * @return the converted premultiplied ARGB pixels, or {@code null} if the
     *     profile is unusable and the caller should fall back to no conversion
     */
    static int[] convert(byte[] bgra, int width, int height, byte[] iccBytes) {
        try {
            ICC_Profile profile = ICC_Profile.getInstance(iccBytes);
            ICC_ColorSpace sourceSpace = new ICC_ColorSpace(profile);
            switch (sourceSpace.getNumComponents()) {
                case 3:
                    return convertBands(bgra, width, height, sourceSpace, 3);
                case 1:
                    return convertBands(bgra, width, height, sourceSpace, 1);
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            // Malformed or unsupported profile: deliver unconverted pixels
            // rather than failing the whole decode.
            return null;
        }
    }

    private static int[] convertBands(
            byte[] bgra, int width, int height, ColorSpace sourceSpace, int bands) {
        int numPixels = width * height;

        // Extract the color samples in RGB band order (or a single gray
        // band; the decoder guarantees B == G == R for grayscale output).
        byte[] source = new byte[numPixels * bands];
        if (bands == 3) {
            for (int i = 0; i < numPixels; i++) {
                int alpha = bgra[i * 4 + 3] & 0xFF;
                source[i * 3] = unpremultiply(bgra[i * 4 + 2], alpha);
                source[i * 3 + 1] = unpremultiply(bgra[i * 4 + 1], alpha);
                source[i * 3 + 2] = unpremultiply(bgra[i * 4], alpha);
            }
        } else {
            for (int i = 0; i < numPixels; i++) {
                source[i] = unpremultiply(bgra[i * 4], bgra[i * 4 + 3] & 0xFF);
            }
        }

        WritableRaster sourceRaster = Raster.createInterleavedRaster(
                new DataBufferByte(source, source.length),
                width,
                height,
                width * bands,
                bands,
                bandOffsets(bands),
                null);
        WritableRaster srgbRaster = Raster.createInterleavedRaster(
                DataBuffer.TYPE_BYTE, width, height, 3, null);

        ColorSpace srgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        new ColorConvertOp(sourceSpace, srgb, null).filter(sourceRaster, srgbRaster);

        byte[] srgbBytes = ((DataBufferByte) srgbRaster.getDataBuffer()).getData();
        int[] argb = new int[numPixels];
        for (int i = 0; i < numPixels; i++) {
            int alpha = bgra[i * 4 + 3] & 0xFF;
            argb[i] = alpha << 24
                    | premultiply(srgbBytes[i * 3], alpha) << 16
                    | premultiply(srgbBytes[i * 3 + 1], alpha) << 8
                    | premultiply(srgbBytes[i * 3 + 2], alpha);
        }
        return argb;
    }

    private static byte unpremultiply(byte value, int alpha) {
        if (alpha == 0xFF) {
            return value;
        }
        if (alpha == 0) {
            return 0;
        }
        return (byte) Math.min(0xFF, ((value & 0xFF) * 0xFF + alpha / 2) / alpha);
    }

    private static int premultiply(byte value, int alpha) {
        if (alpha == 0xFF) {
            return value & 0xFF;
        }
        return ((value & 0xFF) * alpha + 0x7F) / 0xFF;
    }

    private static int[] bandOffsets(int bands) {
        int[] offsets = new int[bands];
        for (int i = 0; i < bands; i++) {
            offsets[i] = i;
        }
        return offsets;
    }
}

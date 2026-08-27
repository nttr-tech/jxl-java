package jp.hisano.imageio.jxl.internal;

import com.dylibso.chicory.runtime.ByteArrayMemory;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.WasmModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Decodes JPEG XL files by calling the jxl-rs decoder compiled to
 * WebAssembly and executed with the Chicory runtime (AOT-compiled classes).
 *
 * <p>Each call creates a fresh WebAssembly instance, so this class is
 * thread-safe and decodes are fully isolated from each other.
 */
public final class WasmJxlDecoder {

    private WasmJxlDecoder() {
    }

    /** Parses the wasm module lazily, once per JVM; parsing is pure CPU work. */
    private static final class ModuleHolder {
        static final WasmModule MODULE = JxlDecoderWasm.load();
    }

    /** The first frame of a decoded JPEG XL image. */
    public static final class Result {
        private final int width;
        private final int height;
        private final int[] argb;

        Result(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        /** Pixels in {@code 0xAARRGGBB} order, row-major, {@code width * height} long. */
        public int[] getArgb() {
            return argb;
        }
    }

    /**
     * Decodes the first frame of the given JPEG XL file.
     *
     * @param data the complete file contents (codestream or container)
     * @return the decoded image as ARGB pixels
     * @throws IOException if the data is not a valid JPEG XL file or decoding fails
     */
    public static Result decode(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("Empty JPEG XL input");
        }
        try {
            Instance instance = Instance.builder(ModuleHolder.MODULE)
                    .withMachineFactory(JxlDecoderWasm::create)
                    // Plain byte-array memory avoids the ByteBuffer
                    // indirection of the default memory implementation.
                    .withMemoryFactory(ByteArrayMemory::new)
                    .withStart(false)
                    .build();
            return decodeWithInstance(instance, data);
        } catch (ChicoryException e) {
            throw new IOException("JPEG XL decoder failed: " + e.getMessage(), e);
        }
    }

    private static Result decodeWithInstance(Instance instance, byte[] data) throws IOException {
        Memory memory = instance.memory();

        int inputPtr = (int) call(instance, "jxl_alloc", data.length)[0];
        try {
            memory.write(inputPtr, data);
            long status = call(instance, "jxl_decode", inputPtr, data.length)[0];
            if (status != 0) {
                throw new IOException("Failed to decode JPEG XL image: " + readError(instance));
            }
        } finally {
            call(instance, "jxl_free", inputPtr, data.length);
        }

        try {
            int width = (int) call(instance, "jxl_get_width")[0];
            int height = (int) call(instance, "jxl_get_height")[0];
            int pixelsPtr = (int) call(instance, "jxl_get_pixels")[0];
            if (width <= 0 || height <= 0 || pixelsPtr == 0) {
                throw new IOException("JPEG XL decoder returned an empty image");
            }
            long byteLength = (long) width * height * 4;
            if (byteLength > Integer.MAX_VALUE) {
                throw new IOException(
                        "JPEG XL image too large: " + width + "x" + height);
            }
            byte[] bgra = memory.readBytes(pixelsPtr, (int) byteLength);

            // If the pixels are not in sRGB, the decoder attaches the ICC
            // profile of the color space they are in; convert them to sRGB.
            int[] argb = null;
            int iccPtr = (int) call(instance, "jxl_get_icc")[0];
            int iccLen = (int) call(instance, "jxl_get_icc_len")[0];
            if (iccPtr != 0 && iccLen > 0) {
                byte[] icc = memory.readBytes(iccPtr, iccLen);
                argb = IccToSrgbConverter.convert(bgra, width, height, icc);
            }
            if (argb == null) {
                argb = bgraToArgb(bgra, width * height);
            }
            return new Result(width, height, argb);
        } finally {
            call(instance, "jxl_free_result");
        }
    }

    private static int[] bgraToArgb(byte[] bgra, int numPixels) {
        // BGRA bytes read as little-endian ints are exactly 0xAARRGGBB.
        int[] argb = new int[numPixels];
        ByteBuffer.wrap(bgra).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb);
        return argb;
    }

    private static String readError(Instance instance) {
        try {
            int ptr = (int) call(instance, "jxl_get_error")[0];
            int len = (int) call(instance, "jxl_get_error_len")[0];
            if (ptr == 0 || len <= 0) {
                return "unknown error";
            }
            byte[] message = instance.memory().readBytes(ptr, len);
            return new String(message, StandardCharsets.UTF_8);
        } catch (ChicoryException e) {
            return "unknown error (" + e.getMessage() + ")";
        }
    }

    private static long[] call(Instance instance, String name, long... args) {
        ExportFunction function = instance.export(name);
        return function.apply(args);
    }
}

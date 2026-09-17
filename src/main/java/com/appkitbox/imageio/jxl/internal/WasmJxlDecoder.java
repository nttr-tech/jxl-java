package com.appkitbox.imageio.jxl.internal;

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

        /**
         * Pixels in {@code 0xAARRGGBB} order, row-major, {@code width * height}
         * long. The color channels are premultiplied by alpha, matching
         * {@code BufferedImage.TYPE_INT_ARGB_PRE}.
         */
        public int[] getArgb() {
            return argb;
        }
    }

    /**
     * Decodes the first frame of the given JPEG XL file.
     *
     * @param data the complete file contents (codestream or container)
     * @return the decoded image as premultiplied ARGB pixels
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

    /**
     * Size in bytes of the {@code jxl_decode} output slot area: eight 4-byte
     * slots (wasm32 pointers and sizes are 4 bytes) holding width, height,
     * BGRA bytes pointer/length, ICC bytes pointer/length and error message
     * pointer/length.
     */
    private static final int OUTPUT_SLOTS_LENGTH = 8 * 4;

    private static Result decodeWithInstance(Instance instance, byte[] data) throws IOException {
        Memory memory = instance.memory();

        int outputSlots = (int) call(instance, "jxl_alloc", OUTPUT_SLOTS_LENGTH)[0];
        try {
            int inputPointer = (int) call(instance, "jxl_alloc", data.length)[0];
            long status;
            try {
                memory.write(inputPointer, data);
                status = call(instance, "jxl_decode",
                        inputPointer, data.length,
                        outputSlots, outputSlots + 4,
                        outputSlots + 8, outputSlots + 12,
                        outputSlots + 16, outputSlots + 20,
                        outputSlots + 24, outputSlots + 28)[0];
            } finally {
                call(instance, "jxl_free", inputPointer, data.length);
            }
            if (status != 0) {
                throw new IOException(
                        "Failed to decode JPEG XL image: " + readErrorMessage(instance, outputSlots));
            }

            int width = memory.readInt(outputSlots);
            int height = memory.readInt(outputSlots + 4);
            int bgraPointer = memory.readInt(outputSlots + 8);
            int bgraLength = memory.readInt(outputSlots + 12);
            int iccPointer = memory.readInt(outputSlots + 16);
            int iccLength = memory.readInt(outputSlots + 20);
            if (width <= 0 || height <= 0 || bgraPointer == 0) {
                throw new IOException("JPEG XL decoder returned an empty image");
            }
            if (bgraLength != (long) width * height * 4) {
                throw new IOException("JPEG XL image too large: " + width + "x" + height);
            }

            byte[] bgra;
            byte[] icc = null;
            try {
                bgra = memory.readBytes(bgraPointer, bgraLength);
                // If the pixels are not in sRGB, the decoder attaches the ICC
                // profile of the color space they are in; convert them to sRGB.
                if (iccPointer != 0 && iccLength > 0) {
                    icc = memory.readBytes(iccPointer, iccLength);
                }
            } finally {
                call(instance, "jxl_free", bgraPointer, bgraLength);
                if (iccPointer != 0) {
                    call(instance, "jxl_free", iccPointer, iccLength);
                }
            }

            int[] argb = null;
            if (icc != null) {
                argb = IccToSrgbConverter.convert(bgra, width, height, icc);
            }
            if (argb == null) {
                argb = bgraToArgb(bgra, width * height);
            }
            return new Result(width, height, argb);
        } finally {
            call(instance, "jxl_free", outputSlots, OUTPUT_SLOTS_LENGTH);
        }
    }

    private static int[] bgraToArgb(byte[] bgra, int numPixels) {
        // Premultiplied BGRA bytes read as little-endian ints are exactly
        // premultiplied 0xAARRGGBB.
        int[] argb = new int[numPixels];
        ByteBuffer.wrap(bgra).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb);
        return argb;
    }

    private static String readErrorMessage(Instance instance, int outputSlots) {
        try {
            Memory memory = instance.memory();
            int messagePointer = memory.readInt(outputSlots + 24);
            int messageLength = memory.readInt(outputSlots + 28);
            if (messagePointer == 0 || messageLength <= 0) {
                return "unknown error";
            }
            try {
                byte[] message = memory.readBytes(messagePointer, messageLength);
                return new String(message, StandardCharsets.UTF_8);
            } finally {
                call(instance, "jxl_free", messagePointer, messageLength);
            }
        } catch (ChicoryException e) {
            return "unknown error (" + e.getMessage() + ")";
        }
    }

    private static long[] call(Instance instance, String name, long... args) {
        ExportFunction function = instance.export(name);
        return function.apply(args);
    }
}

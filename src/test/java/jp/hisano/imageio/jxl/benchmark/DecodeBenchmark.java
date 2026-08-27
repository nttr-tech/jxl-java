package jp.hisano.imageio.jxl.benchmark;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.WasmModule;

import jp.hisano.imageio.jxl.internal.JxlDecoderWasm;
import jp.hisano.imageio.jxl.internal.WasmJxlDecoder;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * Ad-hoc benchmark that breaks a JPEG XL decode into stages (module parse,
 * instance build, wasm decode, pixel transfer) and reports cold and warm
 * timings. Run with {@code ./gradlew runBenchmark [--args="file.jxl ..."]}.
 */
public final class DecodeBenchmark {

    private static final int WARM_ITERATIONS = 10;

    private DecodeBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        String[] files = args.length > 0
                ? args
                : new String[] {
                    "src/test/resources/tirr_photo.jxl",
                    "src/test/resources/green_queen_modular_e3.jxl",
                    "src/test/resources/zoltan_tasi_unsplash.jxl",
                };

        for (String file : files) {
            Path path = Paths.get(file);
            if (!Files.isReadable(path)) {
                System.out.println("skipping unreadable file: " + path);
                continue;
            }
            benchmarkFile(path);
        }
    }

    private static void benchmarkFile(Path path) throws Exception {
        byte[] data = Files.readAllBytes(path);
        System.out.println();
        System.out.println("=== " + path.getFileName() + " (" + data.length + " bytes) ===");

        // Cold: the very first end-to-end decode in this JVM (per file, the
        // first file also pays class loading + JIT warmup).
        long start = System.nanoTime();
        WasmJxlDecoder.Result cold = WasmJxlDecoder.decode(data);
        print("cold ImageIO-equivalent decode", System.nanoTime() - start);
        System.out.println("    image: " + cold.getWidth() + "x" + cold.getHeight());

        long parseTotal = 0;
        long buildTotal = 0;
        long decodeTotal = 0;
        long pixelsTotal = 0;
        long endToEndTotal = 0;

        for (int i = 0; i < WARM_ITERATIONS; i++) {
            long t0 = System.nanoTime();
            WasmModule module = JxlDecoderWasm.load();
            long t1 = System.nanoTime();
            Instance instance = Instance.builder(module)
                    .withMachineFactory(JxlDecoderWasm::create)
                    .withMemoryFactory(com.dylibso.chicory.runtime.ByteArrayMemory::new)
                    .withStart(false)
                    .build();
            long t2 = System.nanoTime();

            Memory memory = instance.memory();
            int inputPtr = (int) call(instance, "jxl_alloc", data.length)[0];
            memory.write(inputPtr, data);
            long status = call(instance, "jxl_decode", inputPtr, data.length)[0];
            call(instance, "jxl_free", inputPtr, data.length);
            if (status != 0) {
                throw new IllegalStateException("decode failed for " + path);
            }
            long t3 = System.nanoTime();

            int width = (int) call(instance, "jxl_get_width")[0];
            int height = (int) call(instance, "jxl_get_height")[0];
            int pixelsPtr = (int) call(instance, "jxl_get_pixels")[0];
            byte[] bgra = memory.readBytes(pixelsPtr, width * height * 4);
            int iccLen = (int) call(instance, "jxl_get_icc_len")[0];
            call(instance, "jxl_free_result");
            long t4 = System.nanoTime();

            parseTotal += t1 - t0;
            buildTotal += t2 - t1;
            decodeTotal += t3 - t2;
            pixelsTotal += t4 - t3;
            if (i == 0) {
                System.out.println("    icc profile: "
                        + (iccLen > 0 ? iccLen + " bytes (conversion needed)" : "none (sRGB)"));
            }
            if (bgra.length == 0) {
                throw new IllegalStateException("empty pixels");
            }

            long t5 = System.nanoTime();
            WasmJxlDecoder.decode(data);
            endToEndTotal += System.nanoTime() - t5;
        }

        System.out.println("  warm averages over " + WARM_ITERATIONS + " iterations:");
        print("module parse (JxlDecoderWasm.load)", parseTotal / WARM_ITERATIONS);
        print("instance build", buildTotal / WARM_ITERATIONS);
        print("wasm jxl_decode", decodeTotal / WARM_ITERATIONS);
        print("pixel read-back", pixelsTotal / WARM_ITERATIONS);
        print("end-to-end WasmJxlDecoder.decode", endToEndTotal / WARM_ITERATIONS);

        long t6 = System.nanoTime();
        ImageIO.read(new ByteArrayInputStream(data));
        print("warm ImageIO.read (incl. ICC->sRGB)", System.nanoTime() - t6);
    }

    private static long[] call(Instance instance, String name, long... callArgs) {
        return instance.export(name).apply(callArgs);
    }

    private static void print(String label, long nanos) {
        System.out.println(String.format(Locale.ROOT, "  %-40s %10.2f ms", label, nanos / 1e6));
    }
}

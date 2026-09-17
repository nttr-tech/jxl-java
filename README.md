# jxl-java

Java ImageIO plugin for reading JPEG XL images, powered by the
[jxl-rs](https://github.com/libjxl/jxl-rs) decoder compiled to WebAssembly
and executed on the JVM with the [Chicory](https://chicory.dev/) runtime
(ahead-of-time compiled to class files, no native libraries and no JNI).

## Usage

Once the jar is on the classpath, JPEG XL support is picked up automatically
through the ImageIO service provider mechanism:

```java
BufferedImage image = ImageIO.read(new File("image.jxl"));
```

The reader decodes the first frame of the image (including the first frame
of animations) as a `TYPE_INT_ARGB_PRE` (premultiplied alpha)
`BufferedImage`, the translucent image type that Java 2D composites and
displays the fastest. Both the bare codestream (`FF 0A`) and the ISOBMFF
container format are supported.

A sample Swing viewer is included in the test sources
(`jp.hisano.imageio.jxl.sample.JxlImageViewer`); launch it with:

```
./gradlew runViewer
```

## Requirements

- **Java 11 or newer at runtime.** The Chicory runtime is compiled for
  Java 11, so Java 8 cannot load it; this module therefore targets
  `--release 11` as the lowest possible bytecode level.
- Building requires a Rust toolchain with the `wasm32-unknown-unknown`
  target (`rustup target add wasm32-unknown-unknown`) and JDK 21+ for
  Gradle.

## Building

```
./gradlew build
```

This produces two jars under `build/libs/`:

- `jxl-java-<version>.jar` — the plain library; requires
  `com.dylibso.chicory:runtime` on the classpath.
- `jxl-java-<version>-all.jar` — self-contained fat jar with the
  Chicory runtime shaded under `jp.hisano.imageio.jxl.internal.chicory`, so
  it never conflicts with another Chicory version in the application.

The build pipeline is:

1. `cargoBuildWasm` — compiles the [`rust/`](rust) crate (a thin C-ABI
   wrapper around the `jxl` crate) to `jxl_wasm.wasm`.
2. `precompileWasm2Class` — the
   [wasm2class](https://github.com/illarionov/wasm2class-gradle-plugin)
   plugin translates the WebAssembly module into Chicory AOT class files.
   The Rust profile uses thin LTO instead of fat LTO so that no single
   wasm function exceeds the JVM method size limit; every function is
   AOT-compiled (`interpreterFallback = FAIL` guards against regressions).
3. Regular Java compilation of the ImageIO plugin and its tests.

## Architecture

```
byte[] (JXL file) --> wasm linear memory --> jxl-rs decoder (wasm)
    --> premultiplied BGRA bytes --> int[] ARGB --> BufferedImage (TYPE_INT_ARGB_PRE)
```

- [`rust/src/lib.rs`](rust/src/lib.rs) exports `jxl_alloc`, `jxl_free` and
  `jxl_decode`. Every image is decoded directly to interleaved BGRA (the
  decoder replicates grayscale to three channels and fills in opaque alpha
  where needed), so no conversion pass over the pixels is needed inside the
  wasm.
- `WasmJxlDecoder` drives those exports through Chicory; the parsed wasm
  module is cached per JVM, and each decode uses a fresh wasm instance
  (sub-millisecond to create), so decoding is thread-safe and isolated.
- `JxlImageReader` / `JxlImageReaderSpi` implement the ImageIO contract.

## Performance

Decoding runs the full jxl-rs pixel pipeline as scalar (non-SIMD) code on
the JVM, so it is CPU-bound inside the wasm: expect roughly 1–2.5 seconds
per megapixel on typical desktop hardware once the JVM is warmed up, and
about 1.5× that for the first image in a JVM (JIT warmup). Everything
else — module loading, instance creation, pixel transfer and ARGB
conversion — is a few milliseconds combined. The Chicory AOT compiler does
not yet support wasm SIMD (`v128`; Chicory 1.5 supports SIMD only in its
Java 21+ interpreter), so the jxl-rs `simd128` code path cannot be used.
A staged benchmark is available via:

```
./gradlew runBenchmark [--args="path/to/image.jxl ..."]
```

## Color management

When the decoder's output is not already sRGB (e.g. an embedded ICC
profile, gamma or wide-gamut encodings), the wasm module attaches the ICC
profile of the output color space to the `jxl_decode` result, and the Java
side
converts the pixels to sRGB with `java.awt.color.ICC_ColorSpace` +
`ColorConvertOp`. XYB-encoded (lossy) images whose embedded ICC profile
cannot be used as a decoder output space are decoded straight to sRGB by
jxl-rs itself. If a profile cannot be parsed, the pixels are delivered
unconverted rather than failing the decode.

## Limitations

- Only the first frame of an animation is exposed.
- Pixels are always returned as 8-bit premultiplied ARGB; HDR/16-bit data
  is truncated to 8 bits per sample, and colors of nearly transparent
  pixels lose precision to the premultiplication.

## License

This project is licensed under the [Apache License, Version 2.0](LICENSE).

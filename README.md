# imageio-jxl

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
of animations) as a `TYPE_INT_ARGB` `BufferedImage`. Both the bare
codestream (`FF 0A`) and the ISOBMFF container format are supported.

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

- `imageio-jxl-<version>.jar` — the plain library; requires
  `com.dylibso.chicory:runtime` on the classpath.
- `imageio-jxl-<version>-all.jar` — self-contained fat jar with the
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
    --> BGRA bytes --> int[] ARGB --> BufferedImage (TYPE_INT_ARGB)
```

- [`rust/src/lib.rs`](rust/src/lib.rs) exports `jxl_alloc`, `jxl_free`,
  `jxl_decode`, `jxl_get_width`, `jxl_get_height`, `jxl_get_pixels`,
  `jxl_get_icc`, `jxl_get_icc_len`, `jxl_get_error` and `jxl_free_result`.
- `WasmJxlDecoder` drives those exports through Chicory; each decode uses a
  fresh wasm instance, so decoding is thread-safe and isolated.
- `JxlImageReader` / `JxlImageReaderSpi` implement the ImageIO contract.

## Color management

When the decoder's output is not already sRGB (e.g. an embedded ICC
profile, gamma or wide-gamut encodings), the wasm module attaches the ICC
profile of the output color space (`jxl_get_icc`), and the Java side
converts the pixels to sRGB with `java.awt.color.ICC_ColorSpace` +
`ColorConvertOp`. XYB-encoded (lossy) images whose embedded ICC profile
cannot be used as a decoder output space are decoded straight to sRGB by
jxl-rs itself. If a profile cannot be parsed, the pixels are delivered
unconverted rather than failing the decode.

## Limitations

- Only the first frame of an animation is exposed.
- Pixels are always returned as 8-bit ARGB; HDR/16-bit data is truncated
  to 8 bits per sample.

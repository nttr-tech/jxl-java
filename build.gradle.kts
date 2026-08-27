plugins {
    `java-library`
    id("at.released.wasm2class.plugin") version "0.5.0"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "jp.hisano.imageio"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    // Chicory requires Java 11+, so 11 is the lowest release we can target.
    options.release = 11
    options.encoding = "UTF-8"
}

val wasmFile =
    layout.projectDirectory.file("rust/target/wasm32-unknown-unknown/release/jxl_wasm.wasm")

// Builds the Rust decoder to WebAssembly. Requires a Rust toolchain with the
// wasm32-unknown-unknown target installed (rustup target add wasm32-unknown-unknown).
val cargoBuildWasm by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles the jxl_wasm Rust crate to WebAssembly with cargo."
    workingDir = layout.projectDirectory.dir("rust").asFile
    commandLine("cargo", "build", "--release", "--target", "wasm32-unknown-unknown")
    inputs.files(
        layout.projectDirectory.file("rust/Cargo.toml"),
        layout.projectDirectory.file("rust/Cargo.lock"),
    )
    inputs.dir(layout.projectDirectory.dir("rust/src"))
    outputs.file(wasmFile)
}

wasm2class {
    targetPackage = "jp.hisano.imageio.jxl.internal"
    modules {
        // Generates jp.hisano.imageio.jxl.internal.JxlDecoderWasmModule.
        create("jxlDecoderWasm") {
            wasm.fileProvider(cargoBuildWasm.map { it.outputs.files.singleFile })
            // All functions must compile to bytecode; the Rust profile uses
            // thin LTO so no wasm function exceeds the JVM method size limit.
            interpreterFallback = at.released.wasm2class.InterpreterFallback.FAIL
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Launches the sample JPEG XL viewer from the test sources.
tasks.register<JavaExec>("runViewer") {
    group = "application"
    description = "Runs the JxlImageViewer sample (file dialog + Swing window)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "jp.hisano.imageio.jxl.sample.JxlImageViewer"
}

// Self-contained jar (imageio-jxl-<version>-all.jar) with the Chicory
// runtime shaded under jp.hisano.imageio.jxl.internal so it cannot clash with
// another Chicory version on the application classpath.
tasks.shadowJar {
    relocate("com.dylibso.chicory", "jp.hisano.imageio.jxl.internal.chicory")
    exclude("META-INF/maven/**")
}

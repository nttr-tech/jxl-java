//! WebAssembly (wasm32-unknown-unknown) bindings used by the Java ImageIO
//! plugin in this repository.
//!
//! The exported API is stateless: a single [`jxl_decode`] call decodes a
//! complete JPEG XL file passed as a byte array and returns the first frame
//! through output pointer arguments as interleaved BGRA bytes (8 bits per
//! sample) with the color channels premultiplied by alpha. When those bytes
//! are read as little-endian 32-bit integers they match Java's premultiplied
//! ARGB pixel layout (`TYPE_INT_ARGB_PRE`, `0xAARRGGBB`). Every buffer returned
//! through an output argument is allocated inside the wasm linear memory and
//! must be released by the caller with [`jxl_free`].

use jxl::api::states::Initialized;
use jxl::api::{
    JxlColorEncoding, JxlColorProfile, JxlColorType, JxlDataFormat, JxlDecoder, JxlDecoderOptions,
    JxlOutputBuffer, JxlPixelFormat, ProcessingResult,
};

struct DecodedImage {
    width: u32,
    height: u32,
    /// Interleaved BGRA bytes, `width * height * 4` long. The color
    /// channels are premultiplied by alpha.
    bgra: Vec<u8>,
    /// ICC profile describing the color space of `bgra`, or empty when the
    /// pixels are already (gray) sRGB and need no conversion.
    icc: Vec<u8>,
}

/// Allocates `len` bytes inside the wasm linear memory and returns a pointer
/// to them. The caller must release the block with [`jxl_free`].
#[no_mangle]
pub extern "C" fn jxl_alloc(len: usize) -> *mut u8 {
    let mut buf = Vec::<u8>::with_capacity(len.max(1));
    let ptr = buf.as_mut_ptr();
    std::mem::forget(buf);
    ptr
}

/// Frees a block previously returned by [`jxl_alloc`] or through an output
/// argument of [`jxl_decode`] with the same `len`.
///
/// # Safety
/// `ptr` must come from `jxl_alloc(len)` or from `jxl_decode` paired with
/// the length `len`, and must not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn jxl_free(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        // SAFETY: guaranteed by the caller contract above.
        unsafe { drop(Vec::from_raw_parts(ptr, 0, len.max(1))) };
    }
}

/// Decodes the JPEG XL file stored at `input_bytes..input_bytes+input_bytes_length`
/// and returns the first frame through the output arguments.
///
/// Returns 0 on success: `output_image_width` / `output_image_height` hold
/// the image size, `output_image_bgra_bytes` points to the interleaved BGRA
/// pixel bytes (`width * height * 4` bytes, color channels premultiplied by
/// alpha, premultiplied ARGB when read as little-endian 32-bit integers),
/// and `output_image_icc_bytes` points to the ICC profile
/// describing their color space (null and 0 when the pixels are already
/// sRGB and need no conversion).
///
/// Returns -1 on failure: `output_error_message` points to the UTF-8 error
/// message (not NUL-terminated) and every image output is null or 0.
///
/// Every returned buffer is allocated by this function and must be released
/// by the caller with [`jxl_free`] using the pointer and length pair it was
/// returned with.
///
/// # Safety
/// `input_bytes` must be valid for reads of `input_bytes_length` bytes and
/// every output pointer must be valid for a write of its pointee type.
#[no_mangle]
pub unsafe extern "C" fn jxl_decode(
    input_bytes: *const u8,
    input_bytes_length: usize,
    output_image_width: *mut u32,
    output_image_height: *mut u32,
    output_image_bgra_bytes: *mut *mut u8,
    output_image_bgra_bytes_length: *mut usize,
    output_image_icc_bytes: *mut *mut u8,
    output_image_icc_bytes_length: *mut usize,
    output_error_message: *mut *mut u8,
    output_error_message_length: *mut usize,
) -> i32 {
    // SAFETY: guaranteed by the caller contract above.
    unsafe {
        *output_image_width = 0;
        *output_image_height = 0;
        *output_image_bgra_bytes = std::ptr::null_mut();
        *output_image_bgra_bytes_length = 0;
        *output_image_icc_bytes = std::ptr::null_mut();
        *output_image_icc_bytes_length = 0;
        *output_error_message = std::ptr::null_mut();
        *output_error_message_length = 0;

        let data = std::slice::from_raw_parts(input_bytes, input_bytes_length);
        match decode_impl(data) {
            Ok(image) => {
                *output_image_width = image.width;
                *output_image_height = image.height;
                let (bgra_bytes, bgra_bytes_length) = leak_bytes(image.bgra);
                *output_image_bgra_bytes = bgra_bytes;
                *output_image_bgra_bytes_length = bgra_bytes_length;
                if !image.icc.is_empty() {
                    let (icc_bytes, icc_bytes_length) = leak_bytes(image.icc);
                    *output_image_icc_bytes = icc_bytes;
                    *output_image_icc_bytes_length = icc_bytes_length;
                }
                0
            }
            Err(message) => {
                let (message_bytes, message_bytes_length) = leak_bytes(message.into_bytes());
                *output_error_message = message_bytes;
                *output_error_message_length = message_bytes_length;
                -1
            }
        }
    }
}

/// Hands the bytes over to the caller: returns their pointer and length and
/// gives up ownership, so that [`jxl_free`] can release them later.
fn leak_bytes(bytes: Vec<u8>) -> (*mut u8, usize) {
    let mut bytes = bytes.into_boxed_slice();
    let pointer = bytes.as_mut_ptr();
    let length = bytes.len();
    std::mem::forget(bytes);
    (pointer, length)
}

fn decode_impl(data: &[u8]) -> Result<DecodedImage, String> {
    let mut input: &[u8] = data;

    // Request premultiplied alpha output: it is what Java's fastest
    // translucent image type (TYPE_INT_ARGB_PRE) stores, and jxl-rs
    // premultiplies in float precision inside the render pipeline (skipping
    // files whose alpha is already associated), so no 8-bit conversion pass
    // is needed later.
    let mut options = JxlDecoderOptions::default();
    options.premultiply_output = true;
    let decoder = JxlDecoder::<Initialized>::new(options);
    let mut decoder = match decoder
        .process(&mut input, None)
        .map_err(|e| format!("failed to parse image header: {e}"))?
    {
        ProcessingResult::Complete { result } => result,
        ProcessingResult::NeedsMoreInput { .. } => return Err("truncated JPEG XL file".into()),
    };

    let info = decoder.basic_info().clone();
    let (width, height) = info.size;
    if width == 0 || height == 0 {
        return Err("image has zero width or height".into());
    }
    u32::try_from(width)
        .ok()
        .zip(u32::try_from(height).ok())
        .and_then(|_| width.checked_mul(height)?.checked_mul(4))
        .ok_or("image dimensions overflow")?;

    // Request 8-bit interleaved BGRA output directly: the decoder writes the
    // final layout itself, so no conversion pass (or second buffer) is needed
    // below. Grayscale images are replicated to B == G == R by the decoder,
    // the alpha channel (if any) is interleaved as the fourth sample, and
    // images without one get opaque alpha filled in.
    let pixel_format = JxlPixelFormat {
        color_type: JxlColorType::Bgra,
        color_data_format: Some(JxlDataFormat::U8 { bit_depth: 8 }),
        // Ignore all planar extra channels; alpha is interleaved instead.
        extra_channel_format: vec![None; info.extra_channels.len()],
    };
    decoder
        .set_pixel_format(pixel_format)
        .map_err(|e| format!("failed to set pixel format: {e}"))?;

    let icc = output_profile_icc(decoder.output_color_profile());

    let bytes_per_row = width * 4;
    let mut bgra = vec![0u8; bytes_per_row * height];

    // Decode only the first (or only) frame of the image.
    let decoder_with_frame = match decoder
        .process(&mut input, None)
        .map_err(|e| format!("failed to parse frame header: {e}"))?
    {
        ProcessingResult::Complete { result } => result,
        ProcessingResult::NeedsMoreInput { .. } => return Err("truncated JPEG XL file".into()),
    };

    let mut buffers = [JxlOutputBuffer::new(&mut bgra, height, bytes_per_row)];
    match decoder_with_frame
        .process(&mut input, &mut buffers, None)
        .map_err(|e| format!("failed to decode frame: {e}"))?
    {
        ProcessingResult::Complete { .. } => {}
        ProcessingResult::NeedsMoreInput { .. } => {
            return Err("truncated JPEG XL file".into());
        }
    }

    Ok(DecodedImage {
        width: width as u32,
        height: height as u32,
        bgra,
        icc,
    })
}

/// Returns the ICC profile bytes for the profile the output pixels are in,
/// or an empty vector when the pixels are already (gray) sRGB or when no
/// profile can be produced (in which case no conversion is attempted).
fn output_profile_icc(profile: &JxlColorProfile) -> Vec<u8> {
    let already_srgb = profile
        .same_color_encoding(&JxlColorProfile::Simple(JxlColorEncoding::srgb(false)))
        || profile.same_color_encoding(&JxlColorProfile::Simple(JxlColorEncoding::srgb(true)));
    if already_srgb {
        return Vec::new();
    }
    profile
        .try_as_icc()
        .map_or_else(Vec::new, |icc| icc.into_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read_test_file(name: &str) -> Vec<u8> {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../src/test/resources/");
        std::fs::read(format!("{path}{name}")).unwrap()
    }

    /// The 3x3 test images contain, in scanline order: red, green, blue,
    /// (128,64,64), (64,128,64), (64,64,128), white, gray, black.
    fn expected_3x3_bgra(alpha: u8) -> Vec<u8> {
        [
            [0, 0, 255],
            [0, 255, 0],
            [255, 0, 0],
            [64, 64, 128],
            [64, 128, 64],
            [128, 64, 64],
            [255, 255, 255],
            [128, 128, 128],
            [0, 0, 0],
        ]
        .iter()
        .flat_map(|bgr| [bgr[0], bgr[1], bgr[2], alpha])
        .collect()
    }

    #[test]
    fn decodes_rgb_lossless_image_to_expected_pixels() {
        let image = decode_impl(&read_test_file("3x3_srgb_lossless.jxl")).unwrap();
        assert_eq!((image.width, image.height), (3, 3));
        assert_eq!(image.bgra, expected_3x3_bgra(255));
    }

    /// Reference premultiplication matching the decoder: multiply in float
    /// and round to the nearest 8-bit value.
    fn premultiply(value: u8, alpha: u8) -> u8 {
        (f32::from(value) * f32::from(alpha) / 255.0 + 0.5) as u8
    }

    #[test]
    fn decodes_alpha_image_with_premultiplied_interleaved_alpha() {
        let image = decode_impl(&read_test_file("3x3a_srgb_lossless.jxl")).unwrap();
        assert_eq!((image.width, image.height), (3, 3));
        let alpha = 128;
        let expected_bytes: Vec<u8> = expected_3x3_bgra(alpha)
            .iter()
            .enumerate()
            .map(|(index, &value)| {
                if index % 4 == 3 {
                    value
                } else {
                    premultiply(value, alpha)
                }
            })
            .collect();
        assert_eq!(image.bgra.len(), expected_bytes.len());
        for (index, (&actual_value, &expected_value)) in
            image.bgra.iter().zip(expected_bytes.iter()).enumerate()
        {
            // The decoder dithers when converting float samples to 8 bits,
            // so premultiplied color values with a fractional part may land
            // one step away from the rounded reference. Alpha values are
            // integers and stay exact.
            let tolerance = if index % 4 == 3 { 0 } else { 1 };
            assert!(
                (i16::from(actual_value) - i16::from(expected_value)).abs() <= tolerance,
                "byte {index}: expected about {expected_value} but was {actual_value}"
            );
        }
    }

    #[test]
    fn decodes_solid_blue_image() {
        let image = decode_impl(&read_test_file("strategic_solid_blue.jxl")).unwrap();
        assert_eq!((image.width, image.height), (257, 256));
        for pixel in image.bgra.chunks_exact(4) {
            assert_eq!(pixel, [255, 0, 0, 255]);
        }
    }

    #[test]
    fn decodes_grayscale_alpha_image() {
        let image = decode_impl(&read_test_file("gray_alpha_lossless.jxl")).unwrap();
        assert!(image.width > 0 && image.height > 0);
        assert_eq!(
            image.bgra.len(),
            image.width as usize * image.height as usize * 4
        );
        // Grayscale output must be gray: the decoder replicates the gray
        // sample to B, G and R, but dithers each channel with a different
        // pattern offset when converting to 8 bits, so the channels of one
        // pixel may differ by a single step.
        for pixel in image.bgra.chunks_exact(4) {
            let minimum_value = *pixel[..3].iter().min().unwrap();
            let maximum_value = *pixel[..3].iter().max().unwrap();
            assert!(
                maximum_value - minimum_value <= 1,
                "pixel is not gray: {:?}",
                &pixel[..3]
            );
        }
    }

    #[test]
    fn decodes_only_first_frame_of_animation() {
        let image = decode_impl(&read_test_file("named_frame_test.jxl")).unwrap();
        assert!(image.width > 0 && image.height > 0);
        assert_eq!(
            image.bgra.len(),
            image.width as usize * image.height as usize * 4
        );
    }

    #[test]
    fn srgb_image_has_no_icc_profile() {
        // strategic_solid_blue.jxl uses the true sRGB transfer function.
        let image = decode_impl(&read_test_file("strategic_solid_blue.jxl")).unwrap();
        assert!(image.icc.is_empty());
    }

    #[test]
    fn gamma_image_exposes_icc_bytes() {
        // The 3x3 test images use a gamma 2.2 transfer function, which is
        // not sRGB, so an ICC profile must be attached for conversion.
        let image = decode_impl(&read_test_file("3x3_srgb_lossless.jxl")).unwrap();
        assert!(!image.icc.is_empty());
        assert_eq!(&image.icc[36..40], b"acsp");
    }

    #[test]
    fn lossless_image_with_icc_profile_exposes_icc_bytes() {
        let image = decode_impl(&read_test_file("with_icc.jxl")).unwrap();
        assert!(!image.icc.is_empty());
        // Every ICC profile has the 'acsp' signature at offset 36.
        assert_eq!(&image.icc[36..40], b"acsp");
    }

    #[test]
    fn lossy_image_with_icc_profile_outputs_srgb() {
        // XYB-encoded images cannot be output to an ICC profile without a
        // CMS, so the decoder falls back to sRGB output: no conversion needed.
        let image = decode_impl(&read_test_file("lossy_with_icc.jxl")).unwrap();
        assert!(image.icc.is_empty());
    }

    #[test]
    fn grayscale_image_with_icc_profile_exposes_icc_bytes() {
        let image = decode_impl(&read_test_file(
            "small_grayscale_patches_modular_with_icc.jxl",
        ))
        .unwrap();
        assert!(!image.icc.is_empty());
        assert_eq!(&image.icc[36..40], b"acsp");
    }

    #[test]
    fn rejects_invalid_data() {
        assert!(decode_impl(&[0u8; 16]).is_err());
        assert!(decode_impl(&[]).is_err());
    }

    #[test]
    fn rejects_truncated_file() {
        let data = read_test_file("3x3_srgb_lossless.jxl");
        assert!(decode_impl(&data[..data.len() / 2]).is_err());
    }
}

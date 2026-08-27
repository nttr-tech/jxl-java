//! WebAssembly (wasm32-unknown-unknown) bindings used by the Java ImageIO
//! plugin in this repository.
//!
//! The exported API decodes a complete JPEG XL file passed as a byte array
//! and produces the first frame as interleaved BGRA bytes (8 bits per
//! sample). When those bytes are read as little-endian 32-bit integers they
//! match Java's ARGB pixel layout (`0xAARRGGBB`).

use std::cell::RefCell;

use jxl::api::states::Initialized;
use jxl::api::{
    JxlColorEncoding, JxlColorProfile, JxlColorType, JxlDataFormat, JxlDecoder, JxlDecoderOptions,
    JxlOutputBuffer, JxlPixelFormat, ProcessingResult,
};
use jxl::headers::extra_channels::ExtraChannel;

struct DecodedImage {
    width: u32,
    height: u32,
    /// Interleaved BGRA bytes, `width * height * 4` long.
    bgra: Vec<u8>,
    /// ICC profile describing the color space of `bgra`, or empty when the
    /// pixels are already (gray) sRGB and need no conversion.
    icc: Vec<u8>,
}

thread_local! {
    static RESULT: RefCell<Option<DecodedImage>> = const { RefCell::new(None) };
    static LAST_ERROR: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
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

/// Frees a block previously returned by [`jxl_alloc`] with the same `len`.
///
/// # Safety
/// `ptr` must come from `jxl_alloc(len)` and must not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn jxl_free(ptr: *mut u8, len: usize) {
    if !ptr.is_null() {
        // SAFETY: guaranteed by the caller contract above.
        unsafe { drop(Vec::from_raw_parts(ptr, 0, len.max(1))) };
    }
}

/// Decodes the JPEG XL file stored at `ptr..ptr+len`.
///
/// Returns 0 on success, in which case the result is available through
/// `jxl_get_width` / `jxl_get_height` / `jxl_get_pixels`. Returns -1 on
/// failure, in which case `jxl_get_error` / `jxl_get_error_len` describe the
/// error. Any previously stored result is dropped.
///
/// # Safety
/// `ptr` must be valid for reads of `len` bytes.
#[no_mangle]
pub unsafe extern "C" fn jxl_decode(ptr: *const u8, len: usize) -> i32 {
    // SAFETY: guaranteed by the caller contract above.
    let data = unsafe { std::slice::from_raw_parts(ptr, len) };
    RESULT.with(|r| *r.borrow_mut() = None);
    match decode_impl(data) {
        Ok(image) => {
            RESULT.with(|r| *r.borrow_mut() = Some(image));
            LAST_ERROR.with(|e| e.borrow_mut().clear());
            0
        }
        Err(message) => {
            LAST_ERROR.with(|e| *e.borrow_mut() = message.into_bytes());
            -1
        }
    }
}

/// Width in pixels of the last successfully decoded image.
#[no_mangle]
pub extern "C" fn jxl_get_width() -> u32 {
    RESULT.with(|r| r.borrow().as_ref().map_or(0, |i| i.width))
}

/// Height in pixels of the last successfully decoded image.
#[no_mangle]
pub extern "C" fn jxl_get_height() -> u32 {
    RESULT.with(|r| r.borrow().as_ref().map_or(0, |i| i.height))
}

/// Pointer to the BGRA pixel bytes (`width * height * 4` bytes) of the last
/// successfully decoded image. Valid until `jxl_free_result` or the next
/// `jxl_decode` call.
#[no_mangle]
pub extern "C" fn jxl_get_pixels() -> *const u8 {
    RESULT.with(|r| {
        r.borrow()
            .as_ref()
            .map_or(std::ptr::null(), |i| i.bgra.as_ptr())
    })
}

/// Pointer to the ICC profile bytes describing the color space of the pixel
/// data, or null when the pixels are already sRGB (no conversion needed).
/// Valid until `jxl_free_result` or the next `jxl_decode` call.
#[no_mangle]
pub extern "C" fn jxl_get_icc() -> *const u8 {
    RESULT.with(|r| {
        r.borrow().as_ref().map_or(std::ptr::null(), |i| {
            if i.icc.is_empty() {
                std::ptr::null()
            } else {
                i.icc.as_ptr()
            }
        })
    })
}

/// Length in bytes of the ICC profile, 0 when the pixels are already sRGB.
#[no_mangle]
pub extern "C" fn jxl_get_icc_len() -> usize {
    RESULT.with(|r| r.borrow().as_ref().map_or(0, |i| i.icc.len()))
}

/// Releases the memory held by the last decode result.
#[no_mangle]
pub extern "C" fn jxl_free_result() {
    RESULT.with(|r| *r.borrow_mut() = None);
}

/// Pointer to the UTF-8 bytes of the last error message (not NUL-terminated).
#[no_mangle]
pub extern "C" fn jxl_get_error() -> *const u8 {
    LAST_ERROR.with(|e| e.borrow().as_ptr())
}

/// Length in bytes of the last error message.
#[no_mangle]
pub extern "C" fn jxl_get_error_len() -> usize {
    LAST_ERROR.with(|e| e.borrow().len())
}

fn decode_impl(data: &[u8]) -> Result<DecodedImage, String> {
    let mut input: &[u8] = data;

    let decoder = JxlDecoder::<Initialized>::new(JxlDecoderOptions::default());
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

    // Request 8-bit output, interleaving the alpha channel (if any) into the
    // color buffer. Color images are requested directly in BGR(A) order so
    // that the decoder writes the final layout and no extra full-image
    // conversion pass (or second buffer) is needed for them below.
    let current_format = decoder.current_pixel_format().clone();
    let alpha_channel = info
        .extra_channels
        .iter()
        .position(|c| c.ec_type == ExtraChannel::Alpha);
    let base_color_type = match current_format.color_type {
        JxlColorType::Rgb | JxlColorType::Rgba => JxlColorType::Bgr,
        other => other,
    };
    let color_type = if alpha_channel.is_some() {
        base_color_type.add_alpha().unwrap_or(base_color_type)
    } else {
        base_color_type
    };
    let pixel_format = JxlPixelFormat {
        color_type,
        color_data_format: Some(JxlDataFormat::U8 { bit_depth: 8 }),
        // Ignore all planar extra channels; alpha is interleaved instead.
        extra_channel_format: vec![None; current_format.extra_channel_format.len()],
    };
    decoder
        .set_pixel_format(pixel_format)
        .map_err(|e| format!("failed to set pixel format: {e}"))?;

    let icc = output_profile_icc(decoder.output_color_profile());

    let samples_per_pixel = color_type.samples_per_pixel();
    let bytes_per_row = width * samples_per_pixel;
    let mut interleaved = vec![0u8; bytes_per_row * height];

    // Decode only the first (or only) frame of the image.
    let decoder_with_frame = match decoder
        .process(&mut input, None)
        .map_err(|e| format!("failed to parse frame header: {e}"))?
    {
        ProcessingResult::Complete { result } => result,
        ProcessingResult::NeedsMoreInput { .. } => return Err("truncated JPEG XL file".into()),
    };

    let mut buffers = [JxlOutputBuffer::new(
        &mut interleaved,
        height,
        bytes_per_row,
    )];
    match decoder_with_frame
        .process(&mut input, &mut buffers, None)
        .map_err(|e| format!("failed to decode frame: {e}"))?
    {
        ProcessingResult::Complete { .. } => {}
        ProcessingResult::NeedsMoreInput { .. } => {
            return Err("truncated JPEG XL file".into());
        }
    }

    let bgra = if color_type == JxlColorType::Bgra {
        interleaved
    } else {
        to_bgra(&interleaved, color_type, width * height)?
    };
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

fn to_bgra(
    interleaved: &[u8],
    color_type: JxlColorType,
    num_pixels: usize,
) -> Result<Vec<u8>, String> {
    let mut bgra = vec![0u8; num_pixels * 4];
    match color_type {
        JxlColorType::Grayscale => {
            for (dst, g) in bgra.chunks_exact_mut(4).zip(interleaved.iter()) {
                dst[0] = *g;
                dst[1] = *g;
                dst[2] = *g;
                dst[3] = 0xFF;
            }
        }
        JxlColorType::GrayscaleAlpha => {
            for (dst, src) in bgra.chunks_exact_mut(4).zip(interleaved.chunks_exact(2)) {
                dst[0] = src[0];
                dst[1] = src[0];
                dst[2] = src[0];
                dst[3] = src[1];
            }
        }
        JxlColorType::Rgb => {
            for (dst, src) in bgra.chunks_exact_mut(4).zip(interleaved.chunks_exact(3)) {
                dst[0] = src[2];
                dst[1] = src[1];
                dst[2] = src[0];
                dst[3] = 0xFF;
            }
        }
        JxlColorType::Rgba => {
            for (dst, src) in bgra.chunks_exact_mut(4).zip(interleaved.chunks_exact(4)) {
                dst[0] = src[2];
                dst[1] = src[1];
                dst[2] = src[0];
                dst[3] = src[3];
            }
        }
        JxlColorType::Bgr => {
            for (dst, src) in bgra.chunks_exact_mut(4).zip(interleaved.chunks_exact(3)) {
                dst[..3].copy_from_slice(src);
                dst[3] = 0xFF;
            }
        }
        JxlColorType::Bgra => bgra.copy_from_slice(interleaved),
        JxlColorType::Cmyk => {
            for (dst, src) in bgra.chunks_exact_mut(4).zip(interleaved.chunks_exact(4)) {
                let (c, m, y, k) = (src[0] as u32, src[1] as u32, src[2] as u32, src[3] as u32);
                dst[0] = ((255 - y) * (255 - k) / 255) as u8;
                dst[1] = ((255 - m) * (255 - k) / 255) as u8;
                dst[2] = ((255 - c) * (255 - k) / 255) as u8;
                dst[3] = 0xFF;
            }
        }
    }
    Ok(bgra)
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

    #[test]
    fn decodes_alpha_image_with_interleaved_alpha() {
        let image = decode_impl(&read_test_file("3x3a_srgb_lossless.jxl")).unwrap();
        assert_eq!((image.width, image.height), (3, 3));
        assert_eq!(image.bgra, expected_3x3_bgra(128));
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
        // Grayscale output must have B == G == R for every pixel.
        for pixel in image.bgra.chunks_exact(4) {
            assert_eq!(pixel[0], pixel[1]);
            assert_eq!(pixel[1], pixel[2]);
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

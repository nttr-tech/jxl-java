package jp.hisano.imageio.jxl;

import jp.hisano.imageio.jxl.internal.WasmJxlDecoder;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;

import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * {@link ImageReader} for JPEG XL images, backed by the jxl-rs decoder
 * compiled to WebAssembly.
 *
 * <p>The reader exposes the first frame of the image (for animations, the
 * first animation frame) as a {@link BufferedImage} of type
 * {@code TYPE_INT_ARGB_PRE} (premultiplied alpha), the translucent image
 * type that Java 2D composites and displays the fastest.
 */
public final class JxlImageReader extends ImageReader {

    private WasmJxlDecoder.Result decoded;

    JxlImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        decoded = null;
    }

    @Override
    public int getNumImages(boolean allowSearch) throws IOException {
        return 1;
    }

    @Override
    public int getWidth(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return decode().getWidth();
    }

    @Override
    public int getHeight(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return decode().getHeight();
    }

    @Override
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return Collections.singletonList(
                        ImageTypeSpecifier.createFromBufferedImageType(
                                BufferedImage.TYPE_INT_ARGB_PRE))
                .iterator();
    }

    @Override
    public IIOMetadata getStreamMetadata() throws IOException {
        return null;
    }

    @Override
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        checkIndex(imageIndex);
        return null;
    }

    @Override
    public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
        checkIndex(imageIndex);
        WasmJxlDecoder.Result result = decode();

        BufferedImage image = new BufferedImage(
                result.getWidth(), result.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(result.getArgb(), 0, pixels, 0, pixels.length);
        return image;
    }

    @Override
    public void reset() {
        super.reset();
        decoded = null;
    }

    @Override
    public void dispose() {
        decoded = null;
        super.dispose();
    }

    private void checkIndex(int imageIndex) {
        if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("Image index out of bounds: " + imageIndex);
        }
    }

    private WasmJxlDecoder.Result decode() throws IOException {
        if (decoded != null) {
            return decoded;
        }
        Object in = getInput();
        if (!(in instanceof ImageInputStream)) {
            throw new IllegalStateException("Input not set");
        }
        decoded = WasmJxlDecoder.decode(readAllBytes((ImageInputStream) in));
        return decoded;
    }

    private static byte[] readAllBytes(ImageInputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            int read = stream.read(buffer);
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}

package com.appkitbox.imageio.jxl;

import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * Service provider for {@link JxlImageReader}. Registered through
 * {@code META-INF/services/javax.imageio.spi.ImageReaderSpi} so that
 * {@code ImageIO.read} discovers JPEG XL support automatically.
 */
public final class JxlImageReaderSpi extends ImageReaderSpi {

    /** Signature of a bare JPEG XL codestream. */
    private static final byte[] CODESTREAM_SIGNATURE = {(byte) 0xFF, 0x0A};

    /** Signature of the ISOBMFF-based JPEG XL container. */
    private static final byte[] CONTAINER_SIGNATURE = {
        0x00, 0x00, 0x00, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A,
    };

    public JxlImageReaderSpi() {
        super(
                "Koji Hisano",
                "0.1.0",
                new String[] {"jxl", "JXL", "JPEG XL", "jpeg xl"},
                new String[] {"jxl"},
                new String[] {"image/jxl"},
                JxlImageReader.class.getName(),
                new Class<?>[] {ImageInputStream.class},
                null,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null);
    }

    @Override
    public boolean canDecodeInput(Object source) throws IOException {
        if (!(source instanceof ImageInputStream)) {
            return false;
        }
        ImageInputStream stream = (ImageInputStream) source;
        byte[] header = new byte[CONTAINER_SIGNATURE.length];
        stream.mark();
        try {
            stream.readFully(header);
        } catch (IOException e) {
            return false;
        } finally {
            stream.reset();
        }
        return startsWith(header, CODESTREAM_SIGNATURE) || startsWith(header, CONTAINER_SIGNATURE);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ImageReader createReaderInstance(Object extension) {
        return new JxlImageReader(this);
    }

    @Override
    public String getDescription(Locale locale) {
        return "JPEG XL image reader backed by jxl-rs (WebAssembly)";
    }
}

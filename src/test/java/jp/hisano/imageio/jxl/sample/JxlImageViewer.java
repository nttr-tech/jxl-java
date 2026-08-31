package jp.hisano.imageio.jxl.sample;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Sample viewer for JPEG XL files, demonstrating that the ImageIO plugin
 * works with plain {@code ImageIO.read} + Swing.
 *
 * <p>On startup a file dialog asks for a {@code .jxl} file, which is then
 * shown with an {@link ImageIcon} in a window sized to the image.
 *
 * <p>Run with: {@code ./gradlew runViewer}
 */
public final class JxlImageViewer {

    private JxlImageViewer() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(JxlImageViewer::selectAndShow);
    }

    private static void selectAndShow() {
        JFileChooser chooser = new JFileChooser(new File("src/test/resources"));
        chooser.setDialogTitle("Select a JPEG XL file");
        chooser.setFileFilter(new FileNameExtensionFilter("JPEG XL images (*.jxl)", "jxl"));
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();

        BufferedImage image;
        try {
            image = ImageIO.read(file);
        } catch (IOException e) {
            showError(file, e.getMessage());
            return;
        }
        if (image == null) {
            showError(file, "no suitable ImageIO reader found");
            return;
        }

        JFrame frame = new JFrame(
                file.getName() + " (" + image.getWidth() + "x" + image.getHeight() + ")");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.add(new JLabel(new ImageIcon(image)));
        // Size the window to the image.
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void showError(File file, String message) {
        JOptionPane.showMessageDialog(
                null,
                "Failed to read " + file + ":\n" + message,
                "JXL Image Viewer",
                JOptionPane.ERROR_MESSAGE);
    }
}

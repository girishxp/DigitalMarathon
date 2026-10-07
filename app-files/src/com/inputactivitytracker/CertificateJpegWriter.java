package com.inputactivitytracker;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import org.w3c.dom.NodeList;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

/** Preserves small colored graphics and text in the certificate's existing JPEG format. */
final class CertificateJpegWriter {
    private CertificateJpegWriter() { }

    static void write(BufferedImage certificate, File output) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("No JPEG image writer is available in this Java runtime.");
        ImageWriter writer = writers.next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            if (stream == null) throw new IOException("The certificate file could not be opened.");
            writer.setOutput(stream);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.97f);

            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(certificate), parameters);
            String format = "javax_imageio_jpeg_image_1.0";
            IIOMetadataNode tree = (IIOMetadataNode) metadata.getAsTree(format);
            NodeList components = tree.getElementsByTagName("componentSpec");
            for (int index = 0; index < components.getLength(); index++) {
                IIOMetadataNode component = (IIOMetadataNode) components.item(index);
                component.setAttribute("HsamplingFactor", "1");
                component.setAttribute("VsamplingFactor", "1");
            }
            metadata.setFromTree(format, tree);
            writer.write(null, new IIOImage(certificate, null, metadata), parameters);
        } finally {
            writer.dispose();
        }
    }
}

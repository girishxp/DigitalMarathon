package com.inputactivitytracker;

import javax.swing.Icon;
import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Filters the artwork to the display's pixel size while keeping the icon's logical size. */
final class AppBrandIcon implements Icon {
    private final BufferedImage image;
    private final int width;
    private final int height;
    private final Map<RasterSize, BufferedImage> preparedImages = new ConcurrentHashMap<>();

    AppBrandIcon(Image image, int width, int height) {
        this.image = premultipliedCopy(Objects.requireNonNull(image));
        this.width = width;
        this.height = height;
    }

    @Override public int getIconWidth() { return width; }
    @Override public int getIconHeight() { return height; }

    static List<Image> windowImages(Image source) {
        BufferedImage artwork = premultipliedCopy(Objects.requireNonNull(source));
        List<Image> images = new ArrayList<>();
        for (int size : new int[] {1024, 512, 256, 128, 64, 48, 32, 24, 16}) {
            images.add(prepareImage(artwork, size, size));
        }
        return images;
    }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            AffineTransform transform = g.getTransform();
            int pixelWidth = Math.max(1, (int) Math.round(width
                    * Math.hypot(transform.getScaleX(), transform.getShearY())));
            int pixelHeight = Math.max(1, (int) Math.round(height
                    * Math.hypot(transform.getScaleY(), transform.getShearX())));
            RasterSize size = new RasterSize(pixelWidth, pixelHeight);
            BufferedImage prepared = preparedImages.computeIfAbsent(size,
                    key -> prepareImage(image, key.width(), key.height()));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(prepared, x, y, width, height, component);
        } finally {
            g.dispose();
        }
    }

    private static BufferedImage premultipliedCopy(Image source) {
        BufferedImage copy = new BufferedImage(source.getWidth(null), source.getHeight(null),
                BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = copy.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static BufferedImage prepareImage(BufferedImage source, int width, int height) {
        BufferedImage current = source;
        // Each halving averages neighboring pixels before the final small reduction.
        // Premultiplied alpha keeps transparent edges from picking up colored fringes.
        while (current.getWidth() >= width * 2 || current.getHeight() >= height * 2) {
            current = resized(current, Math.max(width, current.getWidth() / 2),
                    Math.max(height, current.getHeight() / 2), RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (current.getWidth() == width && current.getHeight() == height) return current;
        return resized(current, width, height, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }

    private static BufferedImage resized(BufferedImage source, int width, int height, Object interpolation) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = result.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    private record RasterSize(int width, int height) { }
}

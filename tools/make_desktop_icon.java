// Copyright (C) 2025-2026 Juan Carlos Manzanares Serrano <jc.manzanares.serrano@gmail.com>
// SPDX-License-Identifier: GPL-3.0-only

// Generates desktopApp/icons/vizmini2.png (512x512) with the same drawing as the Android adaptive icon
// (app/src/main/res/drawable/ic_launcher_*.xml), in a rounded square. Usage: java tools/make_desktop_icon.java
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class make_desktop_icon {
    public static void main(String[] a) throws Exception {
        int size = 512;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // The central area of the adaptive icon is shown (from 9 to 99 of 108), like a rounded launcher.
        double s = size / 90.0;
        g.setClip(new RoundRectangle2D.Double(0, 0, size, size, size * 0.36, size * 0.36));
        g.scale(s, s);
        g.translate(-9, -9);
        g.setPaint(new LinearGradientPaint(0, 0, 108, 108, new float[]{0f, .5f, 1f},
            new Color[]{new Color(0x0A1626), new Color(0x0F2C44), new Color(0x0B6574)}));
        g.fill(new Rectangle2D.Double(0, 0, 108, 108));
        g.setColor(new Color(255, 255, 255, 0x1F));
        g.setStroke(new BasicStroke(0.6f));
        for (int v = 30; v <= 78; v += 12) { g.draw(new Line2D.Double(18, v, 90, v)); g.draw(new Line2D.Double(v, 18, v, 90)); }
        // Axes (group with scale 1.05 and translation -4.8, 1.5).
        g.translate(-4.8, 1.5);
        g.scale(1.05, 1.05);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        axis(g, 0x4FA3FF, 56, 36, new double[]{56, 29, 61.5, 38, 50.5, 38});
        axis(g, 0xFF5A5F, 72, 67, new double[]{78, 70.5, 68.4, 71.1, 73.9, 61.6});
        axis(g, 0x3DDC97, 40, 67, new double[]{34, 70.5, 38.1, 61.6, 43.6, 71.1});
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(52, 54, 8, 8));
        g.dispose();
        File out = new File(a.length > 0 ? a[0] : "desktopApp/icons/vizmini2.png");
        ImageIO.write(img, "png", out);
        System.out.println("Icono: " + out);
    }

    static void axis(Graphics2D g, int rgb, double x, double y, double[] tri) {
        g.setColor(new Color(rgb));
        g.draw(new Line2D.Double(56, 58, x, y));
        Path2D p = new Path2D.Double();
        p.moveTo(tri[0], tri[1]); p.lineTo(tri[2], tri[3]); p.lineTo(tri[4], tri[5]); p.closePath();
        g.fill(p);
    }
}

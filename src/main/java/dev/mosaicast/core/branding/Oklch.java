// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.branding;

/**
 * sRGB ↔ OKLCH colour math and WCAG contrast (ARCHITECTURE §12.3). OKLCH is perceptually uniform, so
 * lightness/chroma adjustments look even; WCAG relative luminance drives the contrast clamp that keeps
 * generated themes readable. OKLab coefficients are Björn Ottosson's.
 */
public final class Oklch {

    private Oklch() {
    }

    /** An OKLCH colour: perceptual lightness {@code l} in [0,1], chroma {@code c}, hue {@code h} in degrees. */
    public record Color(double l, double c, double h) {
    }

    // ---- hex ↔ sRGB (0..1) ----

    /** Parses {@code #rrggbb} into linear-free sRGB components in [0,1]. */
    public static double[] hexToSrgb(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() != 6) {
            throw new IllegalArgumentException("Expected #rrggbb, got: " + hex);
        }
        return new double[] {
            Integer.parseInt(s.substring(0, 2), 16) / 255.0,
            Integer.parseInt(s.substring(2, 4), 16) / 255.0,
            Integer.parseInt(s.substring(4, 6), 16) / 255.0,
        };
    }

    public static String srgbToHex(double[] rgb) {
        return String.format("#%02x%02x%02x", channel(rgb[0]), channel(rgb[1]), channel(rgb[2]));
    }

    private static int channel(double v) {
        long rounded = Math.round(Math.max(0.0, Math.min(1.0, v)) * 255.0);
        return (int) rounded;
    }

    // ---- sRGB gamma ----

    private static double toLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double toGamma(double c) {
        double v = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ---- OKLCH ↔ sRGB ----

    public static Color hexToOklch(String hex) {
        double[] rgb = hexToSrgb(hex);
        double r = toLinear(rgb[0]);
        double g = toLinear(rgb[1]);
        double b = toLinear(rgb[2]);

        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);

        double okL = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        double okA = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double okB = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;

        double chroma = Math.hypot(okA, okB);
        double hue = Math.toDegrees(Math.atan2(okB, okA));
        if (hue < 0) {
            hue += 360.0;
        }
        return new Color(okL, chroma, hue);
    }

    public static String oklchToHex(Color color) {
        double hr = Math.toRadians(color.h());
        double okA = color.c() * Math.cos(hr);
        double okB = color.c() * Math.sin(hr);

        double l_ = color.l() + 0.3963377774 * okA + 0.2158037573 * okB;
        double m_ = color.l() - 0.1055613458 * okA - 0.0638541728 * okB;
        double s_ = color.l() - 0.0894841775 * okA - 1.2914855480 * okB;

        double l = l_ * l_ * l_;
        double m = m_ * m_ * m_;
        double s = s_ * s_ * s_;

        double r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;

        return srgbToHex(new double[] {toGamma(r), toGamma(g), toGamma(b)});
    }

    // ---- WCAG contrast ----

    /** WCAG relative luminance of an sRGB colour. */
    public static double relativeLuminance(String hex) {
        double[] rgb = hexToSrgb(hex);
        double r = toLinear(rgb[0]);
        double g = toLinear(rgb[1]);
        double b = toLinear(rgb[2]);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG contrast ratio between two colours, in [1, 21]. */
    public static double contrast(String a, String b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }
}

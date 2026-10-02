// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.blob;

/**
 * Identifies an upload's format from its leading bytes (ARCHITECTURE §12.2, §11).
 *
 * <p><strong>Why the bytes and not the header:</strong> {@code Content-Type} on a multipart part is written
 * by the client. Trusting it means an SVG labelled {@code image/png} is accepted, stored, and later served
 * back with that same attacker-chosen type — and containment then rests entirely on {@code nosniff} being
 * set somewhere else, which is a guarantee held by a different file. What is stored is what these bytes say.
 *
 * <p><strong>Magic numbers rather than a decode.</strong> The question is only whether the file is the
 * format it claims; decoding attacker-supplied images to find out is a larger attack surface than the one
 * being closed.
 *
 * <p><strong>There is no SVG case, deliberately.</strong> SVG is a script container wearing an image's file
 * extension, and §12.2 takes the conservative half of "sanitize or restrict to raster". An unrecognised
 * format returns {@code null} and every caller rejects it, so adding a case here is the only way SVG could
 * ever be stored — which is the point of it being absent.
 *
 * <p>Recognising a format is not permission to store it: each caller intersects this answer with its own
 * allow-list (branding takes four raster types; a plugin takes what its manifest declares and the operator
 * permits).
 */
public final class MimeSniffer {

    /**
     * How many leading bytes to hand to {@link #sniff(byte[])}.
     *
     * <p>Sized for the longest signature that needs an offset — the ISO base-media {@code ftyp} brand at
     * bytes 8–11 — with room for a second brand.
     */
    public static final int HEAD_BYTES = 32;

    private MimeSniffer() {
    }

    /**
     * The content type these bytes actually are.
     *
     * @param head the file's leading bytes, up to {@link #HEAD_BYTES}; a short array simply matches fewer
     *             signatures rather than failing
     * @return the content type, or {@code null} when it is none of the recognised formats
     */
    public static String sniff(byte[] head) {
        if (head == null) {
            return null;
        }
        // --- raster images ---
        if (startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        // ICO: a 2-byte zero reserved field, then image type 1 (icon) or 2 (cursor).
        if (startsWith(head, 0x00, 0x00, 0x01, 0x00) || startsWith(head, 0x00, 0x00, 0x02, 0x00)) {
            return "image/x-icon";
        }
        if (startsWith(head, 'G', 'I', 'F', '8', '7', 'a') || startsWith(head, 'G', 'I', 'F', '8', '9', 'a')) {
            return "image/gif";
        }
        // RIFF containers carry their real format at bytes 8–11: WEBP is an image, WAVE is audio.
        if (startsWith(head, 'R', 'I', 'F', 'F')) {
            if (matchesAt(head, 8, 'W', 'E', 'B', 'P')) {
                return "image/webp";
            }
            if (matchesAt(head, 8, 'W', 'A', 'V', 'E')) {
                return "audio/wav";
            }
            return null;
        }
        // --- ISO base media (MP4 family): "ftyp" at byte 4, the brand at byte 8 ---
        if (matchesAt(head, 4, 'f', 't', 'y', 'p')) {
            if (matchesAt(head, 8, 'a', 'v', 'i', 'f') || matchesAt(head, 8, 'a', 'v', 'i', 's')) {
                return "image/avif";
            }
            // Only the audio brand. `isom`/`mp42` are the video brands and answering `audio/mp4` for one
            // would be a confident wrong answer about a file the caller may then serve as audio.
            if (matchesAt(head, 8, 'M', '4', 'A', ' ')) {
                return "audio/mp4";
            }
            return null;
        }
        // --- audio ---
        if (startsWith(head, 'O', 'g', 'g', 'S')) {
            // Ogg is a container; audio is what a podcast platform meets, and the alternative is refusing a
            // legitimate file for a video case that has no customer here.
            return "audio/ogg";
        }
        // MP3: either an ID3 tag or a raw frame sync (11 set bits, then a valid MPEG-1/2 Layer III header).
        if (startsWith(head, 'I', 'D', '3')) {
            return "audio/mpeg";
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && ((head[1] & 0xE0) == 0xE0)) {
            return "audio/mpeg";
        }
        // --- archives ---
        // ZIP: a local file header, or the end-of-central-directory record that is all an empty archive
        // contains (core#246). Spanned archives (PK 07 08) are deliberately not recognised: a part of a split
        // archive is not a file anyone can open on its own. Office documents, EPUBs and JARs are ZIPs as well
        // and are reported as such, which is true: whoever opens one gets an archive, and no allow-list that
        // admits application/zip admits a type that renders.
        if (startsWith(head, 'P', 'K', 0x03, 0x04) || startsWith(head, 'P', 'K', 0x05, 0x06)) {
            return "application/zip";
        }
        return null;
    }

    /**
     * A declared content type in the one spelling the allow-lists use.
     *
     * <p>Lower-cased, parameters dropped, and the ZIP aliases folded into {@code application/zip}: browsers
     * disagree on what a .zip is — Chrome on Windows says {@code application/x-zip-compressed}, others
     * {@code application/x-zip} — and an upload refused for the browser's choice of spelling is refused for
     * nothing the visitor did (core#246). The sniffed type is what gets stored either way.
     *
     * @param declared what a client or a manifest said, possibly null
     * @return the canonical type, or an empty string for nothing
     */
    public static String canonicalType(String declared) {
        if (declared == null) {
            return "";
        }
        String type = declared.trim().toLowerCase(java.util.Locale.ROOT);
        int parameters = type.indexOf(';');
        if (parameters >= 0) {
            type = type.substring(0, parameters).trim();
        }
        return switch (type) {
            case "application/x-zip-compressed", "application/x-zip", "application/zip-compressed",
                 "multipart/x-zip" -> "application/zip";
            default -> type;
        };
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        return matchesAt(bytes, 0, prefix);
    }

    private static boolean matchesAt(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != (expected[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}

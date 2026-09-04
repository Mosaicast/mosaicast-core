// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.auth.avatar;

import dev.mosaicast.core.web.NotFoundException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's avatar, as bytes (ARCHITECTURE §8.7).
 *
 * <p>Public, because the pictures it serves are already shown to whoever can see a leaderboard or a comment
 * — and because what made the old arrangement a disclosure was the <em>URL</em>, not the image. This
 * endpoint reveals a picture and a user id the caller already had; the provider's identifier stays on the
 * server, which is the entire point of proxying rather than redirecting.
 *
 * <p>It always answers. A user with no provider picture gets the generated avatar (§8.7), so a caller —
 * including a plugin rendering {@code ctx.users} results (§8.8) — never has to implement a fallback of its
 * own, and never has to tell "no picture" apart from "no such user".
 */
@RestController
public class AvatarController {

    private final AvatarService avatars;

    public AvatarController(AvatarService avatars) {
        this.avatars = avatars;
    }

    /**
     * The avatar for a user.
     *
     * @param id          whose avatar
     * @param ifNoneMatch the validator a browser already holds, if any
     * @return the image, or {@code 304} when the caller's copy is current
     */
    @GetMapping("/api/users/{id}/avatar")
    public ResponseEntity<byte[]> avatar(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        AvatarService.Avatar avatar = avatars.avatarFor(id)
                .orElseThrow(() -> new NotFoundException("No such user: " + id));

        if (avatar.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(304).eTag(avatar.etag()).build();
        }
        return ResponseEntity.ok()
                .eTag(avatar.etag())
                .cacheControl(CacheControl.maxAge(avatars.browserTtl()).cachePublic())
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                // The bytes come from a third party and are served from our origin. `nosniff` is what stops
                // a browser deciding a mislabelled body is something more interesting than an image.
                .header("X-Content-Type-Options", "nosniff")
                .body(avatar.bytes());
    }
}

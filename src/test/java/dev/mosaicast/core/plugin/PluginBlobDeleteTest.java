// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.mosaicast.core.blob.InMemoryBlobStore;
import dev.mosaicast.plugin.api.Role;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;

/** Who may delete a plugin file over HTTP: the uploader, and staff (core#201). */
class PluginBlobDeleteTest {

    private final InMemoryBlobStore store = new InMemoryBlobStore();
    private final PluginBlobService blobs = new PluginBlobService(store,
            new PluginBlobProperties(1_000_000, 100_000, null, null, List.of("image/png")),
            mock(PluginBlobGrantRepository.class), mock(JdbcTemplate.class), DataSize.ofBytes(-1),
            DataSize.ofBytes(-1));

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private String uploadedBy(UUID uploader) {
        String ref = UUID.randomUUID().toString();
        store.put(PluginBlobService.namespaceOf("gallery"), ref, new ByteArrayInputStream(new byte[] {1}),
                "image/png", "a.png", uploader);
        return ref;
    }

    @Test
    void aFanMayNotDeleteAnotherFansFile() {
        // Under a manifest that lets fans write, the floor alone let any of them delete any file.
        String ref = uploadedBy(alice);

        assertThatThrownBy(() -> blobs.delete("gallery", ref, bob, Optional.of(Role.FAN)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(blobs.stat("gallery", ref)).isPresent();
    }

    @Test
    void aFanMayDeleteTheirOwnAndStaffMayDeleteAny() {
        String own = uploadedBy(alice);
        String others = uploadedBy(alice);

        assertThat(blobs.delete("gallery", own, alice, Optional.of(Role.FAN))).isTrue();
        assertThat(blobs.delete("gallery", others, bob, Optional.of(Role.PODCASTER))).isTrue();
        assertThat(blobs.stat("gallery", own)).isEmpty();
        assertThat(blobs.stat("gallery", others)).isEmpty();
    }

    @Test
    void deletingWhatIsNotThereIsStillQuietlyFine() {
        assertThat(blobs.delete("gallery", UUID.randomUUID().toString(), bob, Optional.of(Role.FAN))).isFalse();
    }
}

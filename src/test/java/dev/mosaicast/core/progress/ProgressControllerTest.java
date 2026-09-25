// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.web.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A position is only ever about an episode that exists (core#201). */
class ProgressControllerTest {

    private final ListeningProgressRepository progress = mock(ListeningProgressRepository.class);
    private final EpisodeRefRepository episodes = mock(EpisodeRefRepository.class);
    private final ProgressController controller = new ProgressController(progress, episodes);
    private final UUID user = UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken signedIn = new UsernamePasswordAuthenticationToken(
            user.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_FAN")));

    @Test
    void refusesToStartAPositionForAnEpisodeThatDoesNotExist() {
        // Any UUID used to create a row: unbounded rows in the caller's own partition, and a 204 for nothing.
        UUID nowhere = UUID.randomUUID();
        when(progress.findById(any())).thenReturn(Optional.empty());
        when(episodes.findVisibleById(nowhere)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.put(nowhere, new ProgressController.UpdatePosition(42), signedIn))
                .isInstanceOf(NotFoundException.class);
        verify(progress, never()).save(any());
    }

    @Test
    void storesAPositionForAVisibleEpisode() {
        UUID episode = UUID.randomUUID();
        when(progress.findById(any())).thenReturn(Optional.empty());
        when(episodes.findVisibleById(episode)).thenReturn(Optional.of(mock(EpisodeRef.class)));

        assertThat(controller.put(episode, new ProgressController.UpdatePosition(42), signedIn)
                .getStatusCode().value()).isEqualTo(204);
        verify(progress).save(any());
    }
}

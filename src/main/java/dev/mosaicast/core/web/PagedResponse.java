// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * The uniform pagination envelope for list endpoints (ARCHITECTURE §13 — "list endpoints paginate from
 * day one"). Wraps a Spring Data {@link Page} of entities as a page of DTOs.
 *
 * @param items         the page contents (already mapped to DTOs)
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements total number of matching items across all pages
 * @param totalPages    total number of pages
 * @param <T>           the DTO type
 */
public record PagedResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    /** Maps a {@link Page} of entities to a {@code PagedResponse} of DTOs. */
    public static <E, T> PagedResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}

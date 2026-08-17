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

    /**
     * The largest page any list endpoint serves.
     *
     * <p>Lives here rather than in one controller because a caller learns paging once: the plugin doc
     * surface and the plugin schema surface answer the same way to {@code size=10000}, and a second copy
     * of the number is a second thing to forget to change.
     */
    public static final int MAX_PAGE_SIZE = 200;

    /** The requested page index, floored at 0. */
    public static int page(int requested) {
        return Math.max(0, requested);
    }

    /** The requested page size, clamped into {@code [1, MAX_PAGE_SIZE]}. */
    public static int size(int requested) {
        return Math.min(Math.max(1, requested), MAX_PAGE_SIZE);
    }

    /**
     * Builds a page from an already-sliced list and the total behind it — for endpoints whose store is
     * not Spring Data and hands back a {@link List} plus a separate count.
     *
     * @param items the rows on this page, already sliced
     * @param page  the zero-based page index, already normalized
     * @param size  the page size, already clamped
     * @param total the number of matching rows across all pages
     */
    public static <T> PagedResponse<T> of(List<T> items, int page, int size, long total) {
        return new PagedResponse<>(items, page, size, total, (int) Math.ceil((double) total / size));
    }

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

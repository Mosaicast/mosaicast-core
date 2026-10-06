// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feed;

import dev.mosaicast.core.episode.EpisodeDisplay;
import dev.mosaicast.core.episode.EpisodeDisplayRepository;
import dev.mosaicast.plugin.api.EpisodePhase;
import dev.mosaicast.core.episode.EpisodeRef;
import dev.mosaicast.core.episode.EpisodeRefRepository;
import dev.mosaicast.core.episode.EpisodeStatus;
import dev.mosaicast.core.episode.ShowNotes;
import dev.mosaicast.core.log.LogSafe;
import dev.mosaicast.core.plugin.PluginDataRepository;
import dev.mosaicast.core.web.CodedBadRequest;
import dev.mosaicast.core.web.NotFoundException;
import dev.mosaicast.plugin.api.DisplaySnapshot;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Planning episodes before their feed item exists (ARCHITECTURE §4.3, core#252): create — by hand or from a
 * script holding an access token — edit, announce, cancel, and match to an episode the feed already brought.
 *
 * <p>Everything here acts on {@code PLANNED} episodes only. Once released, an episode's data is the feed's,
 * and the reconciler would overwrite an edit on the next poll; refusing it says so instead of pretending.
 */
@Service
public class PlannedEpisodeService {

    private static final Logger log = LoggerFactory.getLogger(PlannedEpisodeService.class);

    /** The plugin doc-store scope type episode documents live under. */
    private static final String EPISODE_SCOPE = "episode";

    private final FeedRepository feeds;
    private final FeedService feedService;
    private final EpisodeRefRepository refs;
    private final EpisodeDisplayRepository displays;
    private final PluginDataRepository pluginData;
    private final BindingSuggestionRepository suggestions;
    private final JdbcTemplate jdbc;
    private final org.springframework.context.ApplicationEventPublisher events;

    public PlannedEpisodeService(FeedRepository feeds, FeedService feedService, EpisodeRefRepository refs,
                                 EpisodeDisplayRepository displays, PluginDataRepository pluginData,
                                 BindingSuggestionRepository suggestions, JdbcTemplate jdbc,
                                 org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
        this.feeds = feeds;
        this.feedService = feedService;
        this.refs = refs;
        this.displays = displays;
        this.pluginData = pluginData;
        this.suggestions = suggestions;
        this.jdbc = jdbc;
    }

    /** What the API and the admin page show of a planned episode. */
    public record PlannedView(UUID id, String slug, String url, UUID feedId, String feedSlug, String feedTitle,
                              Integer season, Integer episodeNo, String title, String description,
                              EpisodePhase phase, Instant announceAt, String clientRef, Instant createdAt) {
    }

    /** An imported episode a planned one could be matched to, and whether something already hangs on it. */
    public record MatchCandidate(String slug, String title, Integer season, Integer episodeNo,
                                 Instant publishedAt, boolean hasPluginData) {
    }

    /**
     * Plans an episode on a feed, addressed by its slug or id.
     *
     * <p>With a {@code clientRef} that this feed has already seen, nothing is created: the existing plan is
     * returned as it is, so a script that retries after a timeout gets the episode it already made rather than
     * a second one with a new slug.
     *
     * @param announceAt {@code null} for quiet until announced, {@code "now"}, or an ISO-8601 instant
     * @return the plan, and whether this call created it
     */
    @Transactional
    public Created create(String feedRef, Integer season, Integer episodeNo, String title, String description,
                          String announceAt, String clientRef) {
        Feed feed = resolveFeed(feedRef);
        if (clientRef != null && !clientRef.isBlank()) {
            var existing = refs.findByFeedIdAndClientRef(feed.getId(), clientRef);
            if (existing.isPresent()) {
                return new Created(view(existing.get(), feed), false);
            }
        }
        UUID id = feedService.createPlannedEpisode(feed.getId(), season, episodeNo, title, description,
                parseAnnounce(announceAt));
        EpisodeRef ref = refs.findById(id).orElseThrow();
        if (clientRef != null && !clientRef.isBlank()) {
            ref.clientRef(clientRef);
            refs.save(ref);
        }
        return new Created(view(ref, feed), true);
    }

    /** A plan and whether this call made it (201) or found it (200). */
    public record Created(PlannedView plan, boolean created) {
    }

    /** Every planned episode — quiet and announced — optionally of one feed, newest plan first. */
    @Transactional(readOnly = true)
    public List<PlannedView> list(String feedRef) {
        UUID only = feedRef == null || feedRef.isBlank() ? null : resolveFeed(feedRef).getId();
        Map<UUID, Feed> feedsById =
                feeds.findAll().stream().collect(Collectors.toMap(Feed::getId, Function.identity()));
        return refs.findByStatusOrderByFirstSeenAtDesc(EpisodeStatus.PLANNED).stream()
                .filter(ref -> only == null || ref.getFeedId().equals(only))
                .map(ref -> view(ref, feedsById.get(ref.getFeedId())))
                .toList();
    }

    /**
     * Edits a planned episode. Absent fields stay as they are; {@code announceAt} may be {@code null} (back to
     * quiet), {@code "now"}, or an instant. The slug does not change: plugins and links already hold it.
     */
    @Transactional
    public PlannedView update(String slug, JsonNode patch) {
        EpisodeRef ref = planned(slug);
        Instant announcedBefore = ref.getAnnounceAt();
        DisplaySnapshot current = ref.getProvisionalDisplay();
        String title = patch.has("title") ? text(patch, "title") : current.title();
        if (title == null || title.isBlank()) {
            throw new CodedBadRequest("planned.title.required", "A planned episode needs a title.");
        }
        if (title.length() > 200) {
            throw new CodedBadRequest("planned.title.tooLong", "A title can be at most 200 characters.");
        }
        String description = patch.has("description") ? text(patch, "description") : current.description();
        if (description == null) {
            description = "";
        }
        Integer season = patch.has("season") ? number(patch, "season") : ref.getSeason();
        Integer episodeNo = patch.has("episodeNo") ? number(patch, "episodeNo") : ref.getEpisodeNo();
        ref.replan(season, episodeNo, provisional(title, description));
        if (patch.has("announceAt")) {
            JsonNode when = patch.get("announceAt");
            ref.announceAt(parseAnnounce(when.isNull() ? null : when.asString()));
        }
        refs.save(ref);
        publishIfPhaseMoved(ref, announcedBefore);
        log.info("Planned episode '{}' edited", slug);
        return view(ref, feeds.findById(ref.getFeedId()).orElse(null));
    }

    /** Makes a planned episode public now. */
    @Transactional
    public PlannedView announce(String slug) {
        EpisodeRef ref = planned(slug);
        Instant announcedBefore = ref.getAnnounceAt();
        ref.announceAt(Instant.now());
        refs.save(ref);
        publishIfPhaseMoved(ref, announcedBefore);
        log.info("Planned episode '{}' announced", slug);
        return view(ref, feeds.findById(ref.getFeedId()).orElse(null));
    }

    /**
     * Tells plugins when an edit moved a plan between {@code PLANNED} and {@code UPCOMING} (core#270) — compared
     * before and after at <em>one</em> instant, taken after the write, so an {@code announceAt} of "now" counts
     * as announced and an untouched one as no change. A plan going quiet again is the case that matters: until
     * this, a plugin kept publishing the hidden episode until its next schedule tick.
     */
    private void publishIfPhaseMoved(EpisodeRef ref, Instant announcedBefore) {
        Instant now = Instant.now();
        dev.mosaicast.plugin.api.EpisodePhase before = EpisodeRef.phaseOf(ref.getStatus(), announcedBefore, now);
        dev.mosaicast.plugin.api.EpisodePhase after = ref.phase(now);
        if (before != after) {
            events.publishEvent(new dev.mosaicast.core.episode.EpisodePhaseChangedEvent(ref.getSlug(), after));
        }
    }

    /**
     * Cancels a planned episode: the episode goes, and so do the plugin documents prepared for it — a bingo
     * for an episode that will never exist has nowhere left to be shown.
     *
     * @return how many plugin documents went with it
     */
    @Transactional
    public int cancel(String slug) {
        EpisodeRef ref = planned(slug);
        int documents = pluginData.deleteByScope(EPISODE_SCOPE, List.of(slug));
        refs.delete(ref);
        // Its episode-scoped documents went with it; a site- or feed-scope one a plugin published can still
        // name it, and is the plugin's to drop (core#270).
        events.publishEvent(new dev.mosaicast.core.episode.EpisodePhaseChangedEvent(slug, null));
        log.info("Planned episode '{}' cancelled, {} plugin document(s) removed", slug, documents);
        return documents;
    }

    /**
     * Released episodes of the same feed a planned one could be matched to: imported from the feed, newest
     * first. Those that already carry plugin data are listed too, flagged, because a match onto them is
     * refused and saying why beats hiding them.
     */
    @Transactional(readOnly = true)
    public List<MatchCandidate> candidates(String slug) {
        EpisodeRef plan = planned(slug);
        return refs.findByFeedIdAndStatus(plan.getFeedId(), EpisodeStatus.PUBLISHED).stream()
                .filter(ref -> ref.getExternalGuid() != null)
                .map(ref -> {
                    DisplaySnapshot snapshot = displays.findById(ref.getId()).map(EpisodeDisplay::getSnapshot)
                            .orElse(null);
                    return new MatchCandidate(ref.getSlug(), snapshot == null ? ref.getSlug() : snapshot.title(),
                            ref.getSeason(), ref.getEpisodeNo(), snapshot == null ? null : snapshot.publishedAt(),
                            hasPluginData(ref));
                })
                .sorted(Comparator.comparing(MatchCandidate::publishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Matches a planned episode to one the feed already brought in as a separate episode — when neither the
     * numbers nor the title found the pair on their own.
     *
     * <p>The planned episode keeps its identity, because that is what was prepared: its id, its slug and the
     * plugin data on them. It takes over the imported episode's feed item — guid, numbers, display — and the
     * imported duplicate goes. Listeners' progress, pins and the duplicate's own feed and podcaster tags move
     * across, so nothing a visitor did is lost.
     *
     * <p><strong>Refused if the duplicate already has plugin data</strong> — documents, or tags a plugin set.
     * Merging two plugins' worth of state about "the same" episode is a decision about that plugin's data that
     * the host cannot make for it, and dropping either side would lose something a person made. Both episodes
     * stay as they are, and the message says what to do.
     */
    @Transactional
    public PlannedView match(String plannedSlug, String importedSlug) {
        EpisodeRef plan = planned(plannedSlug);
        EpisodeRef imported = refs.findBySlug(importedSlug)
                .orElseThrow(() -> new NotFoundException("No episode " + importedSlug));
        if (!imported.getFeedId().equals(plan.getFeedId()) || imported.getStatus() != EpisodeStatus.PUBLISHED
                || imported.getExternalGuid() == null) {
            throw new CodedBadRequest("planned.match.notCandidate",
                    "Only a released episode of the same feed, imported from it, can be matched.");
        }
        if (hasPluginData(imported)) {
            throw new CodedBadRequest("planned.match.targetHasPluginData",
                    "The released episode already has plugin data of its own (for example a bingo or a poll), "
                            + "so it cannot be merged into the planned one without losing something. Remove that "
                            + "data first, or keep both episodes.");
        }
        String guid = imported.getExternalGuid();
        DisplaySnapshot snapshot =
                displays.findById(imported.getId()).map(EpisodeDisplay::getSnapshot).orElse(null);
        UUID from = imported.getId();
        UUID to = plan.getId();

        // What visitors and editors did on the duplicate moves to the episode that stays.
        jdbc.update("""
                insert into listening_progress (user_id, episode_ref_id, position_seconds, updated_at)
                select user_id, ?, position_seconds, updated_at from listening_progress where episode_ref_id = ?
                on conflict (user_id, episode_ref_id) do update
                  set position_seconds = greatest(listening_progress.position_seconds, excluded.position_seconds),
                      updated_at = greatest(listening_progress.updated_at, excluded.updated_at)
                """, to, from);
        jdbc.update("""
                insert into episode_tag (episode_ref_id, tag, source)
                select ?, tag, source from episode_tag where episode_ref_id = ?
                on conflict do nothing
                """, to, from);
        jdbc.update("""
                insert into episode_pin (episode_ref_id, related_ref_id, position)
                select ?, related_ref_id, position from episode_pin where episode_ref_id = ? and related_ref_id <> ?
                on conflict do nothing
                """, to, from, to);
        jdbc.update("""
                insert into episode_pin (episode_ref_id, related_ref_id, position)
                select episode_ref_id, ?, position from episode_pin where related_ref_id = ? and episode_ref_id <> ?
                on conflict do nothing
                """, to, from, to);

        // The duplicate goes first: the feed's guid is unique per feed, and the plan is about to carry it.
        refs.delete(imported);
        refs.flush();
        plan.bindToFeedItem(guid, imported.getFeedSeason(), imported.getFeedEpisodeNo());
        if (imported.isNumbersPinned()) {
            // Like its tags and pins: numbers a podcaster set on the duplicate move across with it (§4.4).
            plan.pinNumbers(imported.getSeason(), imported.getEpisodeNo());
        }
        refs.save(plan);
        events.publishEvent(new dev.mosaicast.core.episode.EpisodeReleasedEvent(plan.getSlug()));
        // The duplicate was a released episode a plugin may have named; it no longer exists (core#270).
        events.publishEvent(new dev.mosaicast.core.episode.EpisodePhaseChangedEvent(importedSlug, null));
        if (snapshot != null) {
            displays.save(new EpisodeDisplay(to, snapshot));
        }
        suggestions.deleteByPlannedRefId(to);
        // Other plans may have been offered this same feed item as a fuzzy match; it is taken now.
        suggestions.deleteByFeedIdAndRawGuid(plan.getFeedId(), guid);
        log.info("Planned episode '{}' matched to imported '{}' ({}), which was removed",
                plannedSlug, importedSlug, LogSafe.of(snapshot == null ? guid : snapshot.title()));
        return view(plan, feeds.findById(plan.getFeedId()).orElse(null));
    }

    private boolean hasPluginData(EpisodeRef ref) {
        if (pluginData.countByScope(EPISODE_SCOPE, List.of(ref.getSlug())) > 0) {
            return true;
        }
        Integer pluginTags = jdbc.queryForObject(
                "select count(*) from episode_tag where episode_ref_id = ? and source like 'plugin:%'",
                Integer.class, ref.getId());
        return pluginTags != null && pluginTags > 0;
    }

    private EpisodeRef planned(String slug) {
        EpisodeRef ref = refs.findBySlug(slug).orElseThrow(() -> new NotFoundException("No episode " + slug));
        if (ref.getStatus() != EpisodeStatus.PLANNED) {
            throw new CodedBadRequest("planned.notPlanned",
                    "This episode has been released; its data comes from the feed now and cannot be edited here.");
        }
        return ref;
    }

    private Feed resolveFeed(String feedRef) {
        return feeds.findBySlug(feedRef)
                .or(() -> {
                    try {
                        return feeds.findById(UUID.fromString(feedRef));
                    } catch (IllegalArgumentException e) {
                        return java.util.Optional.empty();
                    }
                })
                .orElseThrow(() -> new NotFoundException("No feed " + feedRef));
    }

    /**
     * {@code null} or blank: quiet until announced. {@code "now"}: public at once. Anything else must be an
     * ISO-8601 instant; one in the past is the same as now.
     */
    static Instant parseAnnounce(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("now".equalsIgnoreCase(value.trim())) {
            return Instant.now();
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new CodedBadRequest("planned.announceAt.invalid",
                    "announceAt must be an ISO-8601 instant such as 2026-11-01T09:00:00Z, or \"now\".");
        }
    }

    private static DisplaySnapshot provisional(String title, String description) {
        return new DisplaySnapshot(title, description, null, null, null, null, null, null, null,
                ShowNotes.plainText(description));
    }

    private static String text(JsonNode patch, String field) {
        JsonNode value = patch.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Integer number(JsonNode patch, String field) {
        JsonNode value = patch.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToInt() || value.asInt() < 0) {
            throw new CodedBadRequest("planned.number.invalid", field + " must be a whole number, 0 or more.");
        }
        return value.asInt();
    }

    private static PlannedView view(EpisodeRef ref, Feed feed) {
        DisplaySnapshot display = ref.getProvisionalDisplay();
        return new PlannedView(ref.getId(), ref.getSlug(), "/episodes/" + ref.getSlug(), ref.getFeedId(),
                feed == null ? null : feed.getSlug(), feed == null ? null : feed.getTitle(),
                ref.getSeason(), ref.getEpisodeNo(), display == null ? null : display.title(),
                display == null ? null : display.description(), ref.phase(Instant.now()), ref.getAnnounceAt(),
                ref.getClientRef(), ref.getFirstSeenAt());
    }
}

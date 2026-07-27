// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Captures core's own log statements into {@link AppLogService} (ARCHITECTURE §13).
 *
 * <p>Attaching to Logback instead of calling an event API at each interesting site is the whole point: the ten
 * WARN/ERROR statements that already exist — a rejected plugin, a failed feed poll, the catch-all 500 — appear
 * in the admin viewer without being touched, and so does every one added later. Nothing to remember, nothing
 * to keep in sync.
 *
 * <p>Registered programmatically because the project ships no {@code logback.xml}: adding one purely for this
 * would override Spring Boot's console defaults (pattern, colours, dev level overrides) as a side effect.
 */
@Component
public class AppLogAppender extends AppenderBase<ILoggingEvent> {

    /** Only core's own loggers; framework noise stays on stdout where it belongs. */
    private static final String PACKAGE_PREFIX = "dev.mosaicast.";

    /** MDC keys the appender lifts into their own columns. */
    static final String MDC_PLUGIN_ID = "pluginId";
    static final String MDC_SUBSYSTEM = "subsystem";

    private final AppLogService logs;
    private final AppLogProperties properties;

    public AppLogAppender(AppLogService logs, AppLogProperties properties) {
        this.logs = logs;
        this.properties = properties;
    }

    @PostConstruct
    void attach() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            // Not Logback (e.g. a slimmed test runtime) — capture simply stays off rather than failing boot.
            return;
        }
        setContext(context);
        setName("app-log");
        start();
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(this);
    }

    @PreDestroy
    void detach() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this);
        }
        stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        // The writer thread must never feed itself: a database problem while storing an entry would otherwise
        // log, enqueue, fail, and log again.
        if (logs.isWriterThread()) {
            return;
        }
        String logger = event.getLoggerName();
        if (logger == null || !logger.startsWith(PACKAGE_PREFIX)) {
            return;
        }
        AppLogLevel level = levelOf(event.getLevel());
        if (level == null || !level.isAtLeast(properties.threshold())) {
            return;
        }
        Map<String, String> mdc = event.getMDCPropertyMap();
        logs.record(
                level,
                mdc.getOrDefault(MDC_SUBSYSTEM, subsystemOf(logger)),
                simpleNameOf(logger),
                mdc.get(MDC_PLUGIN_ID),
                event.getFormattedMessage(),
                stackTraceOf(event.getThrowableProxy()),
                contextOf(mdc));
    }

    /**
     * Remaining MDC entries (e.g. {@code feedId}) travel as structured context, so an entry keeps whatever the
     * call site knew without needing a column per concept.
     */
    private static JsonNode contextOf(Map<String, String> mdc) {
        ObjectNode context = null;
        for (Map.Entry<String, String> entry : mdc.entrySet()) {
            if (MDC_PLUGIN_ID.equals(entry.getKey()) || MDC_SUBSYSTEM.equals(entry.getKey())
                    || entry.getValue() == null) {
                continue;
            }
            if (context == null) {
                context = JsonNodeFactory.instance.objectNode();
            }
            context.put(entry.getKey(), entry.getValue());
        }
        return context;
    }

    /** TRACE has no counterpart: it is debugger noise, not operator signal. */
    private static AppLogLevel levelOf(Level level) {
        if (level == null) {
            return null;
        }
        return switch (level.toInt()) {
            case Level.ERROR_INT -> AppLogLevel.ERROR;
            case Level.WARN_INT -> AppLogLevel.WARN;
            case Level.INFO_INT -> AppLogLevel.INFO;
            case Level.DEBUG_INT -> AppLogLevel.DEBUG;
            default -> null;
        };
    }

    /**
     * The package segment below {@code dev.mosaicast.core} is the subsystem — {@code …core.plugin.X} →
     * {@code plugin}, {@code …core.feed.Y} → {@code feed}. Classes outside that layout fall back to
     * {@code core}, so an entry is never unattributed.
     */
    static String subsystemOf(String logger) {
        String rest = logger.startsWith(PACKAGE_PREFIX) ? logger.substring(PACKAGE_PREFIX.length()) : logger;
        if (rest.startsWith("core.")) {
            rest = rest.substring("core.".length());
        }
        int dot = rest.indexOf('.');
        return dot > 0 ? rest.substring(0, dot) : "core";
    }

    private static String simpleNameOf(String logger) {
        int dot = logger.lastIndexOf('.');
        return dot >= 0 ? logger.substring(dot + 1) : logger;
    }

    private static String stackTraceOf(IThrowableProxy throwable) {
        return throwable == null ? null : ThrowableProxyUtil.asString(throwable);
    }
}

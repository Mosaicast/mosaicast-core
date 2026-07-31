// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
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

    /**
     * Only core's own loggers; framework noise stays on stdout where it belongs.
     *
     * <p><strong>Derived, never written out.</strong> A literal {@code "dev.mosaicast."} would keep compiling
     * after a package rename and simply stop capturing anything — a silent hole in the one feature meant to
     * explain silence. Taking the first two segments of this class's own package means the prefix moves with
     * the code, whatever the organisation is called.
     */
    private static final String PACKAGE_PREFIX = corePrefix();

    /**
     * Loggers handed to plugins by the host, named {@code plugin.<pluginId>}.
     *
     * <p>Plugins are <em>not</em> expected to live under core's package — a third-party plugin can be called
     * anything — so capture cannot key off their package. The host names the logger instead, which also means
     * attribution survives a plugin logging from its own thread: the plugin id is in the logger name, not in
     * a thread-local MDC that a plugin-spawned thread would never carry.
     */
    static final String PLUGIN_LOGGER_PREFIX = "plugin.";

    /**
     * The logger name the host gives a plugin, {@code plugin.<pluginId>}. Lives here, next to the code that
     * parses it back out, so the naming convention has exactly one definition —
     * {@code PluginContextImpl.logger()} hands this to plugins and {@link #pluginIdOf} reads it.
     */
    public static String loggerNameFor(String pluginId) {
        return PLUGIN_LOGGER_PREFIX + pluginId;
    }

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
        if (logger == null) {
            return;
        }
        boolean fromPlugin = logger.startsWith(PLUGIN_LOGGER_PREFIX);
        if (!fromPlugin && !logger.startsWith(PACKAGE_PREFIX)) {
            return;
        }
        AppLogLevel level = levelOf(event.getLevel());
        if (level == null || !level.isAtLeast(properties.threshold())) {
            return;
        }
        Map<String, String> mdc = event.getMDCPropertyMap();
        String pluginId = fromPlugin ? pluginIdOf(logger) : mdc.get(MDC_PLUGIN_ID);
        logs.record(
                level,
                fromPlugin ? "plugin" : mdc.getOrDefault(MDC_SUBSYSTEM, subsystemOf(logger)),
                fromPlugin ? "backend" : simpleNameOf(logger),
                pluginId,
                event.getFormattedMessage(),
                stackTraceOf(event.getThrowableProxy()),
                contextOf(mdc));
    }

    /** The plugin id out of a {@code plugin.<pluginId>} logger name. */
    static String pluginIdOf(String logger) {
        String rest = logger.substring(PLUGIN_LOGGER_PREFIX.length());
        int dot = rest.indexOf('.');
        return dot > 0 ? rest.substring(0, dot) : rest;
    }

    /** The first two segments of this class's package, e.g. {@code dev.mosaicast.} */
    private static String corePrefix() {
        String pkg = AppLogAppender.class.getPackageName();
        String[] parts = pkg.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] + "." : pkg + ".";
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

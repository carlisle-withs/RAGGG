package com.rag.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class TraceLogger {

    public static final String TRACE_ID_KEY = "traceId";

    private final Logger logger;
    private final String traceId;

    private TraceLogger(Logger logger, String traceId) {
        this.logger = logger;
        this.traceId = traceId;
        MDC.put(TRACE_ID_KEY, traceId);
    }

    public static TraceLogger get(Class<?> clazz, String traceId) {
        return new TraceLogger(LoggerFactory.getLogger(clazz), traceId);
    }

    public static TraceLogger get(Class<?> clazz, String traceId, String documentId) {
        String combined = traceId + " | documentId=" + documentId;
        return new TraceLogger(LoggerFactory.getLogger(clazz), combined);
    }

    public void info(String message) {
        logger.info("[{}] {}", traceId, message);
    }

    public void info(String message, Object... args) {
        logger.info("[{}] {}", traceId, args.length > 0 ? String.format(message, args) : message);
    }

    public void debug(String message) {
        logger.debug("[{}] {}", traceId, message);
    }

    public void debug(String message, Object... args) {
        logger.debug("[{}] {}", traceId, String.format(message, args));
    }

    public void warn(String message) {
        logger.warn("[{}] {}", traceId, message);
    }

    public void error(String message) {
        logger.error("[{}] {}", traceId, message);
    }

    public void error(String message, Throwable ex) {
        logger.error("[{}] {} - {}", traceId, message, ex.getMessage(), ex);
    }

    public void step(String stepName) {
        info("=== STEP: %s ===", stepName);
    }

    public void stepComplete(String stepName) {
        logger.info("[{}] === STEP COMPLETE: {} ===", traceId, stepName);
    }

    public void stepComplete(String stepName, Object result) {
        logger.info("[{}] === STEP COMPLETE: {} | result={} ===", traceId, stepName, result);
    }

    public String getTraceId() {
        return traceId;
    }
}

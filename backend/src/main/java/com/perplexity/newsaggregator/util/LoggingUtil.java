package com.perplexity.newsaggregator.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized logging utility for consistent log formatting across the application.
 * Provides standardized logging methods for different types of operations.
 */
public class LoggingUtil {
    
    // Log prefixes for different operation types (no emojis for terminal compatibility)
    private static final String PREFIX_NEWS_FETCH = "[NEWS-FETCH]";
    private static final String PREFIX_AI_TAGGING = "[AI-TAGGING]";
    private static final String PREFIX_DB_OPERATION = "[DATABASE]";
    private static final String PREFIX_API_CALL = "[API-CALL]";
    private static final String PREFIX_RATE_LIMIT = "[RATE-LIMIT]";
    private static final String PREFIX_PERFORMANCE = "[PERFORMANCE]";
    private static final String PREFIX_USER_ACTION = "[USER-ACTION]";
    private static final String PREFIX_SYSTEM = "[SYSTEM]";
    private static final String PREFIX_ERROR = "[ERROR]";
    private static final String PREFIX_SUCCESS = "[SUCCESS]";
    
    /**
     * Log news fetching operations
     */
    public static void logNewsFetch(Logger logger, String operation, Object... params) {
        logger.info("{} {} {}", PREFIX_NEWS_FETCH, operation, formatParams(params));
    }
    
    /**
     * Log AI tagging operations
     */
    public static void logAITagging(Logger logger, String operation, Object... params) {
        logger.info("{} {} {}", PREFIX_AI_TAGGING, operation, formatParams(params));
    }
    
    /**
     * Log database operations
     */
    public static void logDatabaseOperation(Logger logger, String operation, Object... params) {
        logger.info("{} {} {}", PREFIX_DB_OPERATION, operation, formatParams(params));
    }
    
    /**
     * Log API calls with timing and response info
     */
    public static void logApiCall(Logger logger, String service, String endpoint, long durationMs, int statusCode) {
        logger.info("{} {} -> {} | {}ms | Status: {}", 
            PREFIX_API_CALL, service, endpoint, durationMs, statusCode);
    }
    
    /**
     * Log rate limiting information
     */
    public static void logRateLimit(Logger logger, String service, String action, Object... params) {
        logger.info("{} {} - {} {}", PREFIX_RATE_LIMIT, service, action, formatParams(params));
    }
    
    /**
     * Log performance metrics
     */
    public static void logPerformance(Logger logger, String operation, long durationMs, Object... params) {
        logger.info("{} {} completed in {}ms {}", 
            PREFIX_PERFORMANCE, operation, durationMs, formatParams(params));
    }
    
    /**
     * Log user actions
     */
    public static void logUserAction(Logger logger, String username, String action, Object... params) {
        logger.info("{} User: {} | Action: {} {}", 
            PREFIX_USER_ACTION, username != null ? username : "anonymous", action, formatParams(params));
    }
    
    /**
     * Log system events
     */
    public static void logSystem(Logger logger, String event, Object... params) {
        logger.info("{} {} {}", PREFIX_SYSTEM, event, formatParams(params));
    }
    
    /**
     * Log successful operations
     */
    public static void logSuccess(Logger logger, String operation, Object... params) {
        logger.info("{} {} {}", PREFIX_SUCCESS, operation, formatParams(params));
    }
    
    /**
     * Log errors with context
     */
    public static void logError(Logger logger, String operation, String error, Object... params) {
        logger.error("{} {} failed: {} {}", PREFIX_ERROR, operation, error, formatParams(params));
    }
    
    /**
     * Log warnings
     */
    public static void logWarning(Logger logger, String operation, String warning, Object... params) {
        logger.warn("{} {} warning: {} {}", PREFIX_SYSTEM, operation, warning, formatParams(params));
    }
    
    /**
     * Log API quota/limit information
     */
    public static void logApiQuota(Logger logger, String service, int used, int limit, String resetTime) {
        logger.info("{} {} quota: {}/{} (resets: {})", 
            PREFIX_RATE_LIMIT, service, used, limit, resetTime);
    }
    
    /**
     * Log batch processing operations
     */
    public static void logBatchOperation(Logger logger, String operation, int processed, int total, long durationMs) {
        logger.info("{} {} batch: {}/{} items processed in {}ms", 
            PREFIX_PERFORMANCE, operation, processed, total, durationMs);
    }
    
    /**
     * Log migration operations
     */
    public static void logMigration(Logger logger, String phase, int processed, int total) {
        logger.info("{} Migration {}: {}/{} articles processed", 
            PREFIX_SYSTEM, phase, processed, total);
    }
    
    /**
     * Format parameters for logging
     */
    private static String formatParams(Object... params) {
        if (params == null || params.length == 0) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder("| ");
        for (int i = 0; i < params.length; i += 2) {
            if (i + 1 < params.length) {
                sb.append(params[i]).append(": ").append(params[i + 1]);
                if (i + 2 < params.length) {
                    sb.append(" | ");
                }
            }
        }
        return sb.toString();
    }
    
    /**
     * Create a performance timer for measuring operation duration
     */
    public static PerformanceTimer startTimer() {
        return new PerformanceTimer();
    }
    
    /**
     * Simple performance timer utility
     */
    public static class PerformanceTimer {
        private final long startTime;
        
        private PerformanceTimer() {
            this.startTime = System.currentTimeMillis();
        }
        
        public long getDurationMs() {
            return System.currentTimeMillis() - startTime;
        }
        
        public void logCompletion(Logger logger, String operation, Object... params) {
            LoggingUtil.logPerformance(logger, operation, getDurationMs(), params);
        }
    }
}
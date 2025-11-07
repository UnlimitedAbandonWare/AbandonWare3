package com.example.lms.infra.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a method as a single‑flight guarded section.  Concurrent calls with
 * the same key will share the result of the first execution and skip
 * subsequent invocations until the first completes.  The key must be
 * supplied via a SpEL expression referencing method arguments.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SingleFlight {
    /**
     * SpEL expression used to compute the deduplication key from method
     * arguments.  For example, {@code "#a0.id"} references the first
     * argument's {@code id} property.  The expression is evaluated with
     * variables {@code a0}, {@code a1}, etc. bound to the respective
     * arguments.
     */
    String keyExpr();
    /**
     * Maximum number of seconds to hold the distributed lock.  After
     * this TTL the lock will automatically expire in Redis.
     */
    int ttlSeconds() default 30;
}
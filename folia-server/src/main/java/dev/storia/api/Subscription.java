package dev.storia.api;

/** A listener or subscription that can be cancelled. */
@FunctionalInterface
public interface Subscription {

    /** Stops it. Safe to call more than once. */
    void cancel();
}

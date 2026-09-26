package com.railops.producer;

/** Outcome of a confirmed batch: messages sent, of which {@code duplicates} were re-sends. */
public record ProduceResult(int sent, int duplicates) {
}

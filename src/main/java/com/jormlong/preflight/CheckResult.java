package com.jormlong.preflight;

/**
 * One preflight row. {@code fix} is a copyable shell command shown only when
 * the check failed; may be null when there is no one-liner to suggest.
 */
public record CheckResult(String label, boolean ok, boolean essential, String message, String fix) {
}

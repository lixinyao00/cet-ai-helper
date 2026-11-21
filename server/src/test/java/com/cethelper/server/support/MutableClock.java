package com.cethelper.server.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 可手动拨动的时钟，用于验证会话凭证过期而无需真实等待。 */
public final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneOffset.UTC);
    }

    public MutableClock(Instant start, ZoneId zone) {
        this.instant = start;
        this.zone = zone;
    }

    public MutableClock advance(Duration duration) {
        this.instant = this.instant.plus(duration);
        return this;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}

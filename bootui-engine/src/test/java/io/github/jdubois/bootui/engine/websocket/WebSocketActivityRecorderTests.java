package io.github.jdubois.bootui.engine.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder.CapturedFrame;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder.Direction;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder.FrameType;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder.SessionCounters;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebSocketActivityRecorderTests {

    private static WebSocketActivityRecorder recorder(WebSocketSettings settings) {
        return new WebSocketActivityRecorder(settings);
    }

    private static WebSocketSettings settings(boolean enabled, boolean capturing, int maxEntries, int maxSessions) {
        return new WebSocketSettings(enabled, capturing, 200, 200, 500, maxEntries, maxSessions);
    }

    @Test
    void hashesTheRawSessionIdBeforeItEntersTheBuffer() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());

        recorder.recordFrame(
                "stomp:/ws", "raw-session-1", Direction.INBOUND, FrameType.TEXT, "/app/chat", 12L, null, true, null);

        CapturedFrame frame = recorder.recent().get(0);
        assertThat(frame.sessionId()).isNotEqualTo("raw-session-1");
        assertThat(frame.sessionId()).isEqualTo(WebSocketSessionIds.opaque("raw-session-1"));
        assertThat(frame.sessionId()).hasSize(32);
    }

    @Test
    void retainsOnlyFrameMetadataAndNeverAPayload() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());

        recorder.recordFrame(
                "stomp:/ws", "s1", Direction.INBOUND, FrameType.TEXT, "/topic/orders", 4096L, 7L, true, null);

        CapturedFrame frame = recorder.recent().get(0);
        assertThat(frame.payloadBytes()).isEqualTo(4096L);
        assertThat(frame.durationMillis()).isEqualTo(7L);
        assertThat(frame.destination()).isEqualTo("/topic/orders");
        assertThat(frame.frameType()).isEqualTo(FrameType.TEXT);
        // CapturedFrame carries no payload-bearing component at all.
        assertThat(CapturedFrame.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "id",
                        "timestamp",
                        "endpointId",
                        "sessionId",
                        "direction",
                        "frameType",
                        "destination",
                        "payloadBytes",
                        "durationMillis",
                        "success",
                        "errorCategory");
    }

    @Test
    void evictsOldestFramesOnceTheBufferIsFullAndCountsTheEviction() {
        WebSocketActivityRecorder recorder = recorder(settings(true, true, 2, 100));

        for (int i = 1; i <= 4; i++) {
            recorder.recordFrame("e", "s", Direction.INBOUND, FrameType.TEXT, "/d" + i, 1L, null, true, null);
        }

        List<CapturedFrame> recent = recorder.recent();
        assertThat(recent).hasSize(2);
        assertThat(recent).extracting(CapturedFrame::destination).containsExactly("/d4", "/d3");
        assertThat(recorder.evicted()).isEqualTo(2);
        assertThat(recorder.totalCaptured()).isEqualTo(4);
    }

    @Test
    void boundsThePerSessionCounterMapIndependentlyOfTheFrameBuffer() {
        WebSocketActivityRecorder recorder = recorder(settings(true, true, 5_000, 2));

        recorder.recordFrame("e", "s1", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        recorder.recordFrame("e", "s2", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        recorder.recordFrame("e", "s3", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);

        assertThat(recorder.counters("s1")).isEqualTo(SessionCounters.empty());
        assertThat(recorder.counters("s3").messagesIn()).isEqualTo(1);
    }

    @Test
    void evictsTheLeastRecentlyActiveSessionRatherThanTheFirstOneSeen() {
        WebSocketActivityRecorder recorder = recorder(settings(true, true, 5_000, 2));

        recorder.recordFrame("e", "long-lived", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        recorder.recordFrame("e", "idle", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        // The oldest session is still the busiest one, so it must survive the next admission.
        recorder.recordFrame("e", "long-lived", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        recorder.recordFrame("e", "newcomer", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);

        assertThat(recorder.counters("long-lived").messagesIn())
                .as("a chatty long-lived connection is exactly the one an operator is watching")
                .isEqualTo(2);
        assertThat(recorder.counters("idle")).isEqualTo(SessionCounters.empty());
        assertThat(recorder.counters("newcomer").messagesIn()).isEqualTo(1);
    }

    @Test
    void redactsARawSessionIdThatAFrameworkEmbeddedInTheDestination() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());

        recorder.recordFrame(
                "e",
                "abc123",
                Direction.OUTBOUND,
                FrameType.TEXT,
                "/queue/notifications-userabc123",
                1L,
                null,
                true,
                null);

        assertThat(recorder.recent().get(0).destination())
                .as("no adapter may smuggle a live session id into the buffer through the destination")
                .isEqualTo("/queue/notifications-user{session}");
    }

    @Test
    void accumulatesDirectionalCountersAndBytes() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());

        recorder.recordFrame("e", "s1", Direction.INBOUND, FrameType.TEXT, null, 100L, null, true, null);
        recorder.recordFrame("e", "s1", Direction.OUTBOUND, FrameType.TEXT, null, 30L, null, true, null);
        recorder.recordFrame("e", "s1", Direction.OUTBOUND, FrameType.BINARY, null, 20L, null, false, "boom");

        SessionCounters counters = recorder.counters("s1");
        assertThat(counters.messagesIn()).isEqualTo(1);
        assertThat(counters.messagesOut()).isEqualTo(2);
        assertThat(counters.bytesIn()).isEqualTo(100);
        assertThat(counters.bytesOut()).isEqualTo(50);
        assertThat(counters.lastActivityAt()).isPositive();
        assertThat(recorder.inboundFrames()).isEqualTo(1);
        assertThat(recorder.outboundFrames()).isEqualTo(2);
        assertThat(recorder.failedFrames()).isEqualTo(1);
    }

    @Test
    void recordsNothingWhenCaptureIsDisabledOrPaused() {
        WebSocketActivityRecorder disabled = recorder(settings(false, true, 100, 100));
        disabled.recordFrame("e", "s", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        assertThat(disabled.recent()).isEmpty();
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.isCapturing()).isFalse();

        WebSocketActivityRecorder paused = recorder(WebSocketSettings.defaults());
        paused.setCapturing(false);
        paused.recordFrame("e", "s", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        assertThat(paused.recent()).isEmpty();

        paused.setCapturing(true);
        paused.recordFrame("e", "s", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        assertThat(paused.recent()).hasSize(1);
    }

    @Test
    void disabledRecorderCannotBeResumedIntoCapturing() {
        WebSocketActivityRecorder disabled = recorder(settings(false, false, 100, 100));

        disabled.setCapturing(true);

        assertThat(disabled.isCapturing()).isFalse();
    }

    @Test
    void truncatesAnOverlongDestinationAndErrorCategory() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());

        recorder.recordFrame(
                "e", "s", Direction.INBOUND, FrameType.TEXT, "/".repeat(1_000), 1L, null, false, "x".repeat(1_000));

        CapturedFrame frame = recorder.recent().get(0);
        assertThat(frame.destination()).hasSize(256);
        assertThat(frame.errorCategory()).hasSize(120);
    }

    @Test
    void dropsTheErrorCategoryOnASuccessfulFrame() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());

        recorder.recordFrame("e", "s", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, "ignored");

        assertThat(recorder.recent().get(0).errorCategory()).isNull();
    }

    @Test
    void clearDropsFramesAndCountersWithoutTouchingLifetimeTotals() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());
        recorder.recordFrame("e", "s1", Direction.INBOUND, FrameType.TEXT, null, 10L, null, true, null);

        recorder.clear();

        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.counters("s1")).isEqualTo(SessionCounters.empty());
        assertThat(recorder.totalCaptured()).isEqualTo(1);
    }

    @Test
    void notifiesSubscribersOnCaptureClearAndStateChangeAndSurvivesAFailingListener() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());
        AtomicInteger notifications = new AtomicInteger();
        recorder.subscribe(() -> {
            throw new IllegalStateException("subscriber blew up");
        });
        Runnable unsubscribe = recorder.subscribe(notifications::incrementAndGet);

        recorder.recordFrame("e", "s", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        recorder.setCapturing(false);
        recorder.clear();
        assertThat(notifications.get()).isEqualTo(3);

        unsubscribe.run();
        recorder.setCapturing(true);
        assertThat(notifications.get()).isEqualTo(3);
    }

    @Test
    void idleSuspensionStopsCaptureAndReleasesRetainedData() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());
        recorder.recordFrame("e", "s", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);

        recorder.suspendForIdle();

        assertThat(recorder.isCapturing()).isFalse();
        assertThat(recorder.recent()).isEmpty();

        recorder.resumeFromIdle();
        recorder.recordFrame("e", "s", Direction.INBOUND, FrameType.TEXT, null, 1L, null, true, null);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void ignoresAFrameWithNoDirectionOrType() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());

        recorder.recordFrame("e", "s", null, FrameType.TEXT, null, 1L, null, true, null);
        recorder.recordFrame("e", "s", Direction.INBOUND, null, null, 1L, null, true, null);

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void clampsNegativeSizesAndDurationsWhileKeepingAnAbsentSizeAbsent() {
        WebSocketActivityRecorder recorder = recorder(WebSocketSettings.defaults());

        recorder.recordFrame("e", "s", Direction.INBOUND, FrameType.PING, null, -5L, -9L, true, null);
        recorder.recordFrame("e", "s", Direction.INBOUND, FrameType.SUBSCRIBE, "/topic/x", null, null, true, null);

        List<CapturedFrame> recent = recorder.recent();
        assertThat(recent.get(1).payloadBytes()).isZero();
        assertThat(recent.get(1).durationMillis()).isZero();
        assertThat(recent.get(0).payloadBytes()).isNull();
    }
}

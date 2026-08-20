package io.github.jdubois.bootui.sample.websocket;

/** Payload exchanged over the sample STOMP chat destination. BootUI never reads this content. */
public record SampleChatMessage(String sender, String text) {}

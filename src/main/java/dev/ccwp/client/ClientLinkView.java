package dev.ccwp.client;

import dev.ccwp.net.LinkViewPayload;

/** Latest link view received from the server. Plain data holder, safe to class-load on a dedicated server. */
public final class ClientLinkView {
    private static volatile LinkViewPayload current = LinkViewPayload.EMPTY;

    private ClientLinkView() {
    }

    public static void set(LinkViewPayload payload) {
        current = payload;
    }

    public static LinkViewPayload get() {
        return current;
    }
}

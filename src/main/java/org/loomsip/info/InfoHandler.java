package org.loomsip.info;

import java.util.concurrent.CompletionStage;

/**
 * Application callback for one registered RFC 6086 INFO package.
 *
 * <pre>{@code
 * validated INFO --> Info Dispatcher --> InfoHandler
 *                                        |
 *                                        v
 *                           CompletionStage<InfoResponse>
 *                                        |
 *                                        v
 *                                  NIST response path
 * }</pre>
 */
@FunctionalInterface
public interface InfoHandler {

    /**
     * Handles one already validated INFO package request.
     *
     * @param request immutable package, headers, and opaque body
     * @return asynchronous final response chosen by the application
     */
    CompletionStage<InfoResponse> onInfo(InfoRequest request);
}

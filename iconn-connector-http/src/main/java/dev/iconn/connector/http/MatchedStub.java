package dev.iconn.connector.http;

import java.util.Map;

/**
 * Encapsulates a matched HTTP stub along with its extracted path parameters.
 */
record MatchedStub(HttpStub stub, Map<String, String> pathParams) {}

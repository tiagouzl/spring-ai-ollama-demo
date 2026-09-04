package com.example.ai.api;

/**
 * Typed output for the {@code /ai/chat/structured} endpoint: the model is asked
 * to produce a JSON object matching this shape (via {@code ChatClient.entity}),
 * so callers get structured data instead of raw text.
 *
 * @param topic     the main subject of the message
 * @param sentiment sentiment detected (positive/negative/neutral)
 * @param rating    a 0-10 rating of the sentiment intensity
 */
public record TopicSentiment(String topic, String sentiment, int rating) {}
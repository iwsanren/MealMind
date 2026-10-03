package com.mealmind.prompt;

/**
 * The result of rendering a prompt: the final text sent to the LLM, plus
 * which version actually produced it.
 */
public record RenderedPrompt(String scenario, String version, String text) {
}

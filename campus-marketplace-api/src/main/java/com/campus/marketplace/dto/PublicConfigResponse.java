package com.campus.marketplace.dto;

/**
 * Feature switches the frontend needs before login. Public, so nothing secret goes here.
 *
 * @param assistantEnabled        show the "Ask AI" button
 * @param assistantVerdictEnabled show "Is it a good buy?" inside it
 * @param assistantWebSearch      answers are web-grounded (otherwise they come from model knowledge)
 */
public record PublicConfigResponse(boolean assistantEnabled, boolean assistantVerdictEnabled, boolean assistantWebSearch) {
}

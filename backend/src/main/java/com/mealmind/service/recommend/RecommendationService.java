package com.mealmind.service.recommend;

import com.mealmind.config.AgentProperties;
import com.mealmind.dto.meal.MealResponse;
import com.mealmind.dto.recommend.RecommendResponse;
import com.mealmind.dto.recommend.RecommendResponse.Source;
import com.mealmind.enums.SessionPhase;
import com.mealmind.exception.MealException;
import com.mealmind.model.MealItem;
import com.mealmind.model.MealRankRequest;
import com.mealmind.model.MealSearchRequest;
import com.mealmind.model.RiskGuardResult;
import com.mealmind.model.SessionState;
import com.mealmind.model.SlotBundle;
import com.mealmind.service.clarify.ClarifyRuleService;
import com.mealmind.service.meal.MealRankService;
import com.mealmind.service.meal.MealSearchService;
import com.mealmind.service.meal.MealService;
import com.mealmind.service.recommend.AgentClient.AgentCallException;
import com.mealmind.service.recommend.AgentClient.AgentOutcome;
import com.mealmind.service.recommend.AgentClient.AgentRequest;
import com.mealmind.service.recommend.HardConstraintExtractor.HardConstraints;
import com.mealmind.service.risk.RiskGuardService;
import com.mealmind.service.session.SessionStateService;
import com.mealmind.service.slot.SlotMergeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One chat turn: safety gate, slot bookkeeping, then the agent (ai-service) when it is switched on, otherwise (or when
 * it fails in any way) the rule-based recommender. Chat must never break because the agent or the LLM did.
 *
 * Not transactional on purpose: the agent call can take many seconds and must not hold a database transaction open.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    static final int MAX_MESSAGE_CHARS = 1000;
    private static final int RULES_RESULT_LIMIT = 3;

    private final SessionStateService sessionStateService;
    private final RiskGuardService riskGuardService;
    private final RuleSlotExtractor slotExtractor;
    private final SlotMergeService slotMergeService;
    private final HardConstraintExtractor constraintExtractor;
    private final ClarifyRuleService clarifyRuleService;
    private final MealSearchService mealSearchService;
    private final MealRankService mealRankService;
    private final MealService mealService;
    private final AgentClient agentClient;
    private final AgentProperties agentProperties;

    public RecommendationService(SessionStateService sessionStateService, RiskGuardService riskGuardService,
                                 RuleSlotExtractor slotExtractor, SlotMergeService slotMergeService,
                                 HardConstraintExtractor constraintExtractor, ClarifyRuleService clarifyRuleService,
                                 MealSearchService mealSearchService, MealRankService mealRankService,
                                 MealService mealService, AgentClient agentClient, AgentProperties agentProperties) {
        this.sessionStateService = sessionStateService;
        this.riskGuardService = riskGuardService;
        this.slotExtractor = slotExtractor;
        this.slotMergeService = slotMergeService;
        this.constraintExtractor = constraintExtractor;
        this.clarifyRuleService = clarifyRuleService;
        this.mealSearchService = mealSearchService;
        this.mealRankService = mealRankService;
        this.mealService = mealService;
        this.agentClient = agentClient;
        this.agentProperties = agentProperties;
    }

    /** @param forceRules skip the agent for this request (it can only turn the agent off, never on) */
    public RecommendResponse recommend(String sessionId, Long userId, String message, boolean forceRules) {
        if (message == null || message.isBlank()) {
            throw new MealException("message must not be blank");
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            throw new MealException("message must be at most " + MAX_MESSAGE_CHARS + " characters");
        }
        SessionState state = sessionStateService.find(sessionId, userId);

        RiskGuardResult risk = riskGuardService.check(message);
        if (risk.blocked()) {
            return new RecommendResponse(sessionId, Source.RISK_GUARD, null, risk.conservativeMessage(), List.of(), null);
        }

        SlotBundle slots = slotMergeService.merge(state.slots(), slotExtractor.extract(message));
        HardConstraints constraints = constraintExtractor.extract(message);

        String fallbackReason;
        if (forceRules) {
            fallbackReason = "DISABLED_BY_REQUEST";
        } else if (!agentProperties.enabled()) {
            fallbackReason = "AGENT_DISABLED";
        } else {
            AgentAttempt attempt = tryAgent(state, message, slots, constraints);
            if (attempt.response != null) {
                save(state, slots, SessionPhase.RECOMMEND, attempt.shownIds);
                return attempt.response;
            }
            fallbackReason = attempt.failure;
            log.warn("agent unavailable for session {} ({}); using rules", sessionId, fallbackReason);
        }
        return rules(state, slots, constraints, fallbackReason);
    }

    // ---- agent path ----

    private record AgentAttempt(RecommendResponse response, List<Long> shownIds, String failure) {
        static AgentAttempt failed(String reason) {
            return new AgentAttempt(null, List.of(), reason);
        }
    }

    private AgentAttempt tryAgent(SessionState state, String message, SlotBundle slots, HardConstraints constraints) {
        AgentOutcome outcome;
        try {
            outcome = agentClient.recommend(new AgentRequest(message, state.sessionId(), state.userId(),
                    state.sourceMode().name(), slotMap(slots), state.lastRecommendations()));
        } catch (AgentCallException e) {
            return AgentAttempt.failed(e.reason());
        }
        // UNVERIFIED, PARSE_FAILED, MAX_ROUNDS and ERROR carry no answer on purpose: the agent's draft is never shown.
        if (!outcome.verifiedAnswer()) {
            return AgentAttempt.failed("AGENT_" + outcome.status());
        }
        var recommendation = outcome.recommendation();
        if (recommendation.mealId() == null) {
            return new AgentAttempt(new RecommendResponse(state.sessionId(), Source.AGENT, null,
                    recommendation.reason(), List.of(), outcome.traceId()), List.of(), null);
        }
        // Load the meal ourselves: the id must be one this user may see, and the facts shown come from the database.
        List<MealItem> found = mealService.findAccessibleMeals(List.of(recommendation.mealId()), state.userId());
        if (found.isEmpty()) {
            return AgentAttempt.failed("AGENT_UNKNOWN_MEAL");
        }
        MealItem meal = found.get(0);
        // Independent second check with the rule-based reading of the user's own words (fail-closed on unknown facts).
        if (constraintExtractor.violates(meal, constraints)) {
            return AgentAttempt.failed("AGENT_FAILED_BACKEND_CHECK");
        }
        return new AgentAttempt(new RecommendResponse(state.sessionId(), Source.AGENT, null, recommendation.reason(),
                List.of(MealResponse.from(meal)), outcome.traceId()), List.of(meal.id()), null);
    }

    private static Map<String, List<String>> slotMap(SlotBundle slots) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("mealTime", slots.mealTime());
        map.put("mood", slots.mood());
        map.put("scene", slots.scene());
        map.put("healthGoal", slots.healthGoal());
        map.put("cuisine", slots.cuisine());
        map.put("taste", slots.taste());
        map.put("convenience", slots.convenience());
        map.values().removeIf(List::isEmpty);
        return map;
    }

    // ---- rules path (the original behaviour, plus the two hard constraints) ----

    private RecommendResponse rules(SessionState state, SlotBundle slots, HardConstraints constraints, String reason) {
        if (constraints.unresolvedAllergy()) {
            // The allergy cannot be expressed in the closed allergen list, so no meal can be proven safe.
            save(state, slots, SessionPhase.CLARIFY, List.of());
            return rulesResponse(state, reason, "You mentioned an allergy I can't filter for. I can only check for milk, "
                    + "egg, fish, shellfish, tree nuts, peanuts, wheat, soy and sesame, so I won't suggest a meal "
                    + "until you tell me which of those applies (or that none does).", List.of());
        }
        List<String> missing = clarifyRuleService.missingSlots(slots);
        if (slots.mealTime().isEmpty()) {
            save(state, slots, SessionPhase.CLARIFY, state.lastRecommendations());
            return rulesResponse(state, reason, clarifyRuleService.fallbackQuestion(missing), List.of());
        }
        List<MealItem> candidates = mealSearchService.search(new MealSearchRequest(state.sourceMode(), state.userId(),
                slots, state.lastRecommendations(), constraints.maxPrice(), List.copyOf(constraints.excludeAllergens())));
        List<MealItem> ranked = mealRankService.rank(new MealRankRequest(candidates, slots, state.lastRecommendations()))
                .stream().limit(RULES_RESULT_LIMIT).toList();
        List<Long> ids = ranked.stream().map(MealItem::id).toList();
        save(state, slots, SessionPhase.RECOMMEND, ids);
        if (ranked.isEmpty()) {
            String why = constraints.any() ? "Nothing in the library fits your budget and allergy limits together"
                    : "Nothing in the library matched that";
            return rulesResponse(state, reason, why + ". Try loosening a preference"
                    + (constraints.any() ? " (allergy limits are never loosened automatically)." : "."), List.of());
        }
        return rulesResponse(state, reason, "Here's what matched (simple rule-based matching):",
                ranked.stream().map(MealResponse::from).toList());
    }

    private RecommendResponse rulesResponse(SessionState state, String reason, String text, List<MealResponse> meals) {
        return new RecommendResponse(state.sessionId(), Source.RULES, reason, text, meals, null);
    }

    private void save(SessionState state, SlotBundle slots, SessionPhase phase, List<Long> shownIds) {
        sessionStateService.save(new SessionState(state.sessionId(), state.userId(), phase, state.sourceMode(), slots,
                shownIds));
    }
}

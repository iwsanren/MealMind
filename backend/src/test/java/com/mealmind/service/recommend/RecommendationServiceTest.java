package com.mealmind.service.recommend;

import com.mealmind.config.AgentProperties;
import com.mealmind.dto.recommend.RecommendResponse;
import com.mealmind.dto.recommend.RecommendResponse.Source;
import com.mealmind.enums.SessionPhase;
import com.mealmind.enums.SourceMode;
import com.mealmind.exception.MealException;
import com.mealmind.model.MealFacts;
import com.mealmind.model.MealItem;
import com.mealmind.model.MealSearchRequest;
import com.mealmind.model.SessionState;
import com.mealmind.model.SlotBundle;
import com.mealmind.service.clarify.ClarifyRuleService;
import com.mealmind.service.meal.MealRankService;
import com.mealmind.service.meal.MealSearchService;
import com.mealmind.service.meal.MealService;
import com.mealmind.service.recommend.AgentClient.AgentCallException;
import com.mealmind.service.recommend.AgentClient.AgentOutcome;
import com.mealmind.service.recommend.AgentClient.AgentRecommendation;
import com.mealmind.service.recommend.AgentClient.AgentRequest;
import com.mealmind.service.risk.RiskGuardService;
import com.mealmind.service.session.SessionStateService;
import com.mealmind.service.slot.SlotMergeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The orchestration of one chat turn. Collaborators that talk to the network or the database are mocked. */
class RecommendationServiceTest {

    private static final String SESSION = "sess_1";
    private static final long USER = 7L;

    private SessionStateService sessions;
    private RuleSlotExtractor slotExtractor;
    private MealSearchService search;
    private MealRankService rank;
    private MealService meals;
    private AgentClient agent;

    @BeforeEach
    void setUp() {
        sessions = mock(SessionStateService.class);
        slotExtractor = mock(RuleSlotExtractor.class);
        search = mock(MealSearchService.class);
        rank = mock(MealRankService.class);
        meals = mock(MealService.class);
        agent = mock(AgentClient.class);
        when(sessions.find(SESSION, USER)).thenReturn(
                new SessionState(SESSION, USER, SessionPhase.START, SourceMode.PUBLIC, SlotBundle.empty(), List.of(3L)));
        when(slotExtractor.extract(any())).thenReturn(dinner());
    }

    private RecommendationService service(boolean agentEnabled) {
        return new RecommendationService(sessions, new RiskGuardService(), slotExtractor, new SlotMergeService(),
                new HardConstraintExtractor(), new ClarifyRuleService(), search, rank, meals, agent,
                new AgentProperties(agentEnabled, "http://unused", 1000, 1000));
    }

    private static SlotBundle dinner() {
        return new SlotBundle(List.of("Dinner"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static MealItem meal(long id, String price, List<String> allergens) {
        return new MealItem(id, SourceMode.PUBLIC, null, "Meal " + id, dinner(),
                new MealFacts(new BigDecimal(price), new BigDecimal("30"), 500, allergens), 0.5);
    }

    private static AgentOutcome success(Long mealId) {
        return new AgentOutcome("run_1", "SUCCESS", new AgentRecommendation(mealId, mealId == null ? null : "Meal " + mealId,
                mealId == null ? "Nothing fits." : "Good fit.", mealId == null ? "over budget" : null), null);
    }

    private SessionState savedState() {
        ArgumentCaptor<SessionState> captor = ArgumentCaptor.forClass(SessionState.class);
        verify(sessions).save(captor.capture());
        return captor.getValue();
    }

    // ---- agent path ----

    @Test
    void aVerifiedAgentAnswerIsReturnedWithItsTraceAndRemembered() {
        when(agent.recommend(any())).thenReturn(success(5L));
        when(meals.findAccessibleMeals(List.of(5L), USER)).thenReturn(List.of(meal(5, "12.50", List.of("milk"))));

        RecommendResponse response = service(true).recommend(SESSION, USER, "dinner under $15", false);

        assertThat(response.source()).isEqualTo(Source.AGENT);
        assertThat(response.fallbackReason()).isNull();
        assertThat(response.text()).isEqualTo("Good fit.");
        assertThat(response.meals()).extracting("id").containsExactly(5L);
        assertThat(response.traceId()).isEqualTo("run_1");
        SessionState saved = savedState();
        assertThat(saved.phase()).isEqualTo(SessionPhase.RECOMMEND);
        assertThat(saved.lastRecommendations()).containsExactly(5L);
        assertThat(saved.slots().mealTime()).containsExactly("Dinner");
    }

    @Test
    void theAgentGetsTheUsersWordsTheSessionContextAndTheMealsToSkip() {
        when(agent.recommend(any())).thenReturn(success(null));

        service(true).recommend(SESSION, USER, "dinner under $15", false);

        ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agent).recommend(captor.capture());
        AgentRequest sent = captor.getValue();
        assertThat(sent.userMessage()).isEqualTo("dinner under $15");
        assertThat(sent.sessionId()).isEqualTo(SESSION);
        assertThat(sent.userId()).isEqualTo(USER);
        assertThat(sent.sourceMode()).isEqualTo("PUBLIC");
        assertThat(sent.excludeMealIds()).containsExactly(3L);
        assertThat(sent.slots()).containsOnlyKeys("mealTime");          // empty dimensions are not sent
    }

    @Test
    void aNoMatchAnswerIsALegitimateAgentAnswerNotAFallback() {
        when(agent.recommend(any())).thenReturn(success(null));

        RecommendResponse response = service(true).recommend(SESSION, USER, "dinner under $1", false);

        assertThat(response.source()).isEqualTo(Source.AGENT);
        assertThat(response.meals()).isEmpty();
        assertThat(response.text()).isEqualTo("Nothing fits.");
        verify(search, never()).search(any());
    }

    @Test
    void everyAgentFailureFallsBackToTheRulesWithTheReason() {
        when(search.search(any())).thenReturn(List.of(meal(8, "9", List.of())));
        when(rank.rank(any())).thenReturn(List.of(meal(8, "9", List.of())));
        List<AgentOutcome> notShown = List.of(
                new AgentOutcome("r", "UNVERIFIED", null, null), new AgentOutcome("r", "PARSE_FAILED", null, null),
                new AgentOutcome("r", "MAX_ROUNDS", null, null), new AgentOutcome("r", "ERROR", null, "LLM call failed"),
                new AgentOutcome("r", "SUCCESS", null, null));   // a success without an answer is not an answer

        for (AgentOutcome outcome : notShown) {
            doReturn(outcome).when(agent).recommend(any());
            RecommendResponse response = service(true).recommend(SESSION, USER, "dinner", false);
            assertThat(response.source()).as(outcome.status()).isEqualTo(Source.RULES);
            assertThat(response.fallbackReason()).isEqualTo("AGENT_" + outcome.status());
            assertThat(response.meals()).extracting("id").containsExactly(8L);
            assertThat(response.traceId()).isNull();
        }
    }

    @Test
    void transportFailuresFallBackToo() {
        when(search.search(any())).thenReturn(List.of());
        when(rank.rank(any())).thenReturn(List.of());
        for (String reason : List.of("AGENT_TIMEOUT", "AGENT_UNREACHABLE", "AGENT_HTTP_503", "AGENT_BAD_RESPONSE")) {
            doThrow(new AgentCallException(reason, null)).when(agent).recommend(any());   // doThrow: re-stubbing must not call the old stub
            RecommendResponse response = service(true).recommend(SESSION, USER, "dinner", false);
            assertThat(response.source()).isEqualTo(Source.RULES);
            assertThat(response.fallbackReason()).isEqualTo(reason);
        }
    }

    @Test
    void aMealTheUserMayNotSeeIsNeverShown() {
        when(agent.recommend(any())).thenReturn(success(99L));
        when(meals.findAccessibleMeals(List.of(99L), USER)).thenReturn(List.of());   // someone else's personal meal
        when(search.search(any())).thenReturn(List.of());
        when(rank.rank(any())).thenReturn(List.of());

        RecommendResponse response = service(true).recommend(SESSION, USER, "dinner", false);

        assertThat(response.source()).isEqualTo(Source.RULES);
        assertThat(response.fallbackReason()).isEqualTo("AGENT_UNKNOWN_MEAL");
    }

    @Test
    void theBackendChecksTheAgentsMealAgainstTheUsersWordsAndRejectsViolations() {
        when(agent.recommend(any())).thenReturn(success(5L));
        // the agent picked a $14 dish with shellfish for "under $10, allergic to shrimp"
        when(meals.findAccessibleMeals(List.of(5L), USER)).thenReturn(List.of(meal(5, "14", List.of("shellfish"))));
        when(search.search(any())).thenReturn(List.of());
        when(rank.rank(any())).thenReturn(List.of());

        RecommendResponse response = service(true).recommend(SESSION, USER, "dinner under $10, allergic to shrimp", false);

        assertThat(response.source()).isEqualTo(Source.RULES);
        assertThat(response.fallbackReason()).isEqualTo("AGENT_FAILED_BACKEND_CHECK");
        assertThat(response.meals()).isEmpty();
    }

    // ---- flag and per-request switch ----

    @Test
    void withTheFlagOffTheAgentIsNeverCalled() {
        when(search.search(any())).thenReturn(List.of(meal(8, "9", List.of())));
        when(rank.rank(any())).thenReturn(List.of(meal(8, "9", List.of())));

        RecommendResponse response = service(false).recommend(SESSION, USER, "dinner", false);

        assertThat(response.source()).isEqualTo(Source.RULES);
        assertThat(response.fallbackReason()).isEqualTo("AGENT_DISABLED");
        verify(agent, never()).recommend(any());
    }

    @Test
    void aRequestCanSwitchTheAgentOffButTheServerFlagStaysTheOnlyWayToSwitchItOn() {
        when(search.search(any())).thenReturn(List.of());
        when(rank.rank(any())).thenReturn(List.of());

        RecommendResponse forced = service(true).recommend(SESSION, USER, "dinner", true);

        assertThat(forced.fallbackReason()).isEqualTo("DISABLED_BY_REQUEST");
        verify(agent, never()).recommend(any());
    }

    // ---- safety gate ----

    @Test
    void aMedicalMessageGetsTheFixedReplyAndNeverReachesTheAgentOrTheSession() {
        RecommendResponse response = service(true).recommend(SESSION, USER, "what can I eat to cure my diabetes", false);

        assertThat(response.source()).isEqualTo(Source.RISK_GUARD);
        assertThat(response.text()).contains("doctor");
        assertThat(response.meals()).isEmpty();
        verify(agent, never()).recommend(any());
        verify(sessions, never()).save(any());
    }

    // ---- rules path ----

    @Test
    void theRulesPathAppliesTheBudgetAndAllergyFromTheMessageAsHardFilters() {
        when(search.search(any())).thenReturn(List.of());
        when(rank.rank(any())).thenReturn(List.of());

        RecommendResponse response = service(false).recommend(SESSION, USER, "dinner under $12, allergic to shrimp", false);

        ArgumentCaptor<MealSearchRequest> captor = ArgumentCaptor.forClass(MealSearchRequest.class);
        verify(search).search(captor.capture());
        assertThat(captor.getValue().maxPrice()).isEqualByComparingTo("12");
        assertThat(captor.getValue().excludeAllergens()).containsExactly("shellfish");
        assertThat(captor.getValue().excludeMealIds()).containsExactly(3L);
        assertThat(response.meals()).isEmpty();
        assertThat(response.text()).contains("never loosened");
    }

    @Test
    void theRulesPathAsksForTheMealTimeWhenItIsMissing() {
        when(slotExtractor.extract(any())).thenReturn(SlotBundle.empty());

        RecommendResponse response = service(false).recommend(SESSION, USER, "something tasty", false);

        assertThat(response.source()).isEqualTo(Source.RULES);
        assertThat(response.text()).contains("breakfast, lunch, or dinner");
        assertThat(savedState().phase()).isEqualTo(SessionPhase.CLARIFY);
        verify(search, never()).search(any());
    }

    @Test
    void theRulesPathRefusesToRecommendWhenAnAllergyCannotBeFiltered() {
        RecommendResponse response = service(false).recommend(SESSION, USER, "dinner, I'm allergic to kiwi", false);

        assertThat(response.meals()).isEmpty();
        assertThat(response.text()).contains("allergy I can't filter");
        verify(search, never()).search(any());
    }

    @Test
    void theRulesPathShowsAtMostThreeMeals() {
        List<MealItem> five = List.of(meal(1, "5", List.of()), meal(2, "5", List.of()), meal(4, "5", List.of()),
                meal(5, "5", List.of()), meal(6, "5", List.of()));
        when(search.search(any())).thenReturn(five);
        when(rank.rank(any())).thenReturn(five);

        RecommendResponse response = service(false).recommend(SESSION, USER, "dinner", false);

        assertThat(response.meals()).hasSize(3);
        assertThat(savedState().lastRecommendations()).containsExactly(1L, 2L, 4L);
    }

    // ---- input checks ----

    @Test
    void badInputIsRejectedBeforeAnythingRuns() {
        RecommendationService service = service(true);
        assertThatThrownBy(() -> service.recommend(SESSION, USER, "  ", false)).isInstanceOf(MealException.class);
        assertThatThrownBy(() -> service.recommend(SESSION, USER, null, false)).isInstanceOf(MealException.class);
        assertThatThrownBy(() -> service.recommend(SESSION, USER, "x".repeat(1001), false)).isInstanceOf(MealException.class);
        verify(agent, never()).recommend(any());
    }

    @Test
    void anUnknownSessionIsAnError() {
        when(sessions.find("nope", USER)).thenThrow(new MealException("Session not found for this user"));
        assertThatThrownBy(() -> service(true).recommend("nope", USER, "dinner", false))
                .isInstanceOf(MealException.class).hasMessageContaining("Session not found");
    }
}

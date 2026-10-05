"""What is specific to the LangGraph version (the shared behaviour is covered by tests/test_agent.py, which runs both)."""

import operator
from typing import Annotated, TypedDict

from langgraph.graph import END, START, StateGraph

from app.agent import Status
from app.agent_graph import build_agent_graph, run_agent_graph
from app.tools import AgentTools, RunContext
from tests.conftest import run
from tests.test_agent import NO_MATCH, NUTRITION, SEARCH_OK, FakeLLM, final_reply, recommendation, tool_reply


def tools_for(backend) -> AgentTools:
    return AgentTools(backend, NUTRITION, RunContext(user_id=7))


def test_the_graph_has_the_loop_nodes_the_pause_node_and_the_conditional_edges(backend):
    definitions = run(tools_for(backend).definitions())
    drawing = build_agent_graph(FakeLLM(), tools_for(backend), definitions, model="gpt-4o-mini", temperature=0.0, max_rounds=8,
                                max_format_retries=2, max_verify_retries=2, spend=None).get_graph()

    assert set(drawing.nodes) == {"__start__", "call_model", "run_tools", "ask_user", "__end__"}
    edges = {(e.source, e.target, e.conditional) for e in drawing.edges}
    assert ("__start__", "call_model", False) in edges
    assert ("ask_user", "call_model", False) in edges
    assert {("run_tools", "call_model", True), ("run_tools", "ask_user", True)} <= edges
    assert {("call_model", "run_tools", True), ("call_model", "call_model", True), ("call_model", "__end__", True)} <= edges


def test_on_step_sees_the_state_grow_one_step_at_a_time(backend):
    seen = []
    llm = FakeLLM(tool_reply(SEARCH_OK), final_reply(recommendation()))
    result = run(run_agent_graph(llm, tools_for(backend), "dinner", model="gpt-4o-mini", system_prompt="S",
                                 on_step=lambda s: seen.append((s["round"], len(s["messages"]), len(s["pending_calls"])))))

    assert result.status is Status.SUCCESS
    # input; after model (asked for a tool); after tools (result appended); after model (final answer)
    assert seen == [(0, 2, 0), (1, 3, 1), (1, 4, 0), (2, 4, 0)]


def test_without_our_own_round_limit_the_frameworks_recursion_limit_ends_a_runaway_loop(backend):
    # A model that asks for a (different) search forever; the forced-final switch is ignored by this fake.
    searches = [tool_reply(("search_meals", {"max_price": 100 - i, "exclude_allergens": []})) for i in range(60)]
    llm = FakeLLM(*searches)

    result = run(run_agent_graph(llm, tools_for(backend), "dinner", model="gpt-4o-mini", system_prompt="S", max_rounds=8,
                                 enforce_round_limit=False))

    assert result.status is Status.ERROR
    assert "recursion limit" in result.error
    assert len(llm.calls) < 20            # it did stop, by the framework's backstop, not by running out of script


def test_with_the_round_limit_the_same_runaway_loop_ends_cleanly_as_max_rounds(backend):
    searches = [tool_reply(("search_meals", {"max_price": 100 - i, "exclude_allergens": []})) for i in range(60)]
    llm = FakeLLM(*searches)

    result = run(run_agent_graph(llm, tools_for(backend), "dinner", model="gpt-4o-mini", system_prompt="S", max_rounds=8))

    assert result.status is Status.MAX_ROUNDS and len(llm.calls) == 8


def test_a_list_returned_by_a_node_replaces_the_field_unless_it_has_a_reducer():
    """Why AgentState.messages is Annotated[..., operator.add]: the concept in 20 lines."""

    class Plain(TypedDict):
        messages: list[str]

    class Appending(TypedDict):
        messages: Annotated[list[str], operator.add]

    def build(schema):
        graph = StateGraph(schema)
        graph.add_node("answer", lambda state: {"messages": ["assistant: hi"]})
        graph.add_edge(START, "answer")
        graph.add_edge("answer", END)
        return graph.compile()

    start = {"messages": ["system: rules", "user: dinner"]}
    assert build(Plain).invoke(start)["messages"] == ["assistant: hi"]                    # the history is gone
    assert build(Appending).invoke(start)["messages"] == ["system: rules", "user: dinner", "assistant: hi"]


def test_the_graph_run_keeps_the_whole_conversation_for_the_next_model_call(backend):
    llm = FakeLLM(tool_reply(SEARCH_OK), final_reply(NO_MATCH))
    run(run_agent_graph(llm, tools_for(backend), "dinner", model="gpt-4o-mini", system_prompt="S"))

    roles = [m["role"] for m in llm.calls[1]["messages"]]
    assert roles[:2] == ["system", "user"] and roles[-2:] == ["assistant", "tool"]

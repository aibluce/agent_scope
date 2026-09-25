package com.ll.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Paths;

public class FirstAgent {
    public static void main(String[] args) {
        OpenAIChatModel chatModel = OpenAIChatModel.builder().baseUrl("https://api.deepseek.com")
                .apiKey("")
                .modelName("deepseek-flash").build();
        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("You are a note-taking assistant.")
                // String form resolved via ModelRegistry — picks up DASHSCOPE_API_KEY
                // from the environment. Use "openai:gpt-5.5", "anthropic:claude-sonnet-4-5",
                // "gemini:gemini-2.0-flash", or "ollama:llama3" to switch providers.
                .model(chatModel)
                .workspace(Paths.get(".agentscope/workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("alice")
                .build();

        // Turn 1: introduce yourself + state today's task
        agent.call(new UserMessage("My name is Alice, and I'm preparing a tech talk on ReAct today."), ctx).block();

        // Turn 2: same sessionId — state from turn 1 is restored automatically
        agent.call(new UserMessage("What is my name? What am I doing today?"), ctx).block();

        agent.streamEvents(new UserMessage("Summarize today in three bullets."))
                .doOnNext(event -> {
                    if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                        // Streaming text fragment — append to UI or stdout
                        System.out.print(((TextBlockDeltaEvent) event).getDelta());
                    } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                        // The agent is about to call a tool — surface the call info
                        System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallName());
                    }
                    // Other events: thinking blocks, tool results, reply end, etc.
                })
                .blockLast();
    }
}

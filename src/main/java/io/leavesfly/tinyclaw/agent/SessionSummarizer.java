package io.leavesfly.tinyclaw.agent;

import io.leavesfly.tinyclaw.logger.TinyClawLogger;
import io.leavesfly.tinyclaw.providers.LLMProvider;
import io.leavesfly.tinyclaw.providers.LLMResponse;
import io.leavesfly.tinyclaw.providers.Message;
import io.leavesfly.tinyclaw.session.Session;
import io.leavesfly.tinyclaw.session.SessionManager;
import io.leavesfly.tinyclaw.util.StringUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.leavesfly.tinyclaw.agent.AgentConstants.*;

/**
 * 会话摘要器 - 负责管理会话摘要和历史记录
 */
public class SessionSummarizer {
    
    private static final TinyClawLogger logger = TinyClawLogger.getLogger("agent.summarizer");
    
    private final SessionManager sessions;
    private final LLMProvider provider;
    private final String model;
    private final int contextWindow;
    private final Set<String> summarizing = ConcurrentHashMap.newKeySet();
    
    public SessionSummarizer(SessionManager sessions, LLMProvider provider, 
                            String model, int contextWindow) {
        this.sessions = sessions;
        this.provider = provider;
        this.model = model;
        this.contextWindow = contextWindow;
    }
    
    /**
     * 根据需要触发会话摘要
     */
    public void maybeSummarize(String sessionKey) {
        List<Message> history = sessions.getHistory(sessionKey);
        int tokenEstimate = estimateTokens(history);
        int threshold = contextWindow * SUMMARIZE_TOKEN_PERCENTAGE / 100;
        
        if (history.size() > SUMMARIZE_MESSAGE_THRESHOLD || tokenEstimate > threshold) {
            if (summarizing.add(sessionKey)) {
                Thread thread = new Thread(() -> {
                    try {
                        summarize(sessionKey);
                    } finally {
                        summarizing.remove(sessionKey);
                    }
                });
                thread.setDaemon(true);
                thread.start();
            }
        }
    }
    
    /**
     * 摘要一个会话
     */
    private void summarize(String sessionKey) {
        List<Message> history = sessions.getHistory(sessionKey);
        String existingSummary = sessions.getSummary(sessionKey);
        
        if (history.size() <= RECENT_MESSAGES_TO_KEEP) {
            return;
        }
        
        List<Message> toSummarize = new ArrayList<>(
                history.subList(0, history.size() - RECENT_MESSAGES_TO_KEEP)
        );
        
        List<Message> validMessages = filterValidMessages(toSummarize);
        if (validMessages.isEmpty()) {
            return;
        }
        
        String finalSummary = generateSummary(validMessages, existingSummary);
        
        if (StringUtils.isNotBlank(finalSummary)) {
            saveSummary(sessionKey, finalSummary, toSummarize.size(), validMessages.size());
        }
    }
    
    private List<Message> filterValidMessages(List<Message> messages) {
        List<Message> validMessages = new ArrayList<>();
        int maxMessageTokens = contextWindow / 2;
        
        for (Message m : messages) {
            if (!"user".equals(m.getRole()) && !"assistant".equals(m.getRole())) {
                continue;
            }
            int msgTokens = StringUtils.estimateTokens(m.getContent());
            if (msgTokens <= maxMessageTokens) {
                validMessages.add(m);
            }
        }
        
        return validMessages;
    }
    
    private String generateSummary(List<Message> validMessages, String existingSummary) {
        if (validMessages.size() > BATCH_SUMMARIZE_THRESHOLD) {
            return generateBatchSummary(validMessages, existingSummary);
        } else {
            return summarizeBatch(validMessages, existingSummary);
        }
    }
    
    private String generateBatchSummary(List<Message> validMessages, String existingSummary) {
        int mid = validMessages.size() / 2;
        List<Message> part1 = validMessages.subList(0, mid);
        List<Message> part2 = validMessages.subList(mid, validMessages.size());
        
        String s1 = summarizeBatch(part1, existingSummary);
        String s2 = summarizeBatch(part2, null);
        
        return mergeSummaries(s1, s2);
    }
    
    private String mergeSummaries(String s1, String s2) {
        if (s1 != null && s2 != null) {
            String mergePrompt = String.format(
                    "Merge these two conversation summaries into one cohesive summary:\n\n1: %s\n\n2: %s",
                    s1, s2
            );
            try {
                List<Message> mergeMessages = List.of(Message.user(mergePrompt));
                Map<String, Object> options = new HashMap<>();
                options.put("max_tokens", SUMMARY_MAX_TOKENS);
                options.put("temperature", SUMMARY_TEMPERATURE);
                
                LLMResponse response = provider.chat(mergeMessages, null, model, options);
                return response.getContent();
            } catch (Exception e) {
                return s1 + " " + s2;
            }
        }
        return s1 != null ? s1 : s2;
    }
    
    private String summarizeBatch(List<Message> batch, String existingSummary) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Provide a concise summary of this conversation segment, preserving core context and key points.\n");
        
        if (StringUtils.isNotBlank(existingSummary)) {
            prompt.append("Existing context: ").append(existingSummary).append("\n");
        }
        
        prompt.append("\nCONVERSATION:\n");
        for (Message m : batch) {
            prompt.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
        }
        
        try {
            List<Message> summaryMessages = List.of(Message.user(prompt.toString()));
            Map<String, Object> options = new HashMap<>();
            options.put("max_tokens", SUMMARY_MAX_TOKENS);
            options.put("temperature", SUMMARY_TEMPERATURE);
            
            LLMResponse response = provider.chat(summaryMessages, null, model, options);
            return response.getContent();
        } catch (Exception e) {
            logger.error("Failed to summarize batch", Map.of("error", e.getMessage()));
            return null;
        }
    }
    
    private void saveSummary(String sessionKey, String summary, 
                            int originalSize, int validSize) {
        sessions.setSummary(sessionKey, summary);
        sessions.truncateHistory(sessionKey, RECENT_MESSAGES_TO_KEEP);
        Session session = sessions.getOrCreate(sessionKey);
        sessions.save(session);
        
        logger.info("Session summarized", Map.of(
                "session_key", sessionKey,
                "original_messages", originalSize,
                "valid_messages", validSize
        ));
    }
    
    private int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += StringUtils.estimateTokens(m.getContent());
        }
        return total;
    }
}

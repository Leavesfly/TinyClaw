package io.leavesfly.tinyclaw.providers;

import java.util.Map;

/**
 * Tool call from the LLM
 */
public class ToolCall {

    private String id;
    private String type;
    private String name;
    private Map<String, Object> arguments;
    private FunctionCall function;

    public ToolCall() {
    }

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.type = "function";
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }

    public FunctionCall getFunction() {
        return function;
    }

    public void setFunction(FunctionCall function) {
        this.function = function;
        if (function != null) {
            this.name = function.getName();
        }
    }

    /**
     * Function call details (OpenAI format)
     */
    public static class FunctionCall {
        private String name;
        private String arguments;

        public FunctionCall() {
        }

        public FunctionCall(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }
}

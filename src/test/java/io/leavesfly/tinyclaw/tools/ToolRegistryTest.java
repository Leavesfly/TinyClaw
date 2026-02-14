package io.leavesfly.tinyclaw.tools;

import io.leavesfly.tinyclaw.providers.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ToolRegistry 工具注册表单元测试
 *
 * <h2>学习目标</h2>
 * <ul>
 *   <li>使用 Mockito 创建 Mock 对象模拟 Tool 接口</li>
 *   <li>理解注册表模式的测试方法</li>
 *   <li>学习异常断言：assertThrows</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -Dtest=ToolRegistryTest
 * </pre>
 */
@DisplayName("ToolRegistry 工具注册表测试")
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    // ==================== 注册/注销测试 ====================

    @Test
    @DisplayName("register: 注册工具后可以获取")
    void register_ValidTool_CanBeRetrieved() {
        Tool mockTool = createMockTool("test-tool", "Test description");
        
        registry.register(mockTool);
        
        assertTrue(registry.hasTool("test-tool"));
        assertTrue(registry.get("test-tool").isPresent());
        assertEquals(mockTool, registry.get("test-tool").get());
    }

    @Test
    @DisplayName("register: 重复注册覆盖旧工具")
    void register_DuplicateName_OverwritesOld() {
        Tool tool1 = createMockTool("same-name", "First");
        Tool tool2 = createMockTool("same-name", "Second");
        
        registry.register(tool1);
        registry.register(tool2);
        
        assertEquals(1, registry.count());
        assertEquals(tool2, registry.get("same-name").get());
    }

    @Test
    @DisplayName("unregister: 注销工具后无法获取")
    void unregister_RegisteredTool_Removed() {
        Tool mockTool = createMockTool("to-remove", "Will be removed");
        registry.register(mockTool);
        assertTrue(registry.hasTool("to-remove"));
        
        registry.unregister("to-remove");
        
        assertFalse(registry.hasTool("to-remove"));
        assertTrue(registry.get("to-remove").isEmpty());
    }

    @Test
    @DisplayName("unregister: 注销不存在的工具不报错")
    void unregister_NonExistent_NoError() {
        assertDoesNotThrow(() -> registry.unregister("non-existent"));
    }

    // ==================== get / hasTool 测试 ====================

    @Test
    @DisplayName("get: 不存在的工具返回空 Optional")
    void get_NonExistent_ReturnsEmpty() {
        assertTrue(registry.get("unknown").isEmpty());
    }

    @Test
    @DisplayName("hasTool: 正确判断工具是否存在")
    void hasTool_CorrectlyIdentifiesExistence() {
        assertFalse(registry.hasTool("tool1"));
        
        registry.register(createMockTool("tool1", "desc"));
        
        assertTrue(registry.hasTool("tool1"));
        assertFalse(registry.hasTool("tool2"));
    }

    // ==================== execute 测试 ====================

    @Test
    @DisplayName("execute: 成功执行工具并返回结果")
    void execute_ValidTool_ReturnsResult() throws Exception {
        Tool mockTool = createMockTool("exec-tool", "Executable tool");
        when(mockTool.execute(anyMap())).thenReturn("execution result");
        registry.register(mockTool);
        
        Map<String, Object> args = new HashMap<>();
        args.put("param1", "value1");
        
        String result = registry.execute("exec-tool", args);
        
        assertEquals("execution result", result);
        verify(mockTool).execute(args);
    }

    @Test
    @DisplayName("execute: 工具不存在抛出异常")
    void execute_NonExistentTool_ThrowsException() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> registry.execute("non-existent", new HashMap<>())
        );
        assertTrue(ex.getMessage().contains("Tool not found"));
    }

    @Test
    @DisplayName("execute: 工具执行失败传递异常")
    void execute_ToolThrowsException_PropagatesException() throws Exception {
        Tool mockTool = createMockTool("failing-tool", "Will fail");
        when(mockTool.execute(anyMap())).thenThrow(new RuntimeException("Execution failed"));
        registry.register(mockTool);
        
        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> registry.execute("failing-tool", new HashMap<>())
        );
        assertEquals("Execution failed", ex.getMessage());
    }

    // ==================== list / count 测试 ====================

    @Test
    @DisplayName("list: 返回所有工具名称")
    void list_ReturnsAllToolNames() {
        registry.register(createMockTool("tool-a", "A"));
        registry.register(createMockTool("tool-b", "B"));
        registry.register(createMockTool("tool-c", "C"));
        
        List<String> names = registry.list();
        
        assertEquals(3, names.size());
        assertTrue(names.contains("tool-a"));
        assertTrue(names.contains("tool-b"));
        assertTrue(names.contains("tool-c"));
    }

    @Test
    @DisplayName("count: 返回正确的工具数量")
    void count_ReturnsCorrectNumber() {
        assertEquals(0, registry.count());
        
        registry.register(createMockTool("t1", ""));
        assertEquals(1, registry.count());
        
        registry.register(createMockTool("t2", ""));
        assertEquals(2, registry.count());
        
        registry.unregister("t1");
        assertEquals(1, registry.count());
    }

    // ==================== getDefinitions 测试 ====================

    @Test
    @DisplayName("getDefinitions: 返回 OpenAI 格式工具定义")
    void getDefinitions_ReturnsToolDefinitions() {
        Tool tool1 = createMockTool("read_file", "Read a file");
        Tool tool2 = createMockTool("write_file", "Write to a file");
        
        Map<String, Object> params1 = new HashMap<>();
        params1.put("type", "object");
        when(tool1.parameters()).thenReturn(params1);
        
        Map<String, Object> params2 = new HashMap<>();
        params2.put("type", "object");
        when(tool2.parameters()).thenReturn(params2);
        
        registry.register(tool1);
        registry.register(tool2);
        
        List<ToolDefinition> definitions = registry.getDefinitions();
        
        assertEquals(2, definitions.size());
    }

    // ==================== getSummaries 测试 ====================

    @Test
    @DisplayName("getSummaries: 返回人类可读的工具摘要")
    void getSummaries_ReturnsHumanReadableSummaries() {
        registry.register(createMockTool("exec", "Execute shell command"));
        registry.register(createMockTool("search", "Search the web"));
        
        List<String> summaries = registry.getSummaries();
        
        assertEquals(2, summaries.size());
        assertTrue(summaries.stream().anyMatch(s -> s.contains("exec") && s.contains("Execute shell command")));
        assertTrue(summaries.stream().anyMatch(s -> s.contains("search") && s.contains("Search the web")));
    }

    // ==================== clear 测试 ====================

    @Test
    @DisplayName("clear: 清除所有工具")
    void clear_RemovesAllTools() {
        registry.register(createMockTool("t1", ""));
        registry.register(createMockTool("t2", ""));
        assertEquals(2, registry.count());
        
        registry.clear();
        
        assertEquals(0, registry.count());
        assertTrue(registry.list().isEmpty());
    }

    // ==================== 辅助方法 ====================

    private Tool createMockTool(String name, String description) {
        Tool tool = mock(Tool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn(description);
        when(tool.parameters()).thenReturn(new HashMap<>());
        return tool;
    }
}

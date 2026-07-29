package io.leavesfly.tinyclaw.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubagentsLoader 动态子代理定义加载的单元测试。
 *
 * 覆盖场景：
 * - AGENT.md 前置元数据解析（description/model/tools/max_iterations）与正文提取
 * - 目录扫描与列表、按名加载、不存在时返回 null
 * - workspace 定义的保存与删除（CRUD）
 * - 摘要构建（注入 spawn 工具描述用）
 * - 内置定义的 classpath 加载与优先级覆盖（workspace &gt; global &gt; builtin）
 */
class SubagentsLoaderTest {

    @TempDir
    Path workspace;

    private void writeAgent(String name, String content) throws IOException {
        Path dir = workspace.resolve("agents").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("AGENT.md"), content);
    }

    /**
     * 过滤掉内置定义，便于断言测试自建的定义
     */
    private List<SubagentDefinition> nonBuiltin(List<SubagentDefinition> agents) {
        return agents.stream().filter(a -> !"builtin".equals(a.getSource())).toList();
    }

    @Test
    void parseFullFrontmatterAndBody() throws IOException {
        writeAgent("code-reviewer", "---\n" +
                "name: code-reviewer\n" +
                "description: 专职代码审查\n" +
                "model: qwen-max\n" +
                "tools: [read_file, grep, spawn]\n" +
                "max_iterations: 15\n" +
                "---\n" +
                "\n" +
                "你是一名资深代码审查专家。\n");

        SubagentsLoader loader = new SubagentsLoader(workspace.toString());
        SubagentDefinition def = loader.load("code-reviewer");

        assertNotNull(def);
        assertEquals("code-reviewer", def.getName());
        assertEquals("专职代码审查", def.getDescription());
        assertEquals("qwen-max", def.getModel());
        assertEquals(List.of("read_file", "grep", "spawn"), def.getTools());
        assertEquals(15, def.getMaxIterations());
        assertEquals("你是一名资深代码审查专家。", def.getSystemPrompt());
        assertEquals("workspace", def.getSource());
    }

    @Test
    void minimalDefinitionInheritsDefaults() throws IOException {
        writeAgent("simple", "---\ndescription: 极简定义\n---\n只做一件事。\n");

        SubagentDefinition def = new SubagentsLoader(workspace.toString()).load("simple");

        assertNotNull(def);
        assertNull(def.getModel());
        assertNull(def.getTools());
        assertEquals(0, def.getMaxIterations());
        assertEquals("只做一件事。", def.getSystemPrompt());
    }

    @Test
    void listAgentsScansDirectory() throws IOException {
        writeAgent("a1", "---\ndescription: 甲\n---\nprompt A\n");
        writeAgent("a2", "---\ndescription: 乙\n---\nprompt B\n");
        // 无 AGENT.md 的目录应被忽略
        Files.createDirectories(workspace.resolve("agents").resolve("empty"));

        SubagentsLoader loader = new SubagentsLoader(workspace.toString());
        List<SubagentDefinition> agents = nonBuiltin(loader.listAgents());

        assertEquals(2, agents.size());
        assertTrue(agents.stream().anyMatch(a -> a.getName().equals("a1")));
        assertTrue(agents.stream().anyMatch(a -> a.getName().equals("a2")));
    }

    @Test
    void loadMissingReturnsNull() {
        SubagentsLoader loader = new SubagentsLoader(workspace.toString());
        assertNull(loader.load("not-exist"));
        assertNull(loader.load(null));
        assertNull(loader.load(""));
        // 无文件系统定义时，列表仅包含内置定义
        assertTrue(nonBuiltin(loader.listAgents()).isEmpty());
    }

    @Test
    void saveAndDeleteWorkspaceAgent() {
        SubagentsLoader loader = new SubagentsLoader(workspace.toString());

        assertTrue(loader.saveWorkspaceAgent("dyn", "---\ndescription: 动态创建\n---\n动态 prompt\n"));
        SubagentDefinition def = loader.load("dyn");
        assertNotNull(def);
        assertEquals("动态创建", def.getDescription());

        assertTrue(loader.deleteWorkspaceAgent("dyn"));
        assertNull(loader.load("dyn"));
        assertFalse(loader.deleteWorkspaceAgent("dyn"));
    }

    @Test
    void buildAgentsSummaryFormat() throws IOException {
        writeAgent("reviewer", "---\ndescription: 代码审查\n---\nprompt\n");

        String summary = new SubagentsLoader(workspace.toString()).buildAgentsSummary();

        assertTrue(summary.contains("- reviewer: 代码审查"));
        // 内置定义也应出现在摘要中
        assertTrue(summary.contains("- researcher:"));
    }

    @Test
    void workspaceOverridesGlobal() throws IOException {
        // 全局目录与工作空间目录定义同名子代理，workspace 优先
        Path globalDir = workspace.resolve("global-agents");
        Files.createDirectories(globalDir.resolve("dup"));
        Files.writeString(globalDir.resolve("dup").resolve("AGENT.md"),
                "---\ndescription: 全局版\n---\nglobal prompt\n");
        writeAgent("dup", "---\ndescription: 工作空间版\n---\nws prompt\n");

        SubagentsLoader loader = new SubagentsLoader(workspace.toString(), globalDir.toString());

        assertEquals("工作空间版", loader.load("dup").getDescription());
        List<SubagentDefinition> agents = nonBuiltin(loader.listAgents());
        assertEquals(1, agents.size());
        assertEquals("workspace", agents.get(0).getSource());
    }

    @Test
    void builtinAgentsLoadedFromClasspath() {
        SubagentsLoader loader = new SubagentsLoader(workspace.toString());

        // 内置定义全部可列出
        List<SubagentDefinition> agents = loader.listAgents();
        for (String name : SubagentsLoader.getBuiltinAgentNames()) {
            assertTrue(agents.stream().anyMatch(a -> a.getName().equals(name)
                    && "builtin".equals(a.getSource())), "missing builtin agent: " + name);
        }

        // 按名加载并正确解析元数据与正文
        SubagentDefinition def = loader.load("code-reviewer");
        assertNotNull(def);
        assertEquals("builtin", def.getSource());
        assertFalse(def.getDescription().isEmpty());
        assertNotNull(def.getTools());
        assertTrue(def.getTools().contains("read_file"));
        assertEquals(15, def.getMaxIterations());
        assertFalse(def.getSystemPrompt().isEmpty());
        assertFalse(def.getSystemPrompt().startsWith("---"));
    }

    @Test
    void workspaceOverridesBuiltin() throws IOException {
        writeAgent("code-reviewer", "---\ndescription: 定制审查员\n---\n定制 prompt\n");

        SubagentsLoader loader = new SubagentsLoader(workspace.toString());

        SubagentDefinition def = loader.load("code-reviewer");
        assertEquals("workspace", def.getSource());
        assertEquals("定制审查员", def.getDescription());

        // 列表中同名只出现一次，且为 workspace 版本
        List<SubagentDefinition> matches = loader.listAgents().stream()
                .filter(a -> a.getName().equals("code-reviewer")).toList();
        assertEquals(1, matches.size());
        assertEquals("workspace", matches.get(0).getSource());
    }
}

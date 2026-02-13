package io.leavesfly.tinyclaw;

import io.leavesfly.tinyclaw.cli.CliCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TinyClaw 应用入口测试类
 *
 * <h2>学习目标</h2>
 * <ul>
 *   <li>理解 JUnit 5 的基本测试注解（@Test）</li>
 *   <li>学习 Mockito 的 mock() / when() / verify() 三件套</li>
 *   <li>掌握命令行应用的测试思路：输入参数 → 退出码验证</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * mvn test -Dtest=TinyClawTest           # 运行本测试类
 * mvn test -Dtest=TinyClawTest#testRun_VersionCommand_ReturnsSuccess   # 运行单个方法
 * </pre>
 *
 * <h2>学习提示</h2>
 * <ol>
 *   <li>先阅读 {@link TinyClaw#run(String[])} 理解被测方法逻辑</li>
 *   <li>观察每个测试的"三段式"结构：Arrange（准备）→ Act（执行）→ Assert（断言）</li>
 *   <li>思考：为什么使用 Mockito 而不是真实调用 CliCommand？（隔离依赖、提高速度）</li>
 * </ol>
 *
 * @see TinyClaw
 * @see CliCommand
 */
class TinyClawTest {

    /**
     * 测试：无参数时返回错误码 1
     * <p>学习点：边界条件测试 —— 空输入是常见的边界场景</p>
     */
    @Test
    void testRun_NoArgs_ReturnsError() {
        int exitCode = TinyClaw.run(new String[]{});
        assertEquals(1, exitCode, "Should return 1 when no arguments provided");
    }

    /**
     * 测试：version 命令的多种写法都返回成功
     * <p>学习点：等价类测试 —— "version"、"--version"、"-v" 是同一功能的不同表达</p>
     */
    @Test
    void testRun_VersionCommand_ReturnsSuccess() {
        int exitCode = TinyClaw.run(new String[]{"version"});
        assertEquals(0, exitCode, "Should return 0 for version command");
        
        exitCode = TinyClaw.run(new String[]{"--version"});
        assertEquals(0, exitCode, "Should return 0 for --version command");
        
        exitCode = TinyClaw.run(new String[]{"-v"});
        assertEquals(0, exitCode, "Should return 0 for -v command");
    }

    /**
     * 测试：未知命令返回错误码 1
     * <p>学习点：异常路径测试 —— 确保系统对非法输入有明确的错误处理</p>
     */
    @Test
    void testRun_UnknownCommand_ReturnsError() {
        int exitCode = TinyClaw.run(new String[]{"unknown-command"});
        assertEquals(1, exitCode, "Should return 1 for unknown command");
    }

    /**
     * 测试：有效命令能正确执行并传递参数
     * <p>
     * 学习点：
     * <ul>
     *   <li>mock(CliCommand.class) —— 创建一个假的 CliCommand 对象</li>
     *   <li>when(...).thenReturn(...) —— 定义 mock 对象的行为</li>
     *   <li>verify(...).execute(...) —— 验证 mock 对象的方法是否被调用</li>
     * </ul>
     * </p>
     */
    @Test
    void testRun_ValidCommand_ExecutesCommand() throws Exception {
        // Arrange（准备）：创建 mock 命令
        CliCommand mockCommand = mock(CliCommand.class);
        when(mockCommand.execute(any())).thenReturn(0);
        
        // 将 mock 命令注册到命令注册表
        TinyClaw.registerCommand("test-cmd", () -> mockCommand);
        
        // Act（执行）：运行命令
        int exitCode = TinyClaw.run(new String[]{"test-cmd", "arg1"});
        
        // Assert（断言）：验证结果和行为
        assertEquals(0, exitCode, "Should return 0 for successful command execution");
        verify(mockCommand).execute(new String[]{"arg1"});
    }
    
    /**
     * 测试：命令的退出码能正确传递
     * <p>学习点：状态传递测试 —— 确保子模块的返回值能正确向上传递</p>
     */
    @Test
    void testRun_ValidCommand_ReturnsCommandExitCode() throws Exception {
        // Mock a command that fails
        CliCommand mockCommand = mock(CliCommand.class);
        when(mockCommand.execute(any())).thenReturn(5);
        
        // Register the mock command
        TinyClaw.registerCommand("fail-cmd", () -> mockCommand);
        
        // Run with the mock command
        int exitCode = TinyClaw.run(new String[]{"fail-cmd"});
        
        // Verify
        assertEquals(5, exitCode, "Should return the exit code from the command");
    }
}

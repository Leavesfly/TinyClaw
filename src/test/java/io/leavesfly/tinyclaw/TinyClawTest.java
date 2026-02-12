package io.leavesfly.tinyclaw;

import io.leavesfly.tinyclaw.cli.CliCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TinyClawTest {

    @Test
    void testRun_NoArgs_ReturnsError() {
        int exitCode = TinyClaw.run(new String[]{});
        assertEquals(1, exitCode, "Should return 1 when no arguments provided");
    }

    @Test
    void testRun_VersionCommand_ReturnsSuccess() {
        int exitCode = TinyClaw.run(new String[]{"version"});
        assertEquals(0, exitCode, "Should return 0 for version command");
        
        exitCode = TinyClaw.run(new String[]{"--version"});
        assertEquals(0, exitCode, "Should return 0 for --version command");
        
        exitCode = TinyClaw.run(new String[]{"-v"});
        assertEquals(0, exitCode, "Should return 0 for -v command");
    }

    @Test
    void testRun_UnknownCommand_ReturnsError() {
        int exitCode = TinyClaw.run(new String[]{"unknown-command"});
        assertEquals(1, exitCode, "Should return 1 for unknown command");
    }

    @Test
    void testRun_ValidCommand_ExecutesCommand() throws Exception {
        // Mock a command
        CliCommand mockCommand = mock(CliCommand.class);
        when(mockCommand.execute(any())).thenReturn(0);
        
        // Register the mock command
        TinyClaw.registerCommand("test-cmd", () -> mockCommand);
        
        // Run with the mock command
        int exitCode = TinyClaw.run(new String[]{"test-cmd", "arg1"});
        
        // Verify
        assertEquals(0, exitCode, "Should return 0 for successful command execution");
        verify(mockCommand).execute(new String[]{"arg1"});
    }
    
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

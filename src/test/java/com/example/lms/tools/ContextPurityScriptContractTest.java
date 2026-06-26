package com.example.lms.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ContextPurityScriptContractTest {

    @Test
    void defaultReportModeUsesBoundedScanRootsInsteadOfWholeWorkspaceRecursion() throws Exception {
        String script = Files.readString(Path.of("tools/context_purity_score.ps1"));

        assertTrue(script.contains("[switch]$FullWorkspaceScan"));
        assertTrue(script.contains("function Get-ContextPurityReportItems"));
        assertTrue(script.contains("if ($FullWorkspaceScan)"));
        assertTrue(script.contains("app/src/main/java_clean"));
        assertTrue(script.contains("app/src/main/resources"));
        assertTrue(script.contains(".agents/skills"));
        assertTrue(script.contains("docs/ai-memory"));
        assertTrue(script.contains("agent-prompts"));
        assertFalse(script.contains("$items = Get-ChildItem -LiteralPath $workspace -Force -Recurse -File"),
                "default context purity report must not enumerate build, PatchDrop, and cache trees before filtering");
    }
}

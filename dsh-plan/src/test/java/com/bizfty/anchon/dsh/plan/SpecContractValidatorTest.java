package com.bizfty.anchon.dsh.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为契约格式校验测试（对齐 OpenSpec 契约规则）：
 * SHALL/MUST 规范性语言、每 Requirement 至少一个 #### Scenario、增量头合法性。
 */
class SpecContractValidatorTest {

    private static final String VALID = """
            ## Purpose

            Lets users export their data in a portable format.

            ## ADDED Requirements

            ### Requirement: User can export data
            The system SHALL allow users to export their data in CSV format.

            #### Scenario: Successful export
            - GIVEN a user with data
            - WHEN the user requests export
            - THEN a CSV file is returned

            #### Scenario: No data
            - GIVEN a user with no data
            - WHEN the user requests export
            - THEN an empty CSV is returned
            """;

    @Test
    void validContractPasses() {
        assertTrue(SpecContractValidator.validate(VALID).isEmpty());
    }

    @Test
    void missingScenarioFails() {
        String text = """
                ### Requirement: Export
                The system SHALL export data.
                """;
        List<String> errors = SpecContractValidator.validate(text);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("缺少 '#### Scenario:'")));
    }

    @Test
    void nonNormativeLanguageFails() {
        String text = """
                ### Requirement: Export
                The system should export data.

                #### Scenario: Export
                - WHEN user clicks export
                - THEN data is exported
                """;
        List<String> errors = SpecContractValidator.validate(text);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("SHALL/MUST")));
    }

    @Test
    void threeHashtagScenarioFails() {
        // 3 个 # 的 Scenario（OpenSpec：必须恰好 4 个，3 个会静默失败）
        String text = """
                ### Requirement: Export
                The system SHALL export data.

                ### Scenario: Wrong level
                - WHEN user clicks export
                - THEN data is exported
                """;
        List<String> errors = SpecContractValidator.validate(text);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("非法 3 级标题")), "3 个 # 的 Scenario 应报错");
        assertTrue(errors.stream().anyMatch(e -> e.contains("缺少 '#### Scenario:'")), "同时缺合法场景");
    }

    @Test
    void invalidDeltaHeaderFails() {
        String text = """
                ## APPENDED Requirements

                ### Requirement: Export
                The system SHALL export data.

                #### Scenario: Export
                - WHEN user clicks export
                - THEN data is exported
                """;
        List<String> errors = SpecContractValidator.validate(text);
        assertTrue(errors.stream().anyMatch(e -> e.contains("非法增量头 '## APPENDED Requirements'")));
    }

    @Test
    void emptyTextFails() {
        assertEquals(List.of("契约文本为空"), SpecContractValidator.validate("   "));
        assertFalse(SpecContractValidator.validate("").isEmpty());
        assertFalse(SpecContractValidator.validate(null).isEmpty());
    }
}

package com.chatbi.service;

import com.chatbi.model.DatabaseConnection;
import com.chatbi.model.SemanticSQL;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticSQLConverterTest {

    @Mock
    private SchemaMetadataBuilder metadataBuilder;

    private SemanticSQLConverter converter;
    private ObjectMapper objectMapper;

    private final Map<String, Object> metadata = Map.of(
        "tables", Map.of(
            "orders", Map.of(
                "comment", "订单表",
                "columns", List.of(Map.of("name", "id", "type", "int", "comment", "主键")),
                "samples", List.of(Map.of("id", 1))
            )
        )
    );

    @BeforeEach
    void setUp() {
        converter = new SemanticSQLConverter();
        objectMapper = new ObjectMapper();

        ReflectionTestUtils.setField(converter, "metadataBuilder", metadataBuilder);
        ReflectionTestUtils.setField(converter, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(converter, "ollamaBaseUrl", "http://localhost:11434");
        ReflectionTestUtils.setField(converter, "ollamaModelName", "test-model");
        ReflectionTestUtils.setField(converter, "ollamaTimeout", "45s");

        when(metadataBuilder.buildDatabaseMetadata(any(DatabaseConnection.class))).thenReturn(metadata);
        when(metadataBuilder.summarizeMetadataForPrompt(metadata)).thenReturn("- 表 orders: 订单表\n");
    }

    @Test
    void convertToSemanticSQL_shouldReturnParsedResponse() {
        ChatLanguageModel stub = prompt -> "response {\n" +
            "  \"tables\": [\"orders\"],\n" +
            "  \"columns\": [\"orders.id\"],\n" +
            "  \"conditions\": [{\"column\":\"orders.status\",\"operator\":\"=\",\"value\":\"PAID\"}],\n" +
            "  \"aggregations\": [],\n" +
            "  \"joins\": [],\n" +
            "  \"order_by\": [],\n" +
            "  \"group_by\": [],\n" +
            "  \"limit\": 20\n" +
            "}";

        ReflectionTestUtils.setField(converter, "llm", stub);

        SemanticSQL semanticSQL = converter.convertToSemanticSQL("查询订单", null);

        assertThat(semanticSQL.getTables()).containsExactly("orders");
        assertThat(semanticSQL.getColumns()).containsExactly("orders.id");
        assertThat(semanticSQL.getConditions()).singleElement()
            .satisfies(condition -> assertThat(condition.get("operator")).isEqualTo("="));
        assertThat(converter.getLastDebug()).containsEntry("raw_response", stub.generate(""));
    }

    @Test
    void convertToSemanticSQL_shouldReturnFallbackWhenJsonMissing() {
        ChatLanguageModel stub = prompt -> "no json available";
        ReflectionTestUtils.setField(converter, "llm", stub);

        SemanticSQL semanticSQL = converter.convertToSemanticSQL("查询", null);

        assertThat(semanticSQL.getTables()).isEmpty();
        assertThat(converter.getLastDebug()).containsEntry("error", "无法从响应中提取JSON格式的语义SQL");
    }

    @Test
    void validateSemanticSQL_shouldDetectInvalidStructures() {
        Map<String, Object> condition = new HashMap<>();
        condition.put("column", "orders.status");
        condition.put("operator", "=");
        condition.put("value", "PAID");

        SemanticSQL valid = new SemanticSQL(
            List.of("orders"),
            List.of("orders.id"),
            List.of(condition),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        assertThat(converter.validateSemanticSQL(valid)).isTrue();

        Map<String, String> invalidAgg = new HashMap<>();
        invalidAgg.put("function", "INVALID");
        invalidAgg.put("column", "orders.amount");

        SemanticSQL invalid = new SemanticSQL(
            List.of("orders"),
            List.of("orders.id"),
            List.of(condition),
            List.of(invalidAgg),
            List.of(),
            List.of(),
            List.of(),
            null
        );

        assertThat(converter.validateSemanticSQL(invalid)).isFalse();
    }
}

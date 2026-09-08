package com.aifuli.module.supplychain.supplieradapter.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 供应商低频配置使用的受限 JSON Schema 校验器。
 *
 * <p>只支持封闭 object、properties、required、type 与 enum；不实现引用、条件、正则执行或
 * 自定义表达式，从能力上阻止页面配置演变为脚本执行器。Schema 超出该封闭子集时失败关闭。</p>
 */
@Component
@RequiredArgsConstructor
public class ClosedJsonSchemaValidator {

    private static final Set<String> SUPPORTED_ROOT_FIELDS = Set.of(
            "$schema", "$id", "title", "description", "type", "properties",
            "required", "additionalProperties");
    private static final Set<String> SUPPORTED_PROPERTY_FIELDS = Set.of(
            "title", "description", "type", "enum");

    private final ObjectMapper objectMapper;

    public void validate(String schemaResource, JsonNode instance) {
        JsonNode schema = readSchema(schemaResource);
        requireSupportedFields(schema, SUPPORTED_ROOT_FIELDS, "root");
        if (!"object".equals(schema.path("type").asText())
                || !schema.has("properties") || !schema.path("properties").isObject()
                || !schema.has("additionalProperties")
                || schema.path("additionalProperties").asBoolean(true)) {
            throw new IllegalStateException("Adapter Schema must be a closed object: " + schemaResource);
        }
        if (instance == null || !instance.isObject()) {
            throw new IllegalArgumentException("Adapter config must be a JSON object");
        }
        JsonNode properties = schema.path("properties");
        instance.fieldNames().forEachRemaining(field -> {
            JsonNode propertySchema = properties.get(field);
            if (propertySchema == null) {
                throw new IllegalArgumentException("Adapter config contains unknown field: " + field);
            }
            validatePropertySchema(field, propertySchema, instance.get(field));
        });
        for (String required : requiredFields(schema)) {
            if (!instance.hasNonNull(required)) {
                throw new IllegalArgumentException("Adapter config is missing required field: " + required);
            }
        }
    }

    private JsonNode readSchema(String schemaResource) {
        try (var input = new ClassPathResource(schemaResource).getInputStream()) {
            JsonNode schema = objectMapper.readTree(input);
            if (schema == null || !schema.isObject()) {
                throw new IllegalStateException("Adapter Schema is not a JSON object: " + schemaResource);
            }
            return schema;
        } catch (IOException exception) {
            throw new IllegalStateException("Adapter Schema cannot be loaded: " + schemaResource, exception);
        }
    }

    private static void validatePropertySchema(String name, JsonNode schema, JsonNode value) {
        requireSupportedFields(schema, SUPPORTED_PROPERTY_FIELDS, "property " + name);
        String type = schema.path("type").asText();
        boolean matches = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> throw new IllegalStateException("Unsupported Adapter Schema type: " + type);
        };
        if (!matches) {
            throw new IllegalArgumentException("Adapter config field has invalid type: " + name);
        }
        JsonNode allowedValues = schema.get("enum");
        if (allowedValues != null && (!allowedValues.isArray()
                || !contains(allowedValues, value))) {
            throw new IllegalArgumentException("Adapter config field is outside the closed enum: " + name);
        }
    }

    private static Set<String> requiredFields(JsonNode schema) {
        JsonNode required = schema.get("required");
        if (required == null) {
            return Set.of();
        }
        if (!required.isArray()) {
            throw new IllegalStateException("Adapter Schema required must be an array");
        }
        Set<String> result = new HashSet<>();
        required.forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalStateException("Adapter Schema required entry must be a string");
            }
            result.add(value.asText());
        });
        return Set.copyOf(result);
    }

    private static boolean contains(JsonNode array, JsonNode expected) {
        for (JsonNode value : array) {
            if (value.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static void requireSupportedFields(JsonNode node, Set<String> supported, String location) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!supported.contains(name)) {
                throw new IllegalStateException("Unsupported Adapter Schema keyword at "
                        + location + ": " + name);
            }
        }
    }
}

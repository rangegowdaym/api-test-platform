/**
 * Request/response models (Java records) for hand-written services, e.g.
 * <pre>{@code
 * @JsonInclude(JsonInclude.Include.NON_NULL)
 * @JsonIgnoreProperties(ignoreUnknown = true)
 * public record Order(Long id, String status) {}
 * }</pre>
 * See the add-api-service skill.
 */
package com.qa.automation.models;

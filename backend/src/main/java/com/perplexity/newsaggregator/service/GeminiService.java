package com.perplexity.newsaggregator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.Map;
import java.util.List;

@Service
public class GeminiService {
    
    private static final Logger logger = LoggerFactory.getLogger(GeminiService.class);
    
    @Value("${gemini.api.key}")
    private String apiKey;
    
    @Value("${gemini.api.key.backup:}")
    private String apiKeyBackup;
    
    // Fallback key selection
    private volatile boolean useBackupKey = false;
    
    @Value("${gemini.api.url}")
    private String apiUrl;
    
    private final RestTemplate restTemplate = new RestTemplate();
    
    /**
     * Get current Gemini API key with fallback support
     */
    private String getCurrentGeminiKey() {
        if (useBackupKey && apiKeyBackup != null && !apiKeyBackup.trim().isEmpty()) {
            return apiKeyBackup;
        }
        return apiKey;
    }
    
    /**
     * Switch to backup key if available
     */
    private void switchToBackupKey() {
        if (apiKeyBackup != null && !apiKeyBackup.trim().isEmpty()) {
            useBackupKey = true;
            logger.info("Switched to backup Gemini API key");
        }
    }
    
    // Generate completion using Google Gemini 2.5 Flash API with fallback
    public String generateCompletion(String prompt) {
        return generateCompletionWithKey(prompt, getCurrentGeminiKey());
    }
    
    /**
     * Generate completion with specific API key and automatic fallback
     */
    private String generateCompletionWithKey(String prompt, String currentApiKey) {
        try {
            // Prepare headers with API key for Gemini 2.5 Flash
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", currentApiKey);
            
            // Prepare request body for Gemini 2.5 Flash API (updated format)
            Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                    Map.of("parts", List.of(
                        Map.of("text", prompt)
                    ))
                ),
                "generationConfig", Map.of(
                    "temperature", 0.4,
                    "topP", 0.9,
                    "topK", 32,
                    "maxOutputTokens", 8192,
                    "responseMimeType", "application/json"
                ),
                "safetySettings", List.of(
                    Map.of(
                        "category", "HARM_CATEGORY_HATE_SPEECH",
                        "threshold", "BLOCK_MEDIUM_AND_ABOVE"
                    ),
                    Map.of(
                        "category", "HARM_CATEGORY_DANGEROUS_CONTENT", 
                        "threshold", "BLOCK_MEDIUM_AND_ABOVE"
                    ),
                    Map.of(
                        "category", "HARM_CATEGORY_HARASSMENT",
                        "threshold", "BLOCK_MEDIUM_AND_ABOVE"
                    ),
                    Map.of(
                        "category", "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                        "threshold", "BLOCK_MEDIUM_AND_ABOVE"
                    )
                )
            );
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            logger.debug("Making request to Gemini 2.5 Flash API: {}", apiUrl);
            
            // Make API call to Gemini 2.5 Flash
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, entity, 
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>(){});
            
            // Enhanced error handling for HTTP status codes
            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.error("Gemini 2.5 Flash API returned non-success status: {} - {}", 
                    response.getStatusCode(), response.getBody());
                return "{\"error\": \"Gemini API returned status: " + response.getStatusCode() + "\"}";
            }
            
            if (response.getBody() == null) {
                logger.error("Gemini 2.5 Flash API returned null response body");
                return "{\"error\": \"Empty response from Gemini API\"}";
            }
            
            // Parse Gemini 2.5 Flash response format
            Map<String, Object> responseBody = response.getBody();
            logger.debug("Gemini 2.5 Pro API response body: {}", responseBody);
            
            // Additional null check after getBody() call
            if (responseBody == null) {
                logger.error("Gemini 2.5 Flash API returned null response body after successful status");
                return "{\"error\": \"Null response body from Gemini API\"}";
            }
            
            // Check for API errors in response body
            if (responseBody.containsKey("error")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> error = (Map<String, Object>) responseBody.get("error");
                String errorMessage = (String) error.get("message");
                String errorCode = (String) error.get("code");
                logger.error("Gemini 2.5 Flash API returned error - Code: {}, Message: {}", errorCode, errorMessage);
                return "{\"error\": \"Gemini API error: " + errorMessage + "\"}";
            }
            
            // Extract candidates from response
            Object candidatesObj = responseBody.get("candidates");
            if (!(candidatesObj instanceof List)) {
                logger.error("No candidates array in Gemini 2.5 Flash response");
                return "{\"error\": \"Invalid response format: missing candidates\"}";
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) candidatesObj;
            
            if (candidates.isEmpty()) {
                logger.error("Empty candidates array in Gemini 2.5 Flash response");
                return "{\"error\": \"No response candidates from Gemini API\"}";
            }
            
            Map<String, Object> firstCandidate = candidates.get(0);
            
            // Check finish reason for Gemini 2.5 Flash
            String finishReason = (String) firstCandidate.get("finishReason");
            if (finishReason != null && !finishReason.equals("STOP")) {
                logger.warn("Gemini 2.5 Flash finished with non-STOP reason: {}", finishReason);
                if (finishReason.equals("SAFETY")) {
                    return "{\"error\": \"Content was blocked due to safety settings\"}";
                } else if (finishReason.equals("MAX_TOKENS")) {
                    logger.warn("Response was truncated due to max tokens limit");
                    // Continue processing as we might have partial content
                } else {
                    return "{\"error\": \"Response generation stopped due to: " + finishReason + "\"}";
                }
            }
            
            // Extract content from candidate
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) firstCandidate.get("content");
            if (content == null) {
                logger.error("No content in first candidate from Gemini 2.5 Flash");
                return "{\"error\": \"No content in response candidate\"}";
            }
            
            // Extract parts from content
            Object partsObj = content.get("parts");
            if (!(partsObj instanceof List)) {
                logger.error("No parts array in content from Gemini 2.5 Flash");
                return "{\"error\": \"Invalid content format: missing parts\"}";
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) partsObj;
            
            if (parts.isEmpty()) {
                logger.error("Empty parts array in content from Gemini 2.5 Flash");
                return "{\"error\": \"No text parts in response content\"}";
            }
            
            Map<String, Object> firstPart = parts.get(0);
            String text = (String) firstPart.get("text");
            
            if (text == null || text.trim().isEmpty()) {
                logger.error("Empty or null text in first part from Gemini 2.5 Flash");
                return "{\"error\": \"Empty text response from Gemini API\"}";
            }
            
            logger.info("Successfully received Gemini 2.5 Flash response with {} characters", text.length());
            return text.trim();
            
        } catch (HttpClientErrorException.TooManyRequests e) {
            // Try backup key if available and not already using it
            if (!useBackupKey && apiKeyBackup != null && !apiKeyBackup.trim().isEmpty()) {
                logger.warn("Gemini primary key rate limited, trying backup key");
                switchToBackupKey();
                return generateCompletionWithKey(prompt, getCurrentGeminiKey());
            }
            // Both keys exhausted
            logger.warn("All Gemini API keys rate limited. User will be shown a friendly error.");
            return "{\"error\": \"RATE_LIMIT_EXCEEDED\", \"message\": \"The daily analysis quota has been reached. Please try again tomorrow.\"}";
        } catch (HttpClientErrorException e) {
            // Try backup key for 4xx errors if available
            if (!useBackupKey && apiKeyBackup != null && !apiKeyBackup.trim().isEmpty()) {
                logger.warn("Gemini primary key failed with 4xx error, trying backup key");
                switchToBackupKey();
                return generateCompletionWithKey(prompt, getCurrentGeminiKey());
            }
            logger.error("HTTP 4xx Error calling Gemini 2.5 Flash API: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            String errorMessage = e.getResponseBodyAsString().replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            return "{\"error\": \"Gemini API client error (" + e.getStatusCode() + "): " + errorMessage + "\"}";
        } catch (HttpServerErrorException e) {
            // Try backup key for 5xx errors if available
            if (!useBackupKey && apiKeyBackup != null && !apiKeyBackup.trim().isEmpty()) {
                logger.warn("Gemini primary key failed with 5xx error, trying backup key");
                switchToBackupKey();
                return generateCompletionWithKey(prompt, getCurrentGeminiKey());
            }
            logger.error("HTTP 5xx Error calling Gemini 2.5 Flash API: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            String errorMessage = e.getResponseBodyAsString().replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            return "{\"error\": \"Gemini API server error (" + e.getStatusCode() + "): " + errorMessage + "\"}";
        } catch (Exception e) {
            // Try backup key for unexpected errors if available
            if (!useBackupKey && apiKeyBackup != null && !apiKeyBackup.trim().isEmpty()) {
                logger.warn("Gemini primary key failed with unexpected error, trying backup key");
                switchToBackupKey();
                return generateCompletionWithKey(prompt, getCurrentGeminiKey());
            }
            logger.error("Unexpected error calling Gemini 2.5 Flash API: {}", e.getMessage(), e);
            String errorMessage = e.getMessage().replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            return "{\"error\": \"Failed to analyze article with Gemini 2.5 Flash: " + errorMessage + "\"}";
        }
    }
}

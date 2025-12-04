package com.perplexity.newsaggregator.dto;

import java.util.List;
import lombok.Data;

@Data
public class StructuredBiasAnalysis {
    private String biasLevel;              // Low | Medium | High | Neutral
    private String confidence;             // High | Medium | Low
    private String reasonForBias;          // 2-3 lines: Why this bias level was assigned
    private String xaiJustification;      // 2-3 lines: XAI - How AI made this decision
    private List<String> missingContext;   // Max 3 bullet points
    private String balancedPerspective;    // 2-3 lines: More balanced view
    private String selfReflection;        // 2-3 lines: AI's self-assessment of potential errors
}

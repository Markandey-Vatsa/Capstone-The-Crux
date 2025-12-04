package com.perplexity.newsaggregator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GuardianApiResponse {
    private Response response;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        private String status;
        private int pageSize;
        private List<Result> results;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Result {
        private String id;
        private String webTitle;
        private String webPublicationDate; // ISO8601
        private String webUrl;
        private Blocks blocks; // Requires show-blocks=body in query
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Blocks {
        private List<Body> body;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Body {
        private String id;
        private String bodyHtml; // full HTML
        private String bodyTextSummary; // sometimes present
    }
}

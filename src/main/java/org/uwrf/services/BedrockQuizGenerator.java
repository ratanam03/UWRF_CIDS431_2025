package org.uwrf.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/**
 * Real implementation of {@link QuizGenerator} that calls AWS Bedrock (Claude 3 Haiku).
 *
 * <p>This makes an actual API call to Bedrock and will incur token costs.
 * Use {@link MockQuizGenerator} during development and switch to this class
 * only when you are ready to test with real AI-generated questions.
 *
 * <p>Requires the Lambda execution role to have {@code bedrock:InvokeModel} permission.
 */
public class BedrockQuizGenerator implements QuizGenerator {

    private static final String MODEL_ID = "amazon.nova-lite-v1:0";
    private final BedrockRuntimeClient bedrockClient;
    private final ObjectMapper objectMapper;

    public BedrockQuizGenerator() {
        this.bedrockClient = BedrockRuntimeClient.create();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String generateQuiz(String transcript) throws Exception {
        System.out.println("Calling Bedrock (" + MODEL_ID + ") to generate quiz questions...");

        String prompt = "Generate 10 multiple choice questions based on this lecture transcript. " +
                "Each question should have 4 options (A, B, C, D) with one correct answer. " +
                "Return ONLY a JSON array with this exact format:\n" +
                "[\n" +
                "  {\n" +
                "    \"question\": \"What is...?\",\n" +
                "    \"options\": {\n" +
                "      \"A\": \"First option\",\n" +
                "      \"B\": \"Second option\",\n" +
                "      \"C\": \"Third option\",\n" +
                "      \"D\": \"Fourth option\"\n" +
                "    },\n" +
                "    \"correctAnswer\": \"A\"\n" +
                "  }\n" +
                "]\n\nTranscript:\n" + transcript;

        // Nova request format
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", "user");
        message.putArray("content").addObject().put("text", prompt);

        ObjectNode bedrockRequest = objectMapper.createObjectNode();
        bedrockRequest.putArray("messages").add(message);
        bedrockRequest.putObject("inferenceConfig")
                .put("maxTokens", 4096)
                .put("temperature", 0.3);

        InvokeModelResponse bedrockResponse = bedrockClient.invokeModel(InvokeModelRequest.builder()
                .modelId(MODEL_ID)
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(bedrockRequest)))
                .build());

        // Nova response format
        String responseBody = bedrockResponse.body().asUtf8String();
        JsonNode responseNode = objectMapper.readTree(responseBody);
        String quizText = responseNode.get("output").get("message").get("content").get(0).get("text").asText();

        // Strip markdown fences if present
        quizText = quizText.trim();
        if (quizText.startsWith("```")) {
            int firstNewline = quizText.indexOf('\n');
            quizText = quizText.substring(firstNewline + 1);
            if (quizText.endsWith("```")) {
                quizText = quizText.substring(0, quizText.lastIndexOf("```"));
            }
            quizText = quizText.trim();
        }

        return quizText;
    }
}

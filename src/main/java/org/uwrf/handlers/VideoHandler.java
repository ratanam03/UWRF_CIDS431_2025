package org.uwrf.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.uwrf.services.BedrockQuizGenerator;
import org.uwrf.services.MockQuizGenerator;
import org.uwrf.services.QuizGenerator;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.time.Instant;

public class VideoHandler implements RequestHandler<S3Event, String> {

    private final QuizGenerator quizGenerator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VideoHandler() {
        this("true".equalsIgnoreCase(System.getenv("MOCK_BEDROCK"))
                ? new MockQuizGenerator()
                : new BedrockQuizGenerator());
    }

    VideoHandler(QuizGenerator quizGenerator) {
        this.quizGenerator = quizGenerator;
    }

    private boolean isMockMode() {
        return !"false".equalsIgnoreCase(System.getenv("MOCK_BEDROCK"));
    }

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        System.out.println("=== Lambda Function Triggered ===");
        System.out.println("Received S3 event with " + s3Event.getRecords().size() + " record(s)");

        for (S3EventNotification.S3EventNotificationRecord record : s3Event.getRecords()) {
            String bucketName = record.getS3().getBucket().getName();
            String objectKey  = record.getS3().getObject().getKey();
            long   objectSize = record.getS3().getObject().getSizeAsLong();
            String eventName  = record.getEventName();

            System.out.println("--- S3 Event Details ---");
            System.out.println("Event Type: " + eventName);
            System.out.println("Bucket:     " + bucketName);
            System.out.println("File:       " + objectKey);
            System.out.println("Size:       " + objectSize + " bytes");
            System.out.println("Event Time: " + record.getEventTime());
            System.out.println("------------------------");

            try {
                processVideo(bucketName, objectKey);
            } catch (Exception e) {
                System.err.println("Failed to process transcript: " + objectKey);
                e.printStackTrace();
                throw new RuntimeException("Failed to process transcript: " + objectKey, e);
            }
        }

        return "Processed " + s3Event.getRecords().size() + " record(s)";
    }

    private void processVideo(String bucketName, String objectKey) throws Exception {

        System.out.println("Reading transcript: " + objectKey);
        String transcriptText = getTranscript(bucketName, objectKey);
        System.out.println("Transcript length: " + transcriptText.length() + " chars");

        // Derive quiz name from transcript filename
        String fileName = objectKey.contains("/")
                ? objectKey.substring(objectKey.lastIndexOf("/") + 1)
                : objectKey;
        String baseName = fileName.replace(".json", "");

        System.out.println("Generating quiz from transcript...");
        String quizJsonArray = this.quizGenerator.generateQuiz(transcriptText);
        System.out.println("Quiz generated successfully.");

        String fullQuizJson = buildQuizEnvelope(objectKey, quizJsonArray);

        String quizKey = "quizzes/" + baseName + "-quiz.json";
        saveQuizToS3(bucketName, quizKey, fullQuizJson);

        System.out.println("=== Pipeline Complete ===");
    }

    private String getTranscript(String bucketName, String objectKey) throws Exception {
        if (isMockMode()) {
            System.out.println("[MOCK] Skipping real read - returning fake transcript");
            return "This is a fake transcript used for local testing. " +
                    "It covers topics like AWS Lambda, S3 buckets, cloud architecture, " +
                    "serverless computing, and infrastructure as code with AWS CDK.";
        }

        System.out.println("Reading transcript from S3: " + objectKey);
        S3Client s3Client = S3Client.create();
        ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(bucketName)
                        .key(objectKey)
                        .build()
        );

        try (InputStream inputStream = s3Object) {
            JsonNode root = objectMapper.readTree(inputStream);
            String transcript = root
                    .get("results")
                    .get("transcripts")
                    .get(0)
                    .get("transcript")
                    .asText();
            System.out.println("Transcript read successfully. Length: " + transcript.length() + " chars");
            return transcript;
        }
    }

    private String buildQuizEnvelope(String objectKey, String quizJsonArray) throws Exception {
        JsonNode questionsNode = objectMapper.readTree(quizJsonArray);

        com.fasterxml.jackson.databind.node.ObjectNode envelope =
                objectMapper.createObjectNode();
        envelope.put("sourceTranscript", objectKey);
        envelope.put("generatedAt", Instant.now().toString());
        envelope.set("questions", questionsNode);

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(envelope);
    }

    private void saveQuizToS3(String bucketName, String quizKey, String quizJson) {
        if (isMockMode()) {
            System.out.println("[MOCK] Skipping real S3 save - quiz would be written to: "
                    + "s3://" + bucketName + "/" + quizKey);
            System.out.println("[MOCK] Quiz preview:\n" + quizJson.substring(0, Math.min(300, quizJson.length())) + "...");
            return;
        }

        byte[] bytes = quizJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        S3Client s3Client = S3Client.create();
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(quizKey)
                        .contentType("application/json")
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes)
        );
        System.out.println("Quiz saved to: s3://" + bucketName + "/" + quizKey);
    }
}
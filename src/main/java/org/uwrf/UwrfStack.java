package org.uwrf;

import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.NotificationKeyFilter;
import software.amazon.awscdk.services.s3.notifications.LambdaDestination;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class UwrfStack extends Stack {
    private final String studentName;

    public UwrfStack(final Construct scope, final String id, final String studentName) {
        this(scope, id, null, studentName);
    }

    public UwrfStack(final Construct scope, final String id, final StackProps props, final String studentName) {
        super(scope, id, props);
        this.studentName = studentName;

        // Create the Lambda function
        Function videoHandler = Function.Builder.create(this, "VideoHandler")
                .functionName(studentName + "-video-handler")
                .runtime(Runtime.JAVA_21)
                .handler("org.uwrf.handlers.VideoHandler::handleRequest")
                .code(Code.fromAsset("target/lambda.jar"))
                .memorySize(512)
                .timeout(Duration.minutes(5))
                .description("Processes video uploads and generates quizzes")
                .environment(Map.of("MOCK_BEDROCK", "false"))
                .build();

        // Create the S3 bucket
        Bucket videoBucket = Bucket.Builder.create(this, "VideoBucket")
                .bucketName(studentName + "-quiz-generator")
                .build();

        // Trigger Lambda when a .mp4 file is uploaded to the bucket
        videoBucket.addEventNotification(
                EventType.OBJECT_CREATED,
                new LambdaDestination(videoHandler),
                NotificationKeyFilter.builder()
                        .prefix("transcripts/")
                        .suffix(".json")
                        .build()
        );

        // Grant Lambda permission to read and write to the S3 bucket
        videoBucket.grantReadWrite(videoHandler);

        // Grant Lambda permission to call Bedrock (AI quiz generation)
        videoHandler.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "bedrock:InvokeModel",
                        "bedrock:InvokeModelWithResponseStream"
                ))
                .resources(List.of("*"))
                .build());

        // Grant Lambda permission to call Transcribe (speech to text)
        videoHandler.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "transcribe:StartTranscriptionJob",
                        "transcribe:GetTranscriptionJob"
                ))
                .resources(List.of("*"))
                .build());

        // Grant Lambda permission to view AWS Marketplace subscriptions
        videoHandler.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "aws-marketplace:ViewSubscriptions",
                        "aws-marketplace:Subscribe"
                ))
                .resources(List.of("*"))
                .build());
    }
}
package com.jcore;

import software.amazon.awscdk.services.codebuild.CfnProject;
import software.amazon.awscdk.services.codepipeline.CfnPipeline;
import software.amazon.awscdk.services.ecr.CfnRepository;
import software.amazon.awscdk.services.iam.CfnPolicy;
import software.amazon.awscdk.services.iam.CfnRole;
import software.amazon.awscdk.services.s3.Bucket;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class AwsPipelineService {
    private final Construct scope;
    private final String prefix;

    public AwsPipelineService(Construct scope, String prefix) {
        this.scope = scope;
        this.prefix = prefix;
    }

    public CfnRepository createRepository() {
        // Define the repository
        return CfnRepository.Builder.create(scope, prefix + "elastische-repositorium")
                .repositoryName("sebas-cdk-repository")
                .imageScanningConfiguration(
                        CfnRepository.ImageScanningConfigurationProperty.builder()
                                .scanOnPush(true)
                                .build()
                )
                .lifecyclePolicy(
                        CfnRepository.LifecyclePolicyProperty.builder()
                                .lifecyclePolicyText("{\n" +
                                        "  \"rules\": [\n" +
                                        "    {\n" +
                                        "      \"rulePriority\": 1,\n" +
                                        "      \"description\": \"Keep last 5 prod images\",\n" +
                                        "      \"selection\": {\n" +
                                        "        \"tagStatus\": \"tagged\",\n" +
                                        "        \"tagPrefixList\": [\"prod\"],\n" +
                                        "        \"countType\": \"imageCountMoreThan\",\n" +
                                        "        \"countNumber\": 5\n" +
                                        "      },\n" +
                                        "      \"action\": {\"type\": \"expire\"}\n" +
                                        "    }\n" +
                                        "  ]\n" +
                                        "}")
                                .build()
                )
                .build();
    }

    public CfnProject createPipeline(String accountNR, String region, CfnRepository repository) {
        //Create artifact bucket for CodePipeline
        Bucket artifactBucket = Bucket.Builder.create(scope, prefix + "pipeline-artifacts").build();

        var codeBuildRole = createCodeBuildRole();
        //Define CodeBuild project (builds Docker image + pushes to ECR)
        CfnProject buildProject = CfnProject.Builder.create(scope, prefix + "cdk-pipeline-project")
                .name("pipeline-project-sebas")
                .source(CfnProject.SourceProperty.builder()
                        .type("CODEPIPELINE") // Input comes from CodePipeline
                        .build())
                .artifacts(CfnProject.ArtifactsProperty.builder()
                        .type("CODEPIPELINE")
                        .build())
                .environment(CfnProject.EnvironmentProperty.builder()
                        .computeType("BUILD_GENERAL1_SMALL")
                        .image("aws/codebuild/standard:7.0") // Ubuntu + Docker preinstalled
                        .type("LINUX_CONTAINER")
                        .privilegedMode(true)
                        .environmentVariables(List.of(
                                createEnv("IMAGE_REPO_NAME", repository.getRepositoryName()),
                                createEnv("AWS_ACCOUNT_ID", accountNR),
                                createEnv("IMAGE_TAG", "latest"),
                                createEnv("AWS_DEFAULT_REGION", region)
                        ))
                        .build())
                .serviceRole(codeBuildRole.getAttrArn())
                .build();

        // 🔹 Step 4: Define CodePipeline (with Source + Build stages)
        var codePipelineRole = createCodePipelineRole();
        CfnPipeline pipeline = CfnPipeline.Builder.create(scope, prefix + "messenger-pipeline")
                .roleArn(codePipelineRole.getAttrArn())
                .artifactStore(CfnPipeline.ArtifactStoreProperty.builder()
                        .type("S3")
                        .location(artifactBucket.getBucketName())
                        .build())
                .stages(List.of(
                        // Stage 1: Source (GitHub example)
                        CfnPipeline.StageDeclarationProperty.builder()
                                .name("Source")
                                .actions(List.of(
                                        CfnPipeline.ActionDeclarationProperty.builder()
                                                .name("GitHubSource")
                                                .actionTypeId(CfnPipeline.ActionTypeIdProperty.builder()
                                                        .category("Source")
                                                        .owner("ThirdParty")
                                                        .provider("GitHub")
                                                        .version("1")
                                                        .build())
                                                .configuration(Map.of(
                                                        "Owner", "{{resolve:secretsmanager:Github-access-Sebas:SecretString:Owner}}",
                                                        "Repo", "{{resolve:secretsmanager:Github-access-Sebas:SecretString:Repo}}",
                                                        "Branch", "{{resolve:secretsmanager:Github-access-Sebas:SecretString:Branch}}",
                                                        "OAuthToken", "{{resolve:secretsmanager:Github-access-Sebas:SecretString:OAuthToken}}"
                                                ))
                                                .outputArtifacts(List.of(
                                                        CfnPipeline.OutputArtifactProperty.builder()
                                                                .name("SourceOutput")
                                                                .build()
                                                ))
                                                .runOrder(1)
                                                .build()
                                ))
                                .build(),

                        // Stage 2: Build (CodeBuild project)
                        CfnPipeline.StageDeclarationProperty.builder()
                                .name("Build")
                                .actions(List.of(
                                        CfnPipeline.ActionDeclarationProperty.builder()
                                                .name("DockerBuild")
                                                .actionTypeId(CfnPipeline.ActionTypeIdProperty.builder()
                                                        .category("Build")
                                                        .owner("AWS")
                                                        .provider("CodeBuild")
                                                        .version("1")
                                                        .build())
                                                .inputArtifacts(List.of(
                                                        CfnPipeline.InputArtifactProperty.builder()
                                                                .name("SourceOutput")
                                                                .build()
                                                ))
                                                .configuration(Map.of(
                                                        "ProjectName", buildProject.getName()
                                                ))
                                                .outputArtifacts(List.of(
                                                        CfnPipeline.OutputArtifactProperty.builder()
                                                                .name("BuildOutput")
                                                                .build()
                                                ))
                                                .runOrder(1)
                                                .build()
                                ))
                                .build()
                ))
                .build();
        return buildProject;
    }

    private CfnRole createCodeBuildRole() {
        CfnRole codeBuildRole = createRole(prefix + "codebuild-role", "codebuild.amazonaws.com");

        var policies = List.of(
                Map.of(
                        "Effect", "Allow",
                        "Action", List.of(
                                "ecr:GetAuthorizationToken",
                                "ecr:BatchCheckLayerAvailability",
                                "ecr:CompleteLayerUpload",
                                "ecr:InitiateLayerUpload",
                                "ecr:PutImage",
                                "ecr:UploadLayerPart"
                        ),
                        "Resource", "*"
                ),
                Map.of(
                        "Effect", "Allow",
                        "Action", List.of(
                                "s3:GetObject",
                                "s3:PutObject",
                                "s3:GetObjectVersion"
                        ),
                        "Resource", "*"
                ),
                Map.of(
                        "Effect", "Allow",
                        "Action", List.of(
                                "logs:CreateLogGroup",
                                "logs:CreateLogStream",
                                "logs:PutLogEvents"
                        ),
                        "Resource", "*"
                )
        );
        createPolicy(prefix + "codebuild-policy", codeBuildRole, policies);
        return codeBuildRole;
    }

    private CfnRole createCodePipelineRole() {
        CfnRole codePipelineRole = createRole(prefix + "codepipeline-role", "codepipeline.amazonaws.com");

        var policies = List.of(
                Map.of(
                        "Effect", "Allow",
                        "Action", List.of(
                                "codebuild:StartBuild",
                                "codebuild:BatchGetBuilds"
                        ),
                        "Resource", "*"
                ),
                Map.of(
                        "Effect", "Allow",
                        "Action", List.of(
                                "s3:GetObject",
                                "s3:PutObject",
                                "s3:GetObjectVersion"
                        ),
                        "Resource", "*"
                ),
                Map.of(
                        "Effect", "Allow",
                        "Action", List.of(
                                "iam:PassRole"
                        ),
                        "Resource", "*"
                )
        );
        createPolicy(prefix + "codepipeline-policy", codePipelineRole, policies);

        return codePipelineRole;
    }

    private CfnRole createRole(String name, String service) {
        return CfnRole.Builder.create(scope, name)
                .roleName(name)
                .assumeRolePolicyDocument(Map.of(
                        "Version", "2012-10-17",
                        "Statement", List.of(
                                Map.of(
                                        "Effect", "Allow",
                                        "Principal", Map.of("Service", service),
                                        "Action", "sts:AssumeRole"
                                )
                        )
                ))
                .build();
    }

    private CfnPolicy createPolicy(String name, CfnRole role, List<Map<String, Object>> policies) {
        return CfnPolicy.Builder.create(scope, name)
                .policyName(name)
                .roles(List.of(role.getRef()))
                .policyDocument(Map.of(
                        "Version", "2012-10-17",
                        "Statement", policies
                ))
                .build();
    }

    private CfnProject.EnvironmentVariableProperty createEnv(String key, String value) {
        return CfnProject.EnvironmentVariableProperty.builder()
                .name(key)
                .value(value)
                .type("PLAINTEXT")
                .build();
    }

}

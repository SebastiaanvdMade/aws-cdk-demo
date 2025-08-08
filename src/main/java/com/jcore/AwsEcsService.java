package com.jcore;

import software.amazon.awscdk.services.ecs.CfnCluster;
import software.amazon.awscdk.services.ecs.CfnService;
import software.amazon.awscdk.services.ecs.CfnTaskDefinition;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnListenerRule;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnTargetGroup;
import software.amazon.awscdk.services.iam.CfnRole;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

public class AwsEcsService {
    private final Construct scope;
    private final String prefix;

    private final String CONTAINER_NAME = "sebastiaans-coole-messenger-container";

    public AwsEcsService(Construct scope, String prefix) {
        this.scope = scope;
        this.prefix = prefix;
    }

    public CfnCluster createCluster() {
        return CfnCluster.Builder
                .create(scope, prefix + "fargate-cluster")
                .clusterName("sebas-zn-fantastische-cdk-cluster")
                .capacityProviders(List.of("FARGATE", "FARGATE_SPOT"))
                .build();
    }

    public CfnTaskDefinition createMessengerService(String mode, int port, String snsTopic, String sqsQueue) {

        var taskRole = createTaskRole(mode);

        return CfnTaskDefinition.Builder
                .create(scope, "%smessenger-%s-service-taskdef".formatted(prefix, mode))
                .runtimePlatform(
                        CfnTaskDefinition.RuntimePlatformProperty.builder()
                                .cpuArchitecture("X86_64")
                                .operatingSystemFamily("LINUX")
                                .build()
                )
                .containerDefinitions(List.of(
                        CfnTaskDefinition.ContainerDefinitionProperty.builder()
                                .name(CONTAINER_NAME)
                                .image("039612879714.dkr.ecr.eu-central-1.amazonaws.com/sebastiaan/messenger:latest")
                                .cpu(256)
                                .memory(1024)
                                .essential(true)
                                .environment(List.of(
                                        CfnTaskDefinition.KeyValuePairProperty.builder()
                                                .name("SPRING_PROFILES_INCLUDE")
                                                .value("nodb,%s".formatted(mode))
                                                .build(),
                                        CfnTaskDefinition.KeyValuePairProperty.builder()
                                                .name("SERVER_PORT")
                                                .value(String.valueOf(port))
                                                .build(),
                                        CfnTaskDefinition.KeyValuePairProperty.builder()
                                                .name("SERVER_SERVLET_CONTEXT-PATH")
                                                .value("/" + mode)
                                                .build(),
                                        CfnTaskDefinition.KeyValuePairProperty.builder()
                                                .name("AWS_SNSTOPIC")
                                                .value(snsTopic)
                                                .build(),
                                        CfnTaskDefinition.KeyValuePairProperty.builder()
                                                .name("AWS_SQSQUEUE")
                                                .value(sqsQueue)
                                                .build()
                                ))
                                .portMappings(List.of(
                                        CfnTaskDefinition.PortMappingProperty.builder()
                                                .hostPort(port)
                                                .name(String.valueOf(port))
                                                .containerPort(port)
                                                .protocol("tcp")
                                                .build()
                                ))
                                .logConfiguration(createLogConfiguration("sebas-cdk-messenger-service-" + mode))
                                .build()
                ))
                .requiresCompatibilities(List.of("FARGATE"))
                .cpu("256")
                .memory("1024")
                .networkMode("awsvpc")
                .taskRoleArn(taskRole.getAttrArn())
                .executionRoleArn(taskRole.getAttrArn())
                .build();
    }

    private CfnRole createTaskRole(String mode) {
        return CfnRole.Builder
                .create(scope, "%stask-role-for-%s-service".formatted(prefix, mode))
                .managedPolicyArns(List.of(
                        "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy",
                        "arn:aws:iam::aws:policy/AmazonSNSFullAccess",
                        "arn:aws:iam::aws:policy/AmazonSQSFullAccess",
                        "arn:aws:iam::aws:policy/AmazonSQSReadOnlyAccess",
                        "arn:aws:iam::aws:policy/service-role/AWSIoTLogging"
                ))
                .assumeRolePolicyDocument(
                        Map.of(
                                "Version", "2012-10-17",
                                "Statement", List.of(Map.of(
                                                "Effect", "Allow",
                                                "Principal", Map.of(
                                                        "Service", List.of("ecs-tasks.amazonaws.com")
                                                ),
                                                "Action", List.of("sts:AssumeRole")
                                        )
                                )
                        )
                ).build();
    }

    private CfnTaskDefinition.LogConfigurationProperty createLogConfiguration(String name) {
        return CfnTaskDefinition.LogConfigurationProperty.builder().
                logDriver("awslogs").
                options(
                        Map.ofEntries(
                                Map.entry("awslogs-group", "/ecs/" + name),
                                Map.entry("mode", "non-blocking"),
                                Map.entry("awslogs-create-group", "true"),
                                Map.entry("max-buffer-size", "25m"),
                                Map.entry("awslogs-region", "eu-central-1"),
                                Map.entry("awslogs-stream-prefix", "ecs")
                        )
                ).
                build();

    }

    public CfnService createService(
            String cluster,
            String targetGroup,
            String securityGroup,
            List<String> subnets,
            String mode,
            int port,
            String snsTopic,
            String sqsQueue
    ) {

        var taskDefinition = createMessengerService(mode, port, snsTopic, sqsQueue);
        return CfnService.Builder
                .create(scope, "%smessenger-%s-servies".formatted(prefix, mode))
                .taskDefinition(taskDefinition.getAttrTaskDefinitionArn())
                .loadBalancers(List.of(
                        CfnService.LoadBalancerProperty.builder()
                                .containerName(CONTAINER_NAME)
                                .containerPort(port)
                                .targetGroupArn(targetGroup)
                                .build())
                )
                .serviceName("cool-messenger-service-%smode".formatted(mode))
                .networkConfiguration(CfnService.NetworkConfigurationProperty.builder()
                        .awsvpcConfiguration(CfnService.AwsVpcConfigurationProperty.builder()
                                .securityGroups(List.of(securityGroup))
                                .subnets(subnets)
                                .assignPublicIp("DISABLED")
                                .build())
                        .build()
                )
                .desiredCount(1)
                .cluster(cluster)
                .launchType("FARGATE")
                .platformVersion("LATEST")
                .build();
    }

    public CfnListener createListener(String loadBalancer, int port) {
        return CfnListener.Builder
                .create(scope, prefix + "HTTP-listener")
                .port(port)
                .loadBalancerArn(loadBalancer)
                .protocol("HTTP")
                .defaultActions(List.of(CfnListener.ActionProperty.builder()
                        .type("fixed-response")
                        .fixedResponseConfig(CfnListener.FixedResponseConfigProperty.builder()
                                .statusCode("404")
                                .contentType("text/html")
                                .messageBody("""
                                        <h3>Helaas pindakaas, je prinses is in een ander kasteel.</h3>
                                        <p>(probeer eens /send of /receive)</p>
                                        """)
                                .build())
                        .build()
                ))
                .build();
    }

    public CfnListenerRule createListenerRule(String listener, String targetGroup, String mode, int prio) {
        return CfnListenerRule.Builder
                .create(scope, "%slistener-rule-%s".formatted(prefix, mode))
                .priority(prio)
                .listenerArn(listener)
                .actions(List.of(CfnListenerRule.ActionProperty.builder()
                        .type("forward")
                        .targetGroupArn(targetGroup)
                        .build())
                ).conditions(List.of(createPathPatternRule(List.of("/%s*".formatted(mode)))))
                .build();
    }

    public CfnListenerRule createLoading(String listener, int prio) {
        return CfnListenerRule.Builder
                .create(scope, "%slistener-loading-rule".formatted(prefix))
                .priority(prio)
                .listenerArn(listener)
                .actions(List.of(CfnListenerRule.ActionProperty.builder()
                        .type("fixed-response")
                        .fixedResponseConfig(CfnListenerRule.FixedResponseConfigProperty.builder()
                                .statusCode("404")
                                .contentType("text/html")
                                .messageBody("""
                                        <h3>Sorry, we zijn nog niet zo ver...</h3>
                                        <p>De pagina is nog aan het laden</p>
                                        """)
                                .build()
                        ).build())
                ).conditions(List.of(
                        createPathPatternRule(List.of("/send*", "/receive*"))
                )).build();
    }

    private CfnListenerRule.RuleConditionProperty createPathPatternRule(List<String> paths) {
        return CfnListenerRule.RuleConditionProperty.builder()
                .field("path-pattern")
                .pathPatternConfig(CfnListenerRule.PathPatternConfigProperty.builder()
                        .values(paths)
                        .build()
                ).build();
    }

    public CfnTargetGroup createTargetGroup(String vpc, String mode, int port) {
        return CfnTargetGroup.Builder
                .create(scope, prefix + "target-group-" + mode)
                .name(mode + "-doelwit")
                .targetType("ip")
                .ipAddressType("ipv4")
                .port(port)
                .protocol("HTTP")
                .vpcId(vpc)
                .healthCheckEnabled(true)
                .healthCheckProtocol("HTTP")
                .healthCheckPath("/%s/api/v1/messenger/healthcheck".formatted(mode))
                .healthCheckIntervalSeconds(60)
                .unhealthyThresholdCount(5)
                .healthyThresholdCount(2)
                .build();
    }
}

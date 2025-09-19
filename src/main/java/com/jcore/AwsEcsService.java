package com.jcore;

import com.jcore.model.ServiceSettings;
import software.amazon.awscdk.services.ecs.CfnCluster;
import software.amazon.awscdk.services.ecs.CfnService;
import software.amazon.awscdk.services.ecs.CfnTaskDefinition;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnListenerRule;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnTargetGroup;
import software.amazon.awscdk.services.iam.CfnRole;
import software.amazon.awscdk.services.secretsmanager.CfnSecret;
import software.constructs.Construct;

import java.util.HashMap;
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

    public CfnTaskDefinition createMessengerService(ServiceSettings settings) {

        var taskRole = createTaskRole(settings.getMode());

        Map<String, String> envVars = new HashMap<>();
        envVars.put("SPRING_PROFILES_INCLUDE", "aws,%s".formatted(settings.getMode()));
        envVars.put("SERVER_PORT", String.valueOf(settings.getPort()));
        envVars.put("SERVER_SERVLET_CONTEXT-PATH", "/%s".formatted(settings.getMode()));
        envVars.put("AWS_SNSTOPIC", settings.getSnsTopic());
        envVars.put("AWS_SQSQUEUE", settings.getSqsQueue());

        Map<String, CfnSecret> secrets = new HashMap<>();
        secrets.put("SPRING_DATA_MONGODB_URI", settings.getConnectionString());

        return CfnTaskDefinition.Builder
                .create(scope, "%smessenger-%s-service-taskdef".formatted(prefix, settings.getMode()))
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
                                .environment(createEnvironmentVariables(envVars))
                                .secrets(createSecretProperties(secrets))
                                .portMappings(List.of(
                                        CfnTaskDefinition.PortMappingProperty.builder()
                                                .hostPort(settings.getPort())
                                                .name(String.valueOf(settings.getPort()))
                                                .containerPort(settings.getPort())
                                                .protocol("tcp")
                                                .build()
                                ))
                                .logConfiguration(createLogConfiguration("sebas-cdk-messenger-service-" + settings.getMode()))
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

    private List<CfnTaskDefinition.KeyValuePairProperty> createEnvironmentVariables(Map<String, String> envVars) {
        return envVars.entrySet().stream()
                .map(entry ->
                        CfnTaskDefinition.KeyValuePairProperty.builder()
                                .name(entry.getKey())
                                .value(entry.getValue())
                                .build()
                ).toList();
    }

    private List<CfnTaskDefinition.SecretProperty> createSecretProperties(Map<String, CfnSecret> secrets) {
        return secrets.entrySet().stream()
                .map(entry ->
                        CfnTaskDefinition.SecretProperty.builder()
                                .name(entry.getKey())
                                .valueFrom(entry.getValue().getRef())
                                .build()
                ).toList();
    }

    private CfnRole createTaskRole(String mode) {
        return CfnRole.Builder
                .create(scope, "%stask-role-for-%s-service".formatted(prefix, mode))
                .managedPolicyArns(List.of(
                        "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy",
                        "arn:aws:iam::aws:policy/AmazonSNSFullAccess",
                        "arn:aws:iam::aws:policy/AmazonSQSFullAccess",
                        "arn:aws:iam::aws:policy/AmazonSQSReadOnlyAccess",
                        "arn:aws:iam::aws:policy/service-role/AWSIoTLogging",
                        "arn:aws:iam::aws:policy/SecretsManagerReadWrite"
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

    public CfnService createService(ServiceSettings settings) {

        var taskDefinition = createMessengerService(settings);
        return CfnService.Builder
                .create(scope, "%smessenger-%s-servies".formatted(prefix, settings.getMode()))
                .taskDefinition(taskDefinition.getAttrTaskDefinitionArn())
                .loadBalancers(List.of(
                        CfnService.LoadBalancerProperty.builder()
                                .containerName(CONTAINER_NAME)
                                .containerPort(settings.getPort())
                                .targetGroupArn(settings.getTargetGroup())
                                .build())
                )
                .serviceName("cool-messenger-service-%smode".formatted(settings.getMode()))
                .networkConfiguration(CfnService.NetworkConfigurationProperty.builder()
                        .awsvpcConfiguration(CfnService.AwsVpcConfigurationProperty.builder()
                                .securityGroups(List.of(settings.getSecurityGroup()))
                                .subnets(settings.getSubnets())
                                .assignPublicIp("DISABLED")
                                .build())
                        .build()
                )
                .desiredCount(1)
                .cluster(settings.getCluster())
                .launchType("FARGATE")
                .platformVersion("LATEST")
                .build();
    }

    public CfnListener createALBListener(String loadBalancer, int port) {
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

    public CfnListener createNLBListener(String loadBalancer, String targetGroup, int port) {
        return CfnListener.Builder
                .create(scope, prefix + "TCP-listener")
                .port(port)
                .loadBalancerArn(loadBalancer)
                .protocol("TCP")
                .defaultActions(List.of(CfnListener.ActionProperty.builder()
                        .type("forward")
                        .targetGroupArn(targetGroup)
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

    public CfnTargetGroup createTargetGroup(String vpc, String mode, int port, List<String> loadBalancers) {
        var targetType = loadBalancers.isEmpty() ? "ip" : "alb";
        var name = loadBalancers.isEmpty() ? mode : "to-balancer";
        var protocol = loadBalancers.isEmpty() ? "HTTP" : "TCP";
        return CfnTargetGroup.Builder
                .create(scope, prefix + "target-group-" + name)
                .name(name + "-doelwit")
                .targetType(targetType)
                .ipAddressType("ipv4")
                .port(port)
                .protocol(protocol)
                .vpcId(vpc)
                .healthCheckEnabled(true)
                .healthCheckProtocol("HTTP")
                .healthCheckPath("/%s/api/v1/messenger/healthcheck".formatted(mode))
                .healthCheckIntervalSeconds(60)
                .unhealthyThresholdCount(5)
                .healthyThresholdCount(2)
                .targets(loadBalancers.stream().map(balancerRef ->
                        CfnTargetGroup.TargetDescriptionProperty.builder()
                                .id(balancerRef)
                                .port(port)
                                .build()).toList())
                .build();
    }
}

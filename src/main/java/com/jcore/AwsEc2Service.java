package com.jcore;

import software.amazon.awscdk.CfnTag;
import software.amazon.awscdk.services.ec2.CfnInstance;
import software.amazon.awscdk.services.ec2.CfnSecurityGroup;
import software.amazon.awscdk.services.ec2.CfnSecurityGroupEgress;
import software.amazon.awscdk.services.ec2.CfnSecurityGroupIngress;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnTargetGroup;
import software.amazon.awscdk.services.iam.CfnRole;
import software.constructs.Construct;

import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AwsEc2Service {

    private final Construct scope;
    private final String prefix;

    public AwsEc2Service(Construct scope, String prefix) {
        this.scope = scope;
        this.prefix = prefix;
    }

    public CfnRole getCnfRole() {
        return CfnRole.Builder.create(scope, prefix + "IAMRole")
                .assumeRolePolicyDocument(createAssumeRolePolicy())
                .build();
    }

    private Map<String, Object> createAssumeRolePolicy() {
        var principal = new HashMap<String, Object>();
        principal.put("Service", "ec2.amazonaws.com");

        var statement = new HashMap<String, Object>();
        statement.put("Effect", "Allow");
        statement.put("Principal", principal);
        statement.put("Action", "sts:AssumeRole");

        var assumeRolePolicy = new HashMap<String, Object>();
        assumeRolePolicy.put("Version", "2012-10-17");
        assumeRolePolicy.put("Statement", Collections.singletonList(statement));

        return assumeRolePolicy;
    }

    public CfnLoadBalancer createLoadBalancer(String vpcId, List<String> subnets, String securityGroup) {
        //var targetGroup = createTargetGroup(vpcId);

        var balancer = CfnLoadBalancer.Builder.create(scope, prefix + "balancer")
                .name("balanceer-monsieur")
                .type("application")
                .subnets(subnets)
                .securityGroups(List.of(securityGroup))
                .scheme("internal")
                .build();

        return balancer;
    }

    private CfnListener createCfnListener(String targetGroupArn) {
        return CfnListener.Builder.create(scope, prefix + "listener")
                .defaultActions(List.of(
                        CfnListener.ActionProperty.builder()
                                .type("forward")
                                .targetGroupArn(targetGroupArn)
                                .build()
                ))
                .port(80)
                .protocol("HTTP")
                .build();
    }

    private CfnTargetGroup createTargetGroup(String vpcId) {
        return CfnTargetGroup.Builder.create(scope, prefix + "targetgroup")
                .vpcId(vpcId)
                .protocol("HTTP")
                .port(80)
                .targetType("instance") // or "ip" or "lambda"
                .healthCheckEnabled(true)
                .build();
    }

    public CfnSecurityGroup createSecurityGroup(String vpcId, String description) {
        var securityGroup = CfnSecurityGroup.Builder.create(scope, String.format("%s-%s-%s", prefix, description, "sg"))
                .groupDescription(description)
                .vpcId(vpcId)
                .build();
        makeInboudRule(securityGroup.getAttrId(), "0.0.0.0/0", 80, description + "-http");
        makeInboudRule(securityGroup.getAttrId(), "0.0.0.0/0", 22, description + "-ssh");
        makeOutboundRule(securityGroup.getAttrId(), description);

        return securityGroup;
    }

    private CfnSecurityGroupIngress makeInboudRule(String sgId, String cidrIp, Number port, String description) {
        return CfnSecurityGroupIngress.Builder.create(scope, prefix + description + "-rule")
                .description(description)
                .cidrIp(cidrIp)
                .ipProtocol("TCP")
                .fromPort(port)
                .toPort(port)
                .groupId(sgId)
                .build();
    }

    private CfnSecurityGroupEgress makeOutboundRule(String sgId, String description) {
        return CfnSecurityGroupEgress.Builder.create(scope, prefix + description + "-outbound-rule")
                .groupId(sgId)
                .ipProtocol("ALL")
                .cidrIp("0.0.0.0/0")
                .description("outbound")
                .build();
    }

    public CfnInstance createNginxInstance(String subnetId, String name, String securityGroupId, String balancerHostname) {
        String userData =
                String.format(
                        """
                                #!/bin/bash\\n
                                echo 'export ALB_HOST=%s' >> ~/.bash_profile\\n
                                source ~/.bash_profile\\n
                                sudo nginx -s reload
                                """,
                        balancerHostname
                );

        return CfnInstance.Builder.create(scope, prefix + name + "-instance")
                .tags(List.of(CfnTag.builder().key("Name").value(prefix + "NGINX").build()))
                .subnetId(subnetId)
                .instanceType("t2.micro")
                .imageId("ami-0904c2bf402fc81cc")
                .keyName("authSebas")
                .securityGroupIds(List.of(securityGroupId))
                .userData(Base64.getEncoder().encodeToString(userData.getBytes()))
                .build();
    }

}

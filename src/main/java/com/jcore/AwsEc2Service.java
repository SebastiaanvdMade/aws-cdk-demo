package com.jcore;

import software.amazon.awscdk.CfnTag;
import software.amazon.awscdk.services.ec2.CfnInstance;
import software.amazon.awscdk.services.ec2.CfnSecurityGroup;
import software.amazon.awscdk.services.ec2.CfnSecurityGroupEgress;
import software.amazon.awscdk.services.ec2.CfnSecurityGroupIngress;
import software.amazon.awscdk.services.elasticloadbalancingv2.CfnLoadBalancer;
import software.amazon.awscdk.services.iam.CfnInstanceProfile;
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

    private CfnInstanceProfile getInstanceProfile() {
        var role = CfnRole.Builder.create(scope, prefix + "IAMRole")
                .assumeRolePolicyDocument(createAssumeRolePolicy())
                .managedPolicyArns(List.of("arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess"))
                .build();

        return CfnInstanceProfile.Builder
                .create(scope, prefix + "nginx-instance-profile")
                .roles(List.of(role.getRef()))
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

    public CfnLoadBalancer createLoadBalancer(List<String> subnets, String securityGroup, boolean isALB) {
        var name = isALB ? "monsieur" : "madame";
        var type = isALB ? "application" : "network";
        var scheme = isALB ? "internal" : "internet-facing";

        return CfnLoadBalancer.Builder.create(scope, prefix + type + "-balancer")
                .name("balanceer-" + name)
                .type(type)
                .subnets(subnets)
                .securityGroups(List.of(securityGroup))
                .scheme(scheme)
                .build();
    }

    public CfnSecurityGroup createSecurityGroup(String vpcId, String description) {
        var securityGroup = CfnSecurityGroup.Builder.create(scope, String.format("%s-%s-sg", prefix, description))
                .groupDescription(description)
                .vpcId(vpcId)
                .build();
        makeInboundRule(securityGroup.getAttrId(), 80, description + "-http80");
        makeInboundRule(securityGroup.getAttrId(), 8080, description + "-http8080");
        makeInboundRule(securityGroup.getAttrId(), 22, description + "-ssh");
        makeInboundRule(securityGroup.getAttrId(), 27017, description + "-database");
        makeOutboundRule(securityGroup.getAttrId(), description);

        return securityGroup;
    }

    public CfnSecurityGroupIngress makeInboundRule(String sgId, Number port, String description) {
        return CfnSecurityGroupIngress.Builder.create(scope, prefix + description + "-rule")
                .description(description)
                .cidrIp("0.0.0.0/0")
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

    public CfnInstance createNginxInstance(String subnetId, String name, String securityGroupId) {
        String userData =
                """
                        #!/bin/bash
                        DNS_NAME=$(aws ssm get-parameter --name "/sebas/alb-dns" --region eu-central-1 --query "Parameter.Value" --output text)
                        export ALB_HOST=$DNS_NAME
                        systemctl start nginx
                        systemctl enable nginx
                        """;

        var result = CfnInstance.Builder.create(scope, prefix + name + "-instance")
                .tags(List.of(CfnTag.builder().key("Name").value(prefix + "NGINX").build()))
                .subnetId(subnetId)
                .instanceType("t2.micro")
                .imageId("ami-0904c2bf402fc81cc")
                .keyName("authSebas")
                .securityGroupIds(List.of(securityGroupId))
                .iamInstanceProfile(getInstanceProfile().getRef())
                .userData(Base64.getEncoder().encodeToString(userData.getBytes()))
                .build();
        return result;
    }

}

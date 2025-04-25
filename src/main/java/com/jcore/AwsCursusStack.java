package com.jcore;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnTag;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.ec2.*;
import software.constructs.Construct;

import java.util.List;


public class AwsCursusStack extends Stack {

    private static final String PREFIX = "sebastiaans-";

    public AwsCursusStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public AwsCursusStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);
        CfnOutput.Builder.create(this, "AccountUsed").
                description("").
                value("Account: " + this.getAccount()).
                build();
        CfnOutput.Builder.create(this, "RegionUsed").
                description("").
                value("Region: " + this.getRegion()).
                build();

        var vpc = createVpc("10.0.0.0/16");
        var publicSubnet = createSubnet("10.0.101.0/24", vpc.getAttrVpcId(), true);
        var privateSubnet = createSubnet("10.0.201.0/24", vpc.getAttrVpcId(), false);
        var gateway = createInternetGatewayAndAttachToVpc(vpc.getAttrVpcId());
        var natGateway = createNatGatewayAndAttachToSubnet(publicSubnet.getSubnetId());
        var rt1 = createRouteTable(vpc, publicSubnet, true);
        var publicRoute = createRoute(rt1.getAttrRouteTableId(), gateway.getAttrInternetGatewayId(), true);
        var rt2 = createRouteTable(vpc, privateSubnet, false);
        var privateRoute = createRoute(rt2.getAttrRouteTableId(), natGateway.getAttrNatGatewayId(), false);
    }

    private CfnVPC createVpc(final String cidrBlock) {
        var vpc = CfnVPC.Builder.create(this, PREFIX + "vpc").cidrBlock(cidrBlock).build();
        Tags.of(vpc).add("Name", PREFIX + "vpc");
        CfnOutput.Builder.create(this, "VpcCreated").value("VpcId: " + vpc.getAttrVpcId()).build();
        return vpc;
    }

    private ISubnet createSubnet(final String cidrBlock, final String vpcId, boolean publicNetwork) {
        String label = publicNetwork ? "public" : "private";
        var publicCfnSubnet =
                CfnSubnet.Builder.create(this, PREFIX + label + "-cfn-subnet").
                        availabilityZone(getRegion() + "a").
                        cidrBlock(cidrBlock).
                        mapPublicIpOnLaunch(publicNetwork).
                        vpcId(vpcId).
                        build();
        Tags.of(publicCfnSubnet).add("Name", PREFIX + label + "-subnet");
        var publicSubnet =
                Subnet.fromSubnetId(this, PREFIX + label + "-subnet", publicCfnSubnet.getAttrSubnetId());
        CfnOutput.Builder.create(this, label + "SubnetCreated").
                value("SubnetId: " + publicSubnet.getSubnetId()).build();
        return publicSubnet;
    }


    private CfnInternetGateway createInternetGatewayAndAttachToVpc(final String vpcId) {
        var internetGateway =
                CfnInternetGateway.Builder.create(this, PREFIX + "igw").
                        tags(List.of(CfnTag.builder().key("Name").value(PREFIX + "igw").build())).
                        build();
        CfnOutput.Builder.create(this, "InternetGatewayCreated").
                value("InternetGatewayId: " + internetGateway.getAttrInternetGatewayId()).
                build();
        var vpcGatewayAttachment =
                CfnVPCGatewayAttachment.Builder.create(this, PREFIX + "vpc-gateway-attachment").
                        vpcId(vpcId).
                        internetGatewayId(internetGateway.getAttrInternetGatewayId()).
                        build();
        CfnOutput.Builder.create(this, "VpcGatewayAttachmentCreated").
                value(String.format("VpcId: %s, InternetGatewayId: %s", vpcId, vpcGatewayAttachment.getInternetGatewayId())).
                build();
        return internetGateway;
    }

    private CfnRouteTable createRouteTable(
            final CfnVPC vpc, final ISubnet subnet, final boolean publicNetwork) {
        String label = publicNetwork ? "public" : "private";
        var routeTable =
                CfnRouteTable.Builder.create(this, PREFIX + label + "-route-table").
                        vpcId(vpc.getAttrVpcId()).
                        tags(List.of(CfnTag.builder().key("Name").value(label + "-route-table").build())).
                        build();
        System.out.println("Created RouteTable: " + routeTable.getAttrRouteTableId());

        CfnOutput.Builder.create(this, PREFIX + label + "RouteTableCreated").
                value("RouteTableId: " + routeTable.getAttrRouteTableId()).
                build();

        var subnetRouteTableAssociation =
                CfnSubnetRouteTableAssociation.Builder.create(this, PREFIX + label + "-subnet-route-table-association").
                        subnetId(subnet.getSubnetId()).
                        routeTableId(routeTable.getAttrRouteTableId()).
                        build();

        CfnOutput.Builder.create(this, PREFIX + label + "SubnetRouteTableAssociationCreated").
                value(
                        String.format(
                                "SubnetId: %s, RouteTableId: %s",
                                subnetRouteTableAssociation.getSubnetId(),
                                subnetRouteTableAssociation.getRouteTableId()
                        )
                ).build();

        return routeTable;
    }

    private CfnRoute createRoute(final String routeTableId, String gatewayId, final boolean publicNetwork) {
        var label = publicNetwork ? "internet" : "nat";
        var builder = CfnRoute.Builder.create(this, PREFIX + label + "-gateway-route").
                routeTableId(routeTableId).
                destinationCidrBlock("0.0.0.0/0");
        if (publicNetwork) {
            builder.gatewayId(gatewayId);
        } else {
            builder.natGatewayId(gatewayId);
        }
        return builder.build();
    }

    private CfnNatGateway createNatGatewayAndAttachToSubnet(String subnetId) {
        CfnEIP ip = CfnEIP.Builder.create(this, PREFIX + "elasticIP")
                .build();

        var natGateway = CfnNatGateway.Builder.create(this, PREFIX + "NAT-gateway")
                .subnetId(subnetId)
                .allocationId(ip.getAttrAllocationId())
                .connectivityType("public")
                .build();

        CfnOutput.Builder.create(this, "NatGatewayCreated").value("NatGatewayID: " + natGateway.getAttrNatGatewayId()).build();
        return natGateway;
    }
}

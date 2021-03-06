/*
 * Copyright (c) 2017, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.cluster.coordinator.zookeeper.test;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.cluster.coordinator.commons.configs.CoordinationPropertyNames;
import org.wso2.carbon.cluster.coordinator.commons.node.NodeDetail;
import org.wso2.carbon.cluster.coordinator.zookeeper.ZookeeperCoordinationStrategy;
import org.wso2.carbon.cluster.coordinator.zookeeper.internal.ZookeeperCoordinationServiceHolder;
import org.wso2.carbon.config.ConfigurationException;
import org.wso2.carbon.config.provider.ConfigProvider;
import org.wso2.carbon.config.provider.ConfigProviderImpl;
import org.wso2.carbon.config.reader.YAMLBasedConfigFileReader;
import org.wso2.carbon.secvault.internal.SecureVaultImpl;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class CoordinatorEventFlowTestCase {

    private static final Log log = LogFactory.getLog(CoordinatorEventFlowTestCase.class);


    ZookeeperCoordinationStrategy zookeeperCoordinationStrategyNodeOne;
    ZookeeperCoordinationStrategy zookeeperCoordinationStrategyNodeTwo;
    ZookeeperCoordinationStrategy zookeeperCoordinationStrategyNodeThree;
    EventListener eventListener;

    @BeforeClass public void initialize() throws InterruptedException, IOException {
        Path deploymentPath = null;
        try {
            deploymentPath = Paths.get(ClassLoader.getSystemResource("conf/deployment.yaml").toURI());
        } catch (URISyntaxException e) {
            log.error("The URI for deployment.yaml in invalid", e);
        }
        ConfigProvider configProvider = new ConfigProviderImpl(new YAMLBasedConfigFileReader(deploymentPath),
                new SecureVaultImpl());
        try {
            Map clusterConfig = (Map) configProvider.
                    getConfigurationObject(CoordinationPropertyNames.CLUSTER_CONFIG_NS);
            ZookeeperCoordinationServiceHolder.setClusterConfiguration(clusterConfig);
        } catch (ConfigurationException e) {
            log.error("Configuration file deployment.yaml not found in resources folder " + e);
        }
        ZookeeperCoordinationServiceHolder.setConfigProvider(configProvider);
        zookeeperCoordinationStrategyNodeOne = new ZookeeperCoordinationStrategy();
        zookeeperCoordinationStrategyNodeTwo = new ZookeeperCoordinationStrategy();
        zookeeperCoordinationStrategyNodeThree = new ZookeeperCoordinationStrategy();
        eventListener = new EventListener();
    }

    @Test public void testMemberJoined() throws InterruptedException {
        Map<String, Object> nodeOnePropertyMap = new HashMap<>();
        nodeOnePropertyMap.put("id", "node1");
        zookeeperCoordinationStrategyNodeOne.joinGroup(nodeOnePropertyMap);
        eventListener.setGroupId("testGroupOne");
        zookeeperCoordinationStrategyNodeOne.registerEventListener(eventListener);
        Thread.sleep(2000);
    }

    @Test(dependsOnMethods = { "testMemberJoined" }) public void testCoordinatorElected()
            throws InterruptedException {
        String leaderId = null;
        int count = 0;
        boolean coordinatorIdentified = false;
        while (count < 10) {
            NodeDetail leaderNodeDetail = zookeeperCoordinationStrategyNodeOne
                    .getLeaderNode();
            if (leaderNodeDetail != null) {
                leaderId = (String) leaderNodeDetail.getPropertiesMap().get("id");
                if (leaderId.equals("node1")) {
                    coordinatorIdentified = true;
                    break;
                }
            }

            Thread.sleep(2000);
            count++;
        }
        Assert.assertTrue(coordinatorIdentified, "Coordinator was not elected in group");
    }

    @Test(dependsOnMethods = { "testCoordinatorElected" }) public void testMultipleMemberJoined()
            throws InterruptedException {
        Map<String, Object> nodeTwoPropertyMap = new HashMap<>();
        nodeTwoPropertyMap.put("id", "node2");
        zookeeperCoordinationStrategyNodeTwo.joinGroup(nodeTwoPropertyMap);
        Map<String, Object> nodeThreePropertyMap = new HashMap<>();
        nodeThreePropertyMap.put("id", "node3");
        zookeeperCoordinationStrategyNodeThree.joinGroup(nodeThreePropertyMap);

        int count = 0;
        boolean membersJoined = false;
        while (count < 10) {
            if (zookeeperCoordinationStrategyNodeOne.getAllNodeDetails().size()
                    == 3) {
                membersJoined = true;
                break;
            }
            Thread.sleep(2000);
            count++;
        }
        Assert.assertTrue(membersJoined, "Multiple members were not joined to group");
    }

    @Test(dependsOnMethods = {
            "testMultipleMemberJoined" }) public void testMemberAddedEventRecieved()
            throws InterruptedException {
        int count = 0;
        boolean eventRecieved = false;
        while (count < 10) {
            if (eventListener.memberAdded.size() == 3) {
                eventRecieved = true;
                break;
            }
            Thread.sleep(2000);
            count++;
        }
        Assert.assertTrue(eventRecieved, "Member added event not received.");
    }

    @Test(dependsOnMethods = { "testMemberAddedEventRecieved" }) public void testMemberRemoved()
            throws InterruptedException {
        zookeeperCoordinationStrategyNodeTwo.stop();
        int count = 0;
        boolean membersRemoved = false;
        while (count < 10) {
            if (zookeeperCoordinationStrategyNodeOne.getAllNodeDetails().size()
                    == 2) {
                membersRemoved = true;
                break;
            }
            Thread.sleep(2000);
            count++;
        }
        Assert.assertTrue(membersRemoved, "Member not removed from group");
    }

    @Test(dependsOnMethods = { "testMemberRemoved" }) public void testMemberRemovedEventRecieved()
            throws InterruptedException {
        int count = 0;
        boolean eventRecieved = false;
        while (count < 10) {
            if (eventListener.memberRemoved.size() == 1) {
                eventRecieved = true;
                break;
            }
            Thread.sleep(2000);
            count++;
        }
        Assert.assertTrue(eventRecieved, "Member removed event not received.");
    }

    @Test(dependsOnMethods = {
            "testMemberRemovedEventRecieved" }) public void testCoordinatorChanged()
            throws InterruptedException, IOException {
        int count;
        String leaderId = null;
        ZookeeperCoordinationStrategy zookeeperCoordinationStrategyNodeFour = new ZookeeperCoordinationStrategy();
        Map<String, Object> nodeFourPropertyMap = new HashMap<>();
        nodeFourPropertyMap.put("id", "node4");
        zookeeperCoordinationStrategyNodeFour.joinGroup(nodeFourPropertyMap);
        EventListener eventListenerTwo = new EventListener();
        eventListenerTwo.setGroupId("testGroupOne");
        zookeeperCoordinationStrategyNodeFour.registerEventListener(eventListenerTwo);
        Thread.sleep(2000);
        boolean coordinatorChanged = false;
        count = 0;

        zookeeperCoordinationStrategyNodeOne.stop();

        while (count < 10) {
            NodeDetail leaderNodeDetail = zookeeperCoordinationStrategyNodeThree
                    .getLeaderNode();
            if (leaderNodeDetail != null) {
                leaderId = (String) leaderNodeDetail.getPropertiesMap().get("id");
            } else {
                leaderId = "";
            }
            if (leaderId.equals("node3") || leaderId.equals("node4")) {
                coordinatorChanged = true;
                break;
            }
            Thread.sleep(2000);
            count++;
        }

        Assert.assertTrue(coordinatorChanged, "Coordinator not changed");
        count = 0;
        boolean coordinatorEventReceived = false;
        while (count < 10) {
            if (eventListenerTwo.coordinatorChanged.size() == 1) {
                coordinatorEventReceived = true;
                break;
            }
            Thread.sleep(2000);
            count++;
        }
        Assert.assertTrue(coordinatorEventReceived, "Coordinator changed event not received.");
    }
}

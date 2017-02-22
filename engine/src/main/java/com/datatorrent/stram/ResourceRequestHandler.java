/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.datatorrent.stram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.hadoop.yarn.api.records.NodeReport;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.util.Records;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.stram.StreamingContainerAgent.ContainerStartRequest;
import com.datatorrent.stram.plan.physical.PTContainer;
import com.datatorrent.stram.plan.physical.PTOperator;
import com.datatorrent.stram.plan.physical.PTOperator.HostOperatorSet;

/**
 * Handle mapping from physical plan locality groupings to resource allocation requests. Monitors available resources
 * through node reports.
 *
 * @since 0.3.4
 */
public class ResourceRequestHandler
{
  private static final String INVALID_HOST = "INVALID_HOST";

  protected static final int NUMBER_MISSED_HEARTBEATS = 30;

  private int maximumMemory;
  private int minimumMemory;

  public ResourceRequestHandler()
  {
    super();
    this.minimumMemory = 1;
    this.maximumMemory = Integer.MAX_VALUE;
  }

  /**
   * Issue requests to AM RM Client again if previous container requests expired and were not allocated by Yarn
   * @param requestedResources
   * @param loopCounter
   * @param resourceRequestor
   * @param containerRequests
   * @param removedContainerRequests
   */
  public void reissueContainerRequests(Map<StreamingContainerAgent.ContainerStartRequest, MutablePair<Integer, ContainerRequest>> requestedResources,
                                       int loopCounter,
                                       ResourceRequestHandler resourceRequestor,
                                       List<ContainerRequest> containerRequests,
                                       List<ContainerRequest> removedContainerRequests)
  {
    if (!requestedResources.isEmpty()) {
      for (Map.Entry<StreamingContainerAgent.ContainerStartRequest, MutablePair<Integer, ContainerRequest>> entry : requestedResources.entrySet()) {
        logger.info("{} Evaluating {}", loopCounter, entry.getValue());
        /*
         * Create container requests again if pending requests were not allocated by Yarn till timeout.
         */
        if ((loopCounter - entry.getValue().getKey()) > NUMBER_MISSED_HEARTBEATS) {
          reissueContainerRequest(entry, removedContainerRequests, resourceRequestor, loopCounter, containerRequests);
        }
      }
    }
  }

  public void reissueContainerRequest(Entry<ContainerStartRequest, MutablePair<Integer, ContainerRequest>> entry,
                                      List<ContainerRequest> removedContainerRequests,
                                      ResourceRequestHandler resourceRequestor,
                                      int loopCounter,
                                      List<ContainerRequest> containerRequests)
  {
    StreamingContainerAgent.ContainerStartRequest csr = entry.getKey();
    removedContainerRequests.add(entry.getValue().getRight());
    ContainerRequest cr = resourceRequestor.createContainerRequest(csr, false);
    entry.getValue().setLeft(loopCounter);
    entry.getValue().setRight(cr);
    containerRequests.add(cr);
  }

  /**
   * Add container request to list of issued requests to Yarn along with current loop counter
   * @param requestedResources
   * @param loopCounter
   * @param containerRequests
   * @param csr
   * @param cr
   */
  public void addContainerRequest(Map<StreamingContainerAgent.ContainerStartRequest, MutablePair<Integer, ContainerRequest>> requestedResources, int loopCounter, List<ContainerRequest> containerRequests, StreamingContainerAgent.ContainerStartRequest csr, ContainerRequest cr)
  {
    MutablePair<Integer, ContainerRequest> pair = new MutablePair<>(loopCounter, cr);
    requestedResources.put(csr, pair);
    containerRequests.add(cr);
  }

  /**
   * Setup the request(s) that will be sent to the RM for the container ask.
   */
  public ContainerRequest createContainerRequest(ContainerStartRequest csr, boolean first)
  {
    int priority = csr.container.getResourceRequestPriority();
    // check for node locality constraint
    String[] nodes = null;
    String[] racks = null;

    String host = getHost(csr, first);
    Resource capability = Records.newRecord(Resource.class);

    int memMB = csr.container.getRequiredMemoryMB();
    if (memMB > maximumMemory) {
      memMB = maximumMemory;
    }
    else if (memMB < minimumMemory) {
      memMB = minimumMemory;
    }
    capability.setMemory(memMB);

    capability.setVirtualCores(csr.container.getRequiredVCores());

    if (host == null) {
      // For now, only memory is supported so we set memory requirements
      return new ContainerRequest(capability, nodes, racks, Priority.newInstance(priority));
    }
    else if (INVALID_HOST.equals(host)) {
      return null;
    }

    // in order to request a host, we don't have to set the rack if the locality is false
    /*
       * if(this.nodeToRack.get(host) != null){ racks = new String[] { this.nodeToRack.get(host) }; }
     */
    return new ContainerRequest(capability, new String[] {host}, racks, Priority.newInstance(priority), false);
  }

  private final Map<String, NodeReport> nodeReportMap = Maps.newHashMap();
  private final Map<Set<PTOperator>, String> nodeLocalMapping = Maps.newHashMap();
  private final Map<String, String> nodeToRack = Maps.newHashMap();
  private final Map<PTContainer, String> antiAffinityMapping = Maps.newHashMap();

  public void clearNodeMapping()
  {
    nodeLocalMapping.clear();
  }

  /**
   * Tracks update to available resources. Resource availability is used to make decisions about where to request new
   * containers.
   *
   * @param nodeReports
   */
  public void updateNodeReports(List<NodeReport> nodeReports)
  {
    // LOG.debug("Got {} updated node reports.", nodeReports.size());
    for (NodeReport nr : nodeReports) {
      StringBuilder sb = new StringBuilder();
      sb.append("rackName=").append(nr.getRackName()).append(",nodeid=").append(nr.getNodeId()).append(",numContainers=").append(nr.getNumContainers()).append(",capability=").append(nr.getCapability()).append("used=").append(nr.getUsed()).append("state=").append(nr.getNodeState());
      logger.debug("Node report: {}", sb);
      nodeReportMap.put(nr.getNodeId().getHost(), nr);
      nodeToRack.put(nr.getNodeId().getHost(), nr.getRackName());
    }
  }

  public List<String> getNodesExceptHost(List<String> hostNames)
  {
    List<String> nodesList = new ArrayList<>();
    Set<String> hostNameSet = Sets.newHashSet();
    hostNameSet.addAll(hostNames);
    for (String host : nodeReportMap.keySet()) {
      // Split node name and port
      String[] parts = host.split(":");
      if (parts.length > 0) {
        if (hostNameSet.contains(parts[0]) || hostNameSet.contains(host)) {
          continue;
        }
        nodesList.add(parts[0]);
      }
    }
    return nodesList;
  }

  public String getHost(ContainerStartRequest csr, boolean first)
  {
    String host = null;
    PTContainer c = csr.container;
    if (first) {
      for (PTOperator oper : c.getOperators()) {
        HostOperatorSet grpObj = oper.getNodeLocalOperators();
        host = nodeLocalMapping.get(grpObj.getOperatorSet());
        if (host != null) {
          antiAffinityMapping.put(c, host);
          return host;
        }
        if (grpObj.getHost() != null) {
          host = grpObj.getHost();
          // using the 1st host value as host for container
          break;
        }
      }
      if (host != null && nodeReportMap.get(host) != null) {
        for (PTOperator oper : c.getOperators()) {
          HostOperatorSet grpObj = oper.getNodeLocalOperators();
          Set<PTOperator> nodeLocalSet = grpObj.getOperatorSet();
          NodeReport report = nodeReportMap.get(host);
          int aggrMemory = c.getRequiredMemoryMB();
          int vCores = c.getRequiredVCores();
          Set<PTContainer> containers = Sets.newHashSet();
          containers.add(c);
          for (PTOperator nodeLocalOper : nodeLocalSet) {
            if (!containers.contains(nodeLocalOper.getContainer())) {
              aggrMemory += nodeLocalOper.getContainer().getRequiredMemoryMB();
              vCores += nodeLocalOper.getContainer().getRequiredVCores();
              containers.add(nodeLocalOper.getContainer());
            }
          }
          int memAvailable = report.getCapability().getMemory() - report.getUsed().getMemory();
          int vCoresAvailable = report.getCapability().getVirtualCores() - report.getUsed().getVirtualCores();
          if (memAvailable >= aggrMemory && vCoresAvailable >= vCores) {
            nodeLocalMapping.put(nodeLocalSet, host);
            antiAffinityMapping.put(c, host);
            return host;
          }
        }
      }
    }

    // the host requested didn't have the resources so looking for other hosts
    host = null;
    List<String> antiHosts = new ArrayList<>();
    List<String> antiPreferredHosts = new ArrayList<>();
    if (!c.getStrictAntiPrefs().isEmpty()) {
      // Check if containers are allocated already for the anti-affinity containers
      populateAntiHostList(c, antiHosts);
    }
    if (!c.getPreferredAntiPrefs().isEmpty()) {
      populateAntiHostList(c, antiPreferredHosts);
    }
    logger.info("Strict anti-affinity = {} for container with operators {}", antiHosts, StringUtils.join(c.getOperators(), ","));
    for (PTOperator oper : c.getOperators()) {
      HostOperatorSet grpObj = oper.getNodeLocalOperators();
      Set<PTOperator> nodeLocalSet = grpObj.getOperatorSet();
      if (nodeLocalSet.size() > 1 ||  !c.getStrictAntiPrefs().isEmpty() || !c.getPreferredAntiPrefs().isEmpty()) {
        logger.info("Finding new host for {}", nodeLocalSet);
        int aggrMemory = c.getRequiredMemoryMB();
        int vCores = c.getRequiredVCores();
        Set<PTContainer> containers = Sets.newHashSet();
        containers.add(c);
        // aggregate memory required for all containers
        for (PTOperator nodeLocalOper : nodeLocalSet) {
          if (!containers.contains(nodeLocalOper.getContainer())) {
            aggrMemory += nodeLocalOper.getContainer().getRequiredMemoryMB();
            vCores += nodeLocalOper.getContainer().getRequiredVCores();
            containers.add(nodeLocalOper.getContainer());
          }
        }
        host = assignHost(host, antiHosts, antiPreferredHosts, grpObj, nodeLocalSet, aggrMemory, vCores);

        if (host == null && !antiPreferredHosts.isEmpty() && !antiHosts.isEmpty()) {
          // Drop the preferred constraint and try allocation
          antiPreferredHosts.clear();
          host = assignHost(host, antiHosts, antiPreferredHosts, grpObj, nodeLocalSet, aggrMemory, vCores);
        }
        if (host != null) {
          antiAffinityMapping.put(c, host);
        } else {
          host = INVALID_HOST;
        }
      }
    }
    logger.info("Found host {}", host);
    return host;
  }

  /**
   * Populate list of nodes where container cannot be allocated due to anti-affinity constraints
   * @param c container
   * @param antiHosts List of nodes where container cannot be allocated
   */
  public void populateAntiHostList(PTContainer c, List<String> antiHosts)
  {
    for (PTContainer container : c.getStrictAntiPrefs()) {
      if (antiAffinityMapping.containsKey(container)) {
        antiHosts.add(antiAffinityMapping.get(container));
      } else {
        // Check if there is an anti-affinity with host locality
        String antiHost = getHostForContainer(container);
        if (antiHost != null) {
          antiHosts.add(antiHost);
        }
      }
    }
  }

  /**
   * Get host name where container would be allocated give node local constraints
   * @param container
   * @return
   */
  public String getHostForContainer(PTContainer container)
  {
    for (PTOperator oper : container.getOperators()) {
      HostOperatorSet grpObj = oper.getNodeLocalOperators();
      String host = nodeLocalMapping.get(grpObj.getOperatorSet());
      if (host != null) {
        return host;
      }
      if (grpObj.getHost() != null) {
        host = grpObj.getHost();
        return host;
      }
    }
    return null;
  }

  /**
   * Assign host to container given affinity and anti-affinity constraints and resource availibility on node
   * @param host
   * @param antiHosts
   * @param antiPreferredHosts
   * @param grpObj
   * @param nodeLocalSet
   * @param aggrMemory
   * @param vCores
   * @return
   */
  public String assignHost(String host, List<String> antiHosts, List<String> antiPreferredHosts, HostOperatorSet grpObj, Set<PTOperator> nodeLocalSet, int aggrMemory, int vCores)
  {
    for (Map.Entry<String, NodeReport> nodeEntry : nodeReportMap.entrySet()) {
      if (nodeEntry.getValue().getNodeState() == NodeState.RUNNING) {
        int memAvailable = nodeEntry.getValue().getCapability().getMemory() - nodeEntry.getValue().getUsed().getMemory();
        int vCoresAvailable = nodeEntry.getValue().getCapability().getVirtualCores() - nodeEntry.getValue().getUsed().getVirtualCores();
        if (memAvailable >= aggrMemory && vCoresAvailable >= vCores && !antiHosts.contains(nodeEntry.getKey()) && !antiPreferredHosts.contains(nodeEntry.getKey())) {
          host = nodeEntry.getKey();
          grpObj.setHost(host);
          nodeLocalMapping.put(nodeLocalSet, host);

          return host;
        }
      }
    }
    return null;
  }

  /**
   * @return the maximumMemory
   */
  public int getMaximumMemory()
  {
    return maximumMemory;
  }

  /**
   * @param maximumMemory the maximumMemory to set
   */
  public void setMaximumMemory(int maximumMemory)
  {
    this.maximumMemory = maximumMemory;
  }

  /**
   * @return the minimumMemory
   */
  public int getMinimumMemory()
  {
    return minimumMemory;
  }

  /**
   * @param minimumMemory the minimumMemory to set
   */
  public void setMinimumMemory(int minimumMemory)
  {
    this.minimumMemory = minimumMemory;
  }

  private static final Logger logger = LoggerFactory.getLogger(ResourceRequestHandler.class);
}

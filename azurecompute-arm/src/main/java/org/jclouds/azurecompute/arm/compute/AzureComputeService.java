/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.azurecompute.arm.compute;

import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Sets.newHashSet;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_NODE_RUNNING;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_NODE_SUSPENDED;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_NODE_TERMINATED;
import static org.jclouds.compute.predicates.NodePredicates.all;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.jclouds.Constants;
import org.jclouds.azurecompute.arm.compute.domain.ResourceGroupAndName;
import org.jclouds.azurecompute.arm.compute.strategy.CleanupResources;
import org.jclouds.collect.Memoized;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.callables.RunScriptOnNode;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.Image;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.extensions.ImageExtension;
import org.jclouds.compute.extensions.SecurityGroupExtension;
import org.jclouds.compute.internal.BaseComputeService;
import org.jclouds.compute.internal.PersistNodeCredentials;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.compute.reference.ComputeServiceConstants.Timeouts;
import org.jclouds.compute.strategy.CreateNodesInGroupThenAddToSet;
import org.jclouds.compute.strategy.DestroyNodeStrategy;
import org.jclouds.compute.strategy.GetImageStrategy;
import org.jclouds.compute.strategy.GetNodeMetadataStrategy;
import org.jclouds.compute.strategy.InitializeRunScriptOnNodeOrPlaceInBadMap;
import org.jclouds.compute.strategy.ListNodesStrategy;
import org.jclouds.compute.strategy.RebootNodeStrategy;
import org.jclouds.compute.strategy.ResumeNodeStrategy;
import org.jclouds.compute.strategy.SuspendNodeStrategy;
import org.jclouds.domain.Credentials;
import org.jclouds.domain.Location;
import org.jclouds.scriptbuilder.functions.InitAdminAccess;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;

@Singleton
public class AzureComputeService extends BaseComputeService {
   private final CleanupResources cleanupResources;

   @Inject
   protected AzureComputeService(ComputeServiceContext context, Map<String, Credentials> credentialStore,
         @Memoized Supplier<Set<? extends Image>> images, @Memoized Supplier<Set<? extends Hardware>> sizes,
         @Memoized Supplier<Set<? extends Location>> locations, ListNodesStrategy listNodesStrategy,
         GetImageStrategy getImageStrategy, GetNodeMetadataStrategy getNodeMetadataStrategy,
         CreateNodesInGroupThenAddToSet runNodesAndAddToSetStrategy, RebootNodeStrategy rebootNodeStrategy,
         DestroyNodeStrategy destroyNodeStrategy, ResumeNodeStrategy startNodeStrategy,
         SuspendNodeStrategy stopNodeStrategy, Provider<TemplateBuilder> templateBuilderProvider,
         @Named("DEFAULT") Provider<TemplateOptions> templateOptionsProvider,
         @Named(TIMEOUT_NODE_RUNNING) Predicate<AtomicReference<NodeMetadata>> nodeRunning,
         @Named(TIMEOUT_NODE_TERMINATED) Predicate<AtomicReference<NodeMetadata>> nodeTerminated,
         @Named(TIMEOUT_NODE_SUSPENDED) Predicate<AtomicReference<NodeMetadata>> nodeSuspended,
         InitializeRunScriptOnNodeOrPlaceInBadMap.Factory initScriptRunnerFactory,
         RunScriptOnNode.Factory runScriptOnNodeFactory, InitAdminAccess initAdminAccess,
         PersistNodeCredentials persistNodeCredentials, Timeouts timeouts,
         @Named(Constants.PROPERTY_USER_THREADS) ListeningExecutorService userExecutor,
         CleanupResources cleanupResources, Optional<ImageExtension> imageExtension,
         Optional<SecurityGroupExtension> securityGroupExtension) {
      super(context, credentialStore, images, sizes, locations, listNodesStrategy, getImageStrategy,
            getNodeMetadataStrategy, runNodesAndAddToSetStrategy, rebootNodeStrategy, destroyNodeStrategy,
            startNodeStrategy, stopNodeStrategy, templateBuilderProvider, templateOptionsProvider, nodeRunning,
            nodeTerminated, nodeSuspended, initScriptRunnerFactory, initAdminAccess, runScriptOnNodeFactory,
            persistNodeCredentials, timeouts, userExecutor, imageExtension, securityGroupExtension);
      this.cleanupResources = cleanupResources;
   }

   @Override
   protected void cleanUpIncidentalResourcesOfDeadNodes(Set<? extends NodeMetadata> deadNodes) {
      ImmutableMultimap.Builder<String, String> regionGroups = ImmutableMultimap.builder();
      ImmutableSet.Builder<String> resourceGroups = ImmutableSet.builder();

      for (NodeMetadata deadNode : deadNodes) {
         String resourceGroupName = ResourceGroupAndName.fromSlashEncoded(deadNode.getId()).resourceGroup();
         resourceGroups.add(resourceGroupName);
         
         if (deadNode.getGroup() != null) {
            regionGroups.put(resourceGroupName, deadNode.getGroup());
         }

         try {
            cleanupResources.cleanupNode(deadNode.getId());
         } catch (Exception ex) {
            logger.warn(ex, "Error cleaning up resources for node %s", deadNode);
         }
      }

      for (Entry<String, String> regionGroup : regionGroups.build().entries()) {
         cleanupResources.cleanupSecurityGroupIfOrphaned(regionGroup.getKey(), regionGroup.getValue());
      }

      for (String resourceGroup : resourceGroups.build()) {
         cleanupResources.deleteResourceGroupIfEmpty(resourceGroup);
      }
   }

   @Override
   public void destroyNode(String id) {
      // Azure ARM does not return TERMINATED nodes, so in practice no node will never reach the TERMINATED
      // state, and the deleted nodes will never be returned.
      // In order to be able to clean up the resources associated to the deleted nodes, we have to retrieve
      // the details of the nodes before deleting them.
      NodeMetadata nodeMetadataBeforeDelete = getNodeMetadata(id);
      super.destroyNode(id);
      //Node metadata is null after deletion but we still need to clean up incidental resources
      cleanUpIncidentalResourcesOfDeadNodes(ImmutableSet.of(nodeMetadataBeforeDelete));
   }

   @Override
   public Set<? extends NodeMetadata> destroyNodesMatching(Predicate<? super NodeMetadata> filter) {
      // Azure ARM does not return TERMINATED nodes, so in practice no node will never reach the TERMINATED
      // state, and the deleted nodes will never be returned.
      // In order to be able to clean up the resources associated to the deleted nodes, we have to retrieve
      // the details of the nodes before deleting them.
      Set<? extends NodeMetadata> nodes = newHashSet(filter(listNodesDetailsMatching(all()), filter));
      super.destroyNodesMatching(filter); // This returns an empty list (a list of null elements) in Azure ARM, as the api does not return deleted nodes
      cleanUpIncidentalResourcesOfDeadNodes(nodes);
      return nodes;
   }
}

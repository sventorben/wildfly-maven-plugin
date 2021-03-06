/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.plugin.deployment.domain;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.domain.DeploymentActionResult;
import org.jboss.as.controller.client.helpers.domain.DeploymentActionsCompleteBuilder;
import org.jboss.as.controller.client.helpers.domain.DeploymentPlan;
import org.jboss.as.controller.client.helpers.domain.DeploymentPlanBuilder;
import org.jboss.as.controller.client.helpers.domain.DeploymentPlanResult;
import org.jboss.as.controller.client.helpers.domain.DomainClient;
import org.jboss.as.controller.client.helpers.domain.DomainDeploymentManager;
import org.jboss.as.controller.client.helpers.domain.DuplicateDeploymentNameException;
import org.jboss.as.controller.client.helpers.domain.ServerGroupDeploymentActionResult;
import org.jboss.as.controller.client.helpers.domain.ServerGroupDeploymentPlanBuilder;
import org.jboss.as.controller.client.helpers.domain.ServerUpdateResult;
import org.jboss.dmr.ModelNode;
import org.wildfly.plugin.common.DeploymentExecutionException;
import org.wildfly.plugin.common.DeploymentFailureException;
import org.wildfly.plugin.common.DeploymentInspector;
import org.wildfly.plugin.common.ServerOperations;
import org.wildfly.plugin.deployment.Deployment;
import org.wildfly.plugin.deployment.MatchPatternStrategy;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
class DomainDeployment implements Deployment {

    private final File content;
    private final DomainClient client;
    private final Domain domain;
    private final String name;
    private final String runtimeName;
    private final Type type;
    private final String matchPattern;
    private final MatchPatternStrategy matchPatternStrategy;
    private InputStream contentStream;

    /**
     * Creates a new deployment.
     *
     * @param client               the client for the server
     * @param domain               the domain information
     * @param content              the content for the deployment
     * @param name                 the name of the deployment, if {@code null} the name of the content file is used
     * @param runtimeName          the runtime name of the deployment, if {@code null} the name is used
     * @param type                 the deployment type
     * @param matchPattern         the pattern for matching multiple artifacts, if {@code null} the name is used.
     * @param matchPatternStrategy the strategy for handling multiple artifacts.
     */
    DomainDeployment(final DomainClient client, final Domain domain, final File content, final String name, final String runtimeName, final Type type,
                            final String matchPattern, final MatchPatternStrategy matchPatternStrategy) {
        this.content = content;
        this.client = client;
        this.domain = domain;
        this.name = (name == null ? content.getName() : name);
        this.runtimeName = (runtimeName == null ? this.name : runtimeName);
        this.type = type;
        this.matchPattern = matchPattern;
        this.matchPatternStrategy = matchPatternStrategy;
    }

    private DeploymentPlan createPlan(final DeploymentPlanBuilder builder) throws IOException, DuplicateDeploymentNameException, DeploymentFailureException {
        DeploymentActionsCompleteBuilder completeBuilder = null;
        List<String> existingDeployments = DeploymentInspector.getDeployments(client, name, matchPattern);
        switch (type) {
            case DEPLOY: {
                contentStream = Files.newInputStream(content.toPath());
                completeBuilder = builder.add(name, runtimeName, contentStream).andDeploy();
                break;
            }
            case FORCE_DEPLOY: {
                contentStream = Files.newInputStream(content.toPath());
                if (existingDeployments.contains(name)) {
                    completeBuilder = builder.replace(name, runtimeName, contentStream);
                } else {
                    completeBuilder = builder.add(name, runtimeName, contentStream).andDeploy();
                }
                break;
            }
            case REDEPLOY: {
                contentStream = Files.newInputStream(content.toPath());
                completeBuilder = builder.replace(name, runtimeName, contentStream);
                break;
            }
            case UNDEPLOY: {
                validateExistingDeployments(existingDeployments);
                completeBuilder = undeployAndRemoveUndeployed(builder, existingDeployments);
                break;
            }
            case UNDEPLOY_IGNORE_MISSING: {
                validateExistingDeployments(existingDeployments);
                if (!existingDeployments.isEmpty()) {
                    completeBuilder = undeployAndRemoveUndeployed(builder, existingDeployments);
                } else {
                    return null;
                }
            }
        }
        if (completeBuilder != null) {
            ServerGroupDeploymentPlanBuilder groupDeploymentBuilder = null;
            for (String serverGroupName : domain.getServerGroups()) {
                groupDeploymentBuilder = (groupDeploymentBuilder == null ? completeBuilder.toServerGroup(serverGroupName) :
                        groupDeploymentBuilder.toServerGroup(serverGroupName));
            }
            if (groupDeploymentBuilder == null) {
                throw new DeploymentFailureException("No server groups were defined for the deployment.");
            }
            return groupDeploymentBuilder.withRollback().build();
        }
        throw new IllegalStateException(String.format("Invalid type '%s' for deployment", type));
    }

    private DeploymentActionsCompleteBuilder undeployAndRemoveUndeployed(
            final DeploymentPlanBuilder builder, final Iterable<String> deploymentNames) {

        DeploymentActionsCompleteBuilder completeBuilder = null;
        for (String deploymentName : deploymentNames) {

            final DeploymentPlanBuilder b = (completeBuilder == null ? builder : completeBuilder);
            completeBuilder = b.undeploy(deploymentName).andRemoveUndeployed();

            if (matchPatternStrategy == MatchPatternStrategy.FIRST) {
                break;
            }
        }

        return completeBuilder;
    }

    private void validateExistingDeployments(List<String> existingDeployments) throws DeploymentFailureException {
        if (matchPattern == null) {
            return;
        }

        if (matchPatternStrategy == MatchPatternStrategy.FAIL && existingDeployments.size() > 1) {
            throw new DeploymentFailureException(String.format("Deployment failed, found %d deployed artifacts for pattern '%s' (%s)",
                    existingDeployments.size(), matchPattern, existingDeployments));
        }
    }

    @Override
    public Status execute() throws DeploymentExecutionException, DeploymentFailureException {
        try {
            validate();
            final DomainDeploymentManager manager = client.getDeploymentManager();
            final DeploymentPlanBuilder builder = manager.newDeploymentPlan();
            DeploymentPlan plan = createPlan(builder);
            if (plan != null) {
                executePlan(manager, plan);
            }
        } catch (DeploymentFailureException | DeploymentExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new DeploymentExecutionException(e, "Error executing %s", type);
        } finally {
            safeClose(contentStream);
        }
        return Status.SUCCESS;
    }

    void validate() throws DeploymentFailureException {
        // Get the server-groups we're deploying to
        final List<String> serverGroups = domain.getServerGroups();
        // Get all the server groups from the server
        final ModelNode op = ServerOperations.createOperation(ServerOperations.READ_CHILDREN_NAMES);
        op.get(ClientConstants.CHILD_TYPE).set(ClientConstants.SERVER_GROUP);
        try {
            final ModelNode result = client.execute(op);
            if (ServerOperations.isSuccessfulOutcome(result)) {
                final List<String> availableGroups = readResultAsStringList(result);
                for (String serverGroup : serverGroups) {
                    if (!availableGroups.contains(serverGroup)) {
                        throw new DeploymentFailureException("Server group '%s' does not exist on the server. Available server groups %s.", serverGroup, availableGroups);
                    }
                }
            } else {
                throw new DeploymentFailureException("Failed to get the available server groups. %s", ServerOperations.getFailureDescription(result));
            }
        } catch (Exception e) {
            throw new DeploymentFailureException("Failed to get the available server groups.", e);
        }
    }

    @Override
    public Type getType() {
        return type;
    }

    private void executePlan(final DomainDeploymentManager manager, final DeploymentPlan plan) throws DeploymentExecutionException, ExecutionException, InterruptedException {
        if (!plan.getDeploymentActions().isEmpty()) {
            final DeploymentPlanResult planResult = manager.execute(plan).get();
            final Map<UUID, DeploymentActionResult> actionResults = planResult.getDeploymentActionResults();
            for (UUID uuid : actionResults.keySet()) {
                final Map<String, ServerGroupDeploymentActionResult> groupDeploymentActionResults = actionResults.get(uuid).getResultsByServerGroup();
                for (String serverGroup2 : groupDeploymentActionResults.keySet()) {
                    final Map<String, ServerUpdateResult> serverUpdateResults = groupDeploymentActionResults.get(serverGroup2).getResultByServer();
                    for (String server : serverUpdateResults.keySet()) {
                        final Throwable t = serverUpdateResults.get(server).getFailureResult();
                        if (t != null) {
                            throw new DeploymentExecutionException(t, "Error executing %s", type);
                        }
                    }
                }
            }
        }
    }

    private static void safeClose(final Closeable closeable) {
        if (closeable != null) try {
            closeable.close();
        } catch (IOException ignore) {
        }
    }

    private static List<String> readResultAsStringList(final ModelNode result) {
        final ModelNode r = ServerOperations.readResult(result);
        if (r.isDefined()) {
            final List<String> l = new ArrayList<>();
            for (ModelNode n : r.asList()) {
                l.add(n.asString());
            }
            return l;
        }
        return Collections.emptyList();
    }
}

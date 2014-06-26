/*
 *  The MIT License
 *
 *  Copyright (c) 2014 Sony Mobile Communications Inc. All rights reserved.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package com.sonymobile.jenkins.plugins.lenientshutdown;

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static hudson.model.Queue.BuildableItem;

/**
 * Utility class for getting information about the build queue and ongoing builds.
 *
 * @author Fredrik Persson &lt;fredrik6.persson@sonymobile.com&gt;
 */
public final class QueueUtils {

    /**
     * Hiding utility class constructor.
     */
    private QueueUtils() { }

    /**
     * Returns the set of all project names that are in the build queue
     * AND have a completed upstream project.
     * Note: This method locks the queue; don't use excessively.
     * @return set of queued and permitted project names
     */
    public static Set<String> getPermittedQueueProjectNames() {
        return getPermittedQueueProjectNames(null);
    }

    /**
     * Returns the set of queued project names that are bound to a specific node
     * AND have a completed upstream project.
     * Note: This method locks the queue; don't use excessively.
     * @param nodeName the node name to check projects for, null is interpreted as no restriction
     * @return set of queued and permitted project names
     */
    public static Set<String> getPermittedQueueProjectNames(String nodeName) {
        Set<String> queuedProjects = new HashSet<String>();
        Queue queueInstance = Queue.getInstance();
        for (int id : getPermittedQueueItemIds(nodeName)) {
            Queue.Item item = queueInstance.getItem(id);
            if (item.task instanceof AbstractProject) {
                AbstractProject project = (AbstractProject)item.task;
                queuedProjects.add(project.getFullName());
            }
        }
        return Collections.unmodifiableSet(queuedProjects);
    }

    /**
     * Returns the set of item ids for items that are in the build queue
     * AND have a completed upstream project if they are a project build.
     * Note: This method locks the queue; don't use excessively.
     * @return set of item ids
     */
    public static Set<Integer> getPermittedQueueItemIds() {
        Set<Integer> queuedIds = new HashSet<Integer>();
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.task instanceof AbstractProject) {
                //Only add if it has a completed upstream build:
                for (AbstractBuild upstreamBuild : getUpstreamBuilds(item)) {
                    if (!upstreamBuild.isBuilding()) {
                        queuedIds.add(item.id);
                        break;
                    }
                }
            } else {
                queuedIds.add(item.id);
            }
        }
        return Collections.unmodifiableSet(queuedIds);
    }

    /**
     * Returns a set of queued item ids that are bound to a specific node
     * and should be permitted to build since they have a completed upstream project.
     * Note: This method locks the queue; don't use excessively.
     * @param nodeName the node name to check allowed ids for
     * @return set of permitted item ids
     */
    public static Set<Integer> getPermittedQueueItemIds(String nodeName) {
        Set<Integer> permittedQueueItemIds = new HashSet<Integer>();
        if (nodeName == null) {
            permittedQueueItemIds.addAll(getPermittedQueueItemIds());
        } else {
            Queue queueInstance = Queue.getInstance();

            Node node = Jenkins.getInstance().getNode(nodeName);
            if (nodeName.isEmpty()) { //Special case when building on master
                node = Jenkins.getInstance();
            }

            if (node != null) {
                for (int id : getPermittedQueueItemIds()) {
                    Queue.Item item = queueInstance.getItem(id);
                    if (item != null && !canOtherNodeBuild(item, node)) {
                        permittedQueueItemIds.add(id);
                    }
                }
            }
        }

        return Collections.unmodifiableSet(permittedQueueItemIds);
    }

    /**
     * Returns the set of project names that have an ongoing build.
     * @return set of running project names
     */
    public static Set<String> getRunningProjectNames() {
        Set<String> runningProjects = new HashSet<String>();

        List<Node> allNodes = new ArrayList<Node>(Jenkins.getInstance().getNodes());
        allNodes.add(Jenkins.getInstance());

        for (Node node : allNodes) {
            runningProjects.addAll(getRunningProjectNames(node.getNodeName()));
        }
        return Collections.unmodifiableSet(runningProjects);
    }

    /**
     * Returns the set of project names that have an ongoing build on a specific node.
     * @param nodeName the node name to list running projects for
     * @return set of running project names
     */
    public static Set<String> getRunningProjectNames(String nodeName) {
        Set<String> runningProjects = new HashSet<String>();

        Node node = Jenkins.getInstance().getNode(nodeName);
        if (nodeName.isEmpty()) { //Special case when building on master
            node = Jenkins.getInstance();
        }

        if (node != null) {
            Computer computer = node.toComputer();
            if (computer != null) {
                List<Executor> executors = new ArrayList<Executor>(computer.getExecutors());
                executors.addAll(computer.getOneOffExecutors());

                for (Executor executor : executors) {
                    Queue.Executable executable =  executor.getCurrentExecutable();
                    if (executable instanceof AbstractBuild) {
                        AbstractBuild build = (AbstractBuild)executable;
                        runningProjects.add(build.getProject().getFullName());
                    }
                }
            }
        }

        return Collections.unmodifiableSet(runningProjects);
    }

    /**
     * Gets the names of all upstream projects that triggered argument queue item.
     * @param item the queue item to find upstream projects for
     * @return set of upstream project names
     */
    public static Set<String> getUpstreamProjectNames(Queue.Item item) {
        Set<String> upstreamProjects = new HashSet<String>();
        for (Cause cause : item.getCauses()) {
            if (cause instanceof Cause.UpstreamCause) {
                Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause)cause;
                upstreamProjects.add(upstreamCause.getUpstreamProject());
            }
        }
        return Collections.unmodifiableSet(upstreamProjects);
    }

    /**
     * Gets all upstream builds that triggered argument queue item.
     * @param item the queue item to find upstream builds for
     * @return set of upstream builds
     */
    public static Set<AbstractBuild> getUpstreamBuilds(Queue.Item item) {
        Set<AbstractBuild> upstreamBuilds = new HashSet<AbstractBuild>();
        for (Cause cause : item.getCauses()) {
            if (cause instanceof Cause.UpstreamCause) {
                Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause)cause;
                String upstreamProjectName = upstreamCause.getUpstreamProject();
                AbstractProject upstreamProject = (AbstractProject)Jenkins.getInstance()
                        .getItemByFullName(upstreamProjectName);

                if (upstreamProject != null) {
                    AbstractBuild upstreamBuild = upstreamProject
                            .getBuildByNumber(upstreamCause.getUpstreamBuild());
                    upstreamBuilds.add(upstreamBuild);
                }
            }
        }
        return Collections.unmodifiableSet(upstreamBuilds);
    }

    /**
     * Checks if there are any online nodes other than the argument node
     * that can build the item.
     * @param item the item to build
     * @param node the node to exclude in the search
     * @return true if any other available nodes were found, otherwise false
     */
    public static boolean canOtherNodeBuild(Queue.Item item, Node node) {
        boolean otherNodeCanBuild = false;

        if (item instanceof BuildableItem) {
            //Item is ready to build, we can make a full check if other slaves can build it.
            BuildableItem buildableItem = (BuildableItem)item;
            Set<Node> allNodes = new HashSet<Node>(Jenkins.getInstance().getNodes());
            allNodes.add(Jenkins.getInstance());

            for (Node otherNode : allNodes) {
                Computer otherComputer = otherNode.toComputer();
                if (otherComputer != null && otherComputer.isOnline() && !otherNode.equals(node)
                        && otherNode.canTake(buildableItem) == null) {
                    otherNodeCanBuild = true;
                    break;
                }
            }
        } else if (item instanceof Queue.WaitingItem) {
            //Item is in quiet period. We can't make a full check if other nodes can build,
            //instead we check if its upstream was built on the argument node and it that case
            //return false.
            otherNodeCanBuild = true;
            for (AbstractBuild upstreamBuild : getUpstreamBuilds(item)) {
                boolean isUpstreamFinished = !upstreamBuild.isBuilding();
                if (isUpstreamFinished
                        && upstreamBuild.getBuiltOnStr().equals(node.getNodeName())) {
                    otherNodeCanBuild = false;
                    break;
                }
            }
        }
        return otherNodeCanBuild;
    }

    /**
     * Checks if argument computer is currently building something.
     * @param computer the computer to check for
     * @return true if computer is building, otherwise false
     */
    public static boolean isBuilding(Computer computer) {
        boolean isBuilding = false;
        List<Executor> executors = new ArrayList<Executor>(computer.getExecutors());
        executors.addAll(computer.getOneOffExecutors());

        for (Executor executor : executors) {
            if (executor.isBusy()) {
                isBuilding = true;
                break;
            }
        }

        return isBuilding;
    }

    /**
     * Checks if there are any builds in queue that can only be built
     * by the argument computer.
     * Note: This method locks the queue; don't use excessively.
     * @param computer the computer to check assignment for
     * @return true if there are builds that can only be build by argument computer, otherwise false
     */
    public static boolean hasNodeExclusiveItemInQueue(Computer computer) {
        boolean hasExclusive = false;
        Queue.Item[] queueItems = Queue.getInstance().getItems();

        for (Queue.Item item : queueItems) {
            if (!canOtherNodeBuild(item, computer.getNode())) {
                hasExclusive = true;
                break;
            }
        }
        return hasExclusive;
    }


}

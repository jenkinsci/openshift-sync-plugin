/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import static java.net.HttpURLConnection.HTTP_GONE;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jenkins.util.Timer;

public abstract class BaseWatcher {
    private final Logger LOGGER = Logger.getLogger(BaseWatcher.class.getName());

    protected ScheduledFuture relister;
    protected final String[] namespaces;
    protected ConcurrentHashMap<String, Watch> watches;
    protected static ConcurrentHashMap<String, List<PodTemplate>> trackedPodTemplates = new ConcurrentHashMap<String, List<PodTemplate>>();
    protected static ConcurrentHashMap<String, String> podTemplateToApiType = new ConcurrentHashMap<String, String>();
    protected static final String cmType = "ConfigMap";
    protected static final String isType = "ImageStream";
    private final String PT_NAME_CLAIMED = "The event for %s | %s | %s that attempts to add the pod template %s was ignored because a %s previously created a pod template with the same name";
    private final String PT_NOT_OWNED = "The event for %s | %s | %s that no longer includes the pod template %s was ignored because the type %s was associated with that pod template";

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public BaseWatcher(String[] namespaces) {
        this.namespaces = namespaces;
        watches = new ConcurrentHashMap<>();
    }

    public abstract Runnable getStartTimerTask();

    public abstract int getListIntervalInSeconds();

    public abstract <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource);

    public synchronized void start() {
        // lets do this in a background thread to avoid errors like:
        // Tried proxying
        // io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support
        // a circular dependency, but it is not an interface.
        Runnable task = getStartTimerTask();
        relister = Timer.get().scheduleAtFixedRate(task, 100, // still do the
                                                              // first run 100
                                                              // milliseconds in
                getListIntervalInSeconds() * 1000,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (relister != null && !relister.isDone()) {
            relister.cancel(true);
            relister = null;
        }

        for (Map.Entry<String, Watch> entry : watches.entrySet()) {
            entry.getValue().close();
            watches.remove(entry.getKey());
        }
    }

    public void onClose(KubernetesClientException e, String namespace) {
        //scans of fabric client confirm this call be called with null
        //we do not want to totally ignore this, as the closing of the
        //watch can effect responsiveness
        LOGGER.info("Watch for type " + this.getClass().getName() + " closed for one of the following namespaces: " + watches.keySet().toString());
        if (e != null) {
            LOGGER.warning(e.toString());

            if (e.getStatus() != null && e.getStatus().getCode() == HTTP_GONE) {
                stop();
                start();
            }
        }
        // clearing the watches here will signal the extending classes
        // to attempt to re-establish the watch the next time they attempt
        // to list; should shield from rapid/repeated close/reopen cycles
        // doing it in this fashion
        watches.remove(namespace);
    }

    public void addWatch(String key, Watch desiredWatch) {
        Watch watch = watches.putIfAbsent(key, desiredWatch);
        if (watch != null) {
          watch.close();
        }
    }

    protected boolean hasSlaveLabelOrAnnotation(Map<String, String> map) {
        if (map != null)
            return map.containsKey("role")
                    && map.get("role").equals("jenkins-slave");
        return false;
    }

    protected boolean isReservedPodTemplateName(String name) {
        if (name.equals("maven") || name.equals("nodejs"))
            return true;
        return false;
    }

    protected void processSlavesForAddEvent(List<PodTemplate> slaves, String type, String uid, String apiObjName, String namespace) {
      LOGGER.info("Adding PodTemplate(s) for ");
      List<PodTemplate> finalSlaveList = new ArrayList<PodTemplate>();
        for (PodTemplate podTemplate : slaves) {
          addPodTemplate(type, apiObjName, namespace, finalSlaveList, podTemplate);
        }
        updateTrackedPodTemplatesMap(uid, finalSlaveList);
    }

    protected void processSlavesForModifyEvent(List<PodTemplate> slaves, String type, String uid, String apiObjName, String namespace) {
        LOGGER.info("Modifying PodTemplates");
        boolean alreadyTracked = trackedPodTemplates.containsKey(uid);
        boolean hasSlaves = slaves.size() > 0; // Configmap has podTemplates
        if (alreadyTracked) {
            if (hasSlaves) {
                // Since the user could have change the immutable image
                // that a PodTemplate uses, we just
                // recreate the PodTemplate altogether. This makes it so
                // that any changes from within
                // Jenkins is undone.

                // Check if there are new PodTemplates added or removed to the configmap,
                // if they are, add them to or remove them from trackedPodTemplates
                List<PodTemplate> podTemplatesToTrack = new ArrayList<PodTemplate>();
                purgeTemplates(type, uid, apiObjName, namespace);
                for(PodTemplate pt: slaves){
                    podTemplatesToTrack = onlyTrackPodTemplate(type,apiObjName,namespace,podTemplatesToTrack, pt);
                }
                updateTrackedPodTemplatesMap(uid, podTemplatesToTrack);
                for (PodTemplate podTemplate : podTemplatesToTrack) {
                      // still do put here in case this is a new item from the last
                      // update on this ConfigMap/ImageStream
                      addPodTemplate(type,null,null,null, podTemplate);
                }
            } else {
                // The user modified the configMap to no longer be a
                // jenkins-slave.
                purgeTemplates(type, uid, apiObjName, namespace);
            }
        } else {
            if (hasSlaves) {
                List<PodTemplate> finalSlaveList = new ArrayList<PodTemplate>();
                for (PodTemplate podTemplate : slaves) {
                  // The user modified the api obj to be a jenkins-slave
                  addPodTemplate(type, apiObjName, namespace, finalSlaveList, podTemplate);
                }
                updateTrackedPodTemplatesMap(uid, finalSlaveList);
            }
        }
    }

    private void purgeTemplates(String type, String uid, String apiObjName, String namespace) {
        LOGGER.info("Purging PodTemplates for from Configmap with Uid "+uid);
        for (PodTemplate podTemplate : trackedPodTemplates.get(uid)) {
            // we should not have included any pod templates we did not
            // mark the type for, but we'll check just in case
            removePodTemplate(type, apiObjName, namespace, podTemplate);
        }
        trackedPodTemplates.remove(uid);
    }

    private void updateTrackedPodTemplatesMap(String uid, List<PodTemplate> finalSlaveList) {
        if (finalSlaveList != null && finalSlaveList.size() > 0)
            trackedPodTemplates.put(uid, finalSlaveList);
    }

    // Adds PodTemplate to the List<PodTemplate> correspoding to the ConfigMap of given uid
    private void trackPodTemplates(String uid, List<PodTemplate> podTemplatesToTrack) {
        trackedPodTemplates.put(uid, podTemplatesToTrack);
    }

    // Adds PodTemplate to the List<PodTemplate> correspoding to the ConfigMap of given uid and Deletes from Jenkins
    private List<PodTemplate> onlyTrackPodTemplate(String type, String apiObjName, String namespace, List<PodTemplate> podTemplates, PodTemplate podTemplate) {
        String name = podTemplate.getName();
        // we allow configmap overrides of maven and nodejs, but not imagestream ones
        // as they are less specific/defined wrt podTemplate fields
        if (isReservedPodTemplateName(name) && isType.equals(type))
          return null;
        // for imagestreams, if the core image has not changed, we avoid
        // the remove/add pod template churn and multiple imagestream events
        // come in for activity that does not affect the pod template
        if (type.equals(isType) && JenkinsUtils.hasPodTemplate(podTemplate))
          return null;
        // once a CM or IS claims a name, it gets to keep it until it is remove or un-labeled
        String ret = podTemplateToApiType.putIfAbsent(name, type);
        // if not set, or previously set by an obj of the same type
        if (ret == null || ret.equals(type)) {
            JenkinsUtils.removePodTemplate(podTemplate);
            podTemplates.add(podTemplate);
        } else {
            LOGGER.info(String.format(PT_NAME_CLAIMED, type, apiObjName, namespace, name, ret));
        }
        return podTemplates;
    }

    // Adds PodTemplate from Jenkins
    private void addPodTemplate(String type, String apiObjName, String namespace, List<PodTemplate> podTemplates, PodTemplate podTemplate) {
        String name = podTemplate.getName();
        // we allow configmap overrides of maven and nodejs, but not imagestream ones
        // as they are less specific/defined wrt podTemplate fields
        if (apiObjName != null && namespace != null && podTemplates != null){
            if (isReservedPodTemplateName(name) && isType.equals(type))
                return;
            String ret = podTemplateToApiType.putIfAbsent(name, type);
            if (ret == null || ret.equals(type)) {
                JenkinsUtils.addPodTemplate(podTemplate);
                podTemplates.add(podTemplate);
            } else {
                LOGGER.info(String.format(PT_NAME_CLAIMED, type, apiObjName, namespace, name, ret));
            }
        } else {
            podTemplateToApiType.put(name, type);
            JenkinsUtils.addPodTemplate(podTemplate);
        }
    }

    // Delete a PodTemplate from Jenkins
    private void removePodTemplate(String type, String apiObjName, String namespace, PodTemplate podTemplate) {
        String name = podTemplate.getName();
        String t = podTemplateToApiType.get(name);
        if (t != null && t.equals(type)) {
            podTemplateToApiType.remove(name);
            JenkinsUtils.removePodTemplate(podTemplate);
        } else {
            LOGGER.info(String.format(PT_NOT_OWNED, type, apiObjName, namespace, name, t));
        }
    }

    protected void processSlavesForDeleteEvent(List<PodTemplate> slaves, String type, String uid, String apiObjName, String namespace) {
        if (trackedPodTemplates.containsKey(uid)) {
            purgeTemplates(type, uid, apiObjName, namespace);
        }
    }
}

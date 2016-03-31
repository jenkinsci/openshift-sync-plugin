/**
 * Copyright (C) 2016 Red Hat, Inc.
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
#!/usr/bin/groovy
def updateDependencies(source){

  def properties = []
  properties << ['<openshift-client.version>','io/fabric8/openshift-client']
  properties << ['<kubernetes-model.version>','io/fabric8/kubernetes-model']

  updatePropertyVersion{
    updates = properties
    repository = source
    project = 'fabric8io/openshift-jenkins-sync-plugin'
  }
}

def stage(){
  return stageProject{
    project = 'fabric8io/openshift-jenkins-sync-plugin'
    useGitTagForNextVersion = true
  }
}

def release(project){
  releaseProject{
    stagedProject = project
    useGitTagForNextVersion = true
    helmPush = false
    groupId = 'io.fabric8'
    githubOrganisation = 'fabric8io'
    artifactIdToWatchInCentral = 'openshift-jenkins-sync-plugin'
    artifactExtensionToWatchInCentral = 'jar'
    extraImagesToTag = null
  }
}

return this;

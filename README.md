# openshift-jenkins-sync-plugin

This Jenkins plugin keeps OpenShift BuildConfig and Build objects in sync with Jenkins Jobs and Builds.

The synchronization works like this


* changes to OpenShift BuildConfig resources for Jenkins pipeline builds result in updates to the Jenkins Job of the same name
* creating a new OpenShift Build for a BuildConfig associated with a Jenkins Job results in the Jenkins Job being triggered
* changes in a Jenkins Build Run thats associated with a Jenkins Job gets replicated to an OpenShift Build object (which is created if necessary if the build was triggered via Jenkins)

Development Instructions
------------------------

* Build and run the unit tests
  Execute `mvn clean install`
  
* Install the plugin into a locally-running Jenkins
  Execute `mvn hpi:run`
  Navigate in brower to `http://localhost:8080/jenkins`
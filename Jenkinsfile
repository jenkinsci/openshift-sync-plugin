#!/usr/bin/groovy
node{
  ws{
    checkout scm

    def pipeline = load 'release.groovy'

    // Dont update dependencies yet, working issue on kubernetes-model thats using SNAPSHOT - cc jdyson
    // Uncomemnt below to update dependencies
    //stage 'Updating dependencies'
    //def prId = pipeline.updateDependencies('http://central.maven.org/maven2/')

    stage 'Staging project'
    def stagedProject = pipeline.stage()

    stage 'Promoting'
    pipeline.release(stagedProject)

    if (prId != null){
      pipeline.mergePullRequest(prId)
    }
  }
}

#!/usr/bin/env bash

export JENKINS_HOST="http://jenkins.vagrant.f8/"
export JENKINS_CONTAINER=`docker ps | grep jenkins | { read first rest ; echo $first ; }`

echo "rebuilding the plugin"
mvn -Dtest=false -DfailIfNoTests=false clean install -Pnochecks $*

docker cp target/*.hpi $JENKINS_CONTAINER:/var/jenkins_home/plugins
echo "Copied the new plugins into the docker container $JENKINS_CONTAINER"

echo "Now restarting Jenkins at: $JENKINS_HOST"
curl -X POST ${JENKINS_HOST}/safeRestart

echo "Restarted Jenkins at: $JENKINS_HOST"




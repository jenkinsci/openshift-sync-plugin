#!/usr/bin/env bash
# Copyright (C) 2016 Red Hat, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

export JENKINS_HOST="http://jenkins.vagrant.f8/"
export JENKINS_CONTAINER=`docker ps | grep jenkins | { read first rest ; echo $first ; }`

echo "rebuilding the plugin"
mvn -Dtest=false -DfailIfNoTests=false clean install -Pnochecks $*

docker cp target/*.hpi $JENKINS_CONTAINER:/var/jenkins_home/plugins
echo "Copied the new plugins into the docker container $JENKINS_CONTAINER"

echo "Now restarting Jenkins at: $JENKINS_HOST"
curl -X POST ${JENKINS_HOST}/safeRestart

echo "Restarted Jenkins at: $JENKINS_HOST"




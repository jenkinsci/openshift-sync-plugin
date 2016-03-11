package io.fabric8.jenkins.openshiftsync;

import hudson.model.Job;
import hudson.util.XStream2;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.apache.tools.ant.filters.StringInputStream;

import java.io.IOException;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.jobName;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToJob;

public class BuildConfigWatcher implements Watcher<BuildConfig> {

  public static final String EXTERNAL_BUILD_STRATEGY = "External";
  private final Logger logger = Logger.getLogger(getClass().getName());

  @Override
  public void onClose(KubernetesClientException e) {
    if (e != null) {
      logger.warning(e.toString());
    }
  }

  @Override
  public void eventReceived(Watcher.Action action, BuildConfig buildConfig) {
    try {
      switch (action) {
        case ADDED:
          createJob(buildConfig);
          break;
        case DELETED:
          deleteJob(buildConfig);
          break;
        case MODIFIED:
          modifyJob(buildConfig);
          break;
      }
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
    }
  }

  private void createJob(BuildConfig buildConfig) throws IOException {
    if (buildConfig.getSpec().getStrategy().getType().equalsIgnoreCase(EXTERNAL_BUILD_STRATEGY)) {
      Job job = mapBuildConfigToJob(buildConfig);
      Jenkins.getInstance().createProjectFromXML(
        job.getName(),
        new StringInputStream(new XStream2().toXML(job))
      );
    }
  }

  private void modifyJob(BuildConfig buildConfig) {

  }

  private void deleteJob(BuildConfig buildConfig) throws IOException, InterruptedException {
    String jobName = jobName(buildConfig);
    Job job = Jenkins.getInstance().getItemByFullName(jobName, Job.class);
    job.delete();
  }

}

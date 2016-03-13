package io.fabric8.jenkins.openshiftsync;

import hudson.model.Job;
import hudson.util.XStream2;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.apache.tools.ant.filters.StringInputStream;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
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
          upsertJob(buildConfig);
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

  private void upsertJob(BuildConfig buildConfig) throws IOException {
    if (buildConfig.getSpec().getStrategy().getType().equalsIgnoreCase(EXTERNAL_BUILD_STRATEGY)) {
      String jobName = jobName(buildConfig);
      Job jobFromBuildConfig = mapBuildConfigToJob(buildConfig);
      InputStream jobStream = new StringInputStream(new XStream2().toXML(jobFromBuildConfig));

      Job job = Jenkins.getInstance().getItem(jobName, Jenkins.getInstance(), Job.class);
      if (job == null) {
        Jenkins.getInstance().createProjectFromXML(
          jobName,
          jobStream
        );
      } else {
        Source source = new StreamSource(jobStream);
        job.updateByXml(source);
        job.save();
      }
    }
  }

  private void modifyJob(BuildConfig buildConfig) throws IOException, InterruptedException {
    if (buildConfig.getSpec().getStrategy().getType().equalsIgnoreCase(EXTERNAL_BUILD_STRATEGY)) {
      upsertJob(buildConfig);
      return;
    }

    String jobName = jobName(buildConfig);
    Job job = Jenkins.getInstance().getItem(jobName, Jenkins.getInstance(), Job.class);
    if (job != null) {
      job.delete();
    }
  }

  private void deleteJob(BuildConfig buildConfig) throws IOException, InterruptedException {
    String jobName = jobName(buildConfig);
    Job job = Jenkins.getInstance().getItem(jobName, Jenkins.getInstance(), Job.class);
    if (job != null) {
      job.delete();
    }
  }

}

package io.fabric8.jenkins.openshiftsync;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.remoting.Base64;
import hudson.security.ACL;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.fixNull;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

public class CredentialsUtils {

  private final static Logger logger = Logger.getLogger(CredentialsUtils.class.getName());

  public static synchronized String updateSourceCredentials(BuildConfig buildConfig) throws IOException {
    String id = null;
    if (buildConfig.getSpec() != null &&
      buildConfig.getSpec().getSource() != null &&
      buildConfig.getSpec().getSource().getSourceSecret() != null &&
      !buildConfig.getSpec().getSource().getSourceSecret().getName().isEmpty()) {
      Secret sourceSecret = getOpenShiftClient().secrets().inNamespace(buildConfig.getMetadata().getNamespace()).withName(buildConfig.getSpec().getSource().getSourceSecret().getName()).get();
      if (sourceSecret != null) {
        Credentials creds = secretToCredentials(sourceSecret);
        id = buildConfig.getMetadata().getNamespace() + "-" + buildConfig.getSpec().getSource().getSourceSecret().getName();
        Credentials existingCreds = lookupCredentials(id);
        final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
        try {
          CredentialsStore s = CredentialsProvider.lookupStores(Jenkins.getInstance()).iterator().next();
          if (existingCreds != null) {
            s.updateCredentials(Domain.global(), existingCreds, creds);
          } else {
            s.addCredentials(Domain.global(), creds);
          }
        } finally {
          SecurityContextHolder.setContext(previousContext);
        }
      }
    }
    return id;
  }

  private static Credentials lookupCredentials(String id) {
    return CredentialsMatchers.firstOrNull(
      CredentialsProvider.lookupCredentials(
        Credentials.class,
        Jenkins.getInstance(),
        ACL.SYSTEM,
        Collections.<DomainRequirement> emptyList()
      ),
      CredentialsMatchers.withId(id)
    );
  }

  private static Credentials secretToCredentials(Secret secret) {
    switch (secret.getType()) {
      case "kubernetes.io/basic-auth":
        return new UsernamePasswordCredentialsImpl(
          CredentialsScope.GLOBAL,
          secret.getMetadata().getNamespace() + "-" + secret.getMetadata().getName(),
          secret.getMetadata().getNamespace() + "-" + secret.getMetadata().getName(),
          new String(Base64.decode(fixNull(secret.getData().get("username"))), StandardCharsets.UTF_8),
          new String(Base64.decode(fixNull(secret.getData().get("password"))), StandardCharsets.UTF_8)
        );
      case "kubernetes.io/ssh-auth":
        return new BasicSSHUserPrivateKey(
          CredentialsScope.GLOBAL,
          secret.getMetadata().getNamespace() + "-" + secret.getMetadata().getName(),
          fixNull(secret.getData().get("username")),
          new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(new String(Base64.decode(fixNull(secret.getData().get("ssh-privatekey"))), StandardCharsets.UTF_8)),
          null,
          secret.getMetadata().getNamespace() + "-" + secret.getMetadata().getName()
        );
      default:
        logger.log(Level.WARNING, "Unknown secret type: " + secret.getType());
        return null;
    }
  }
}

package io.fabric8.jenkins.openshiftsync;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.common.UsernameCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleProject;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_PASSWORD;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_DATA_USERNAME;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_SECRETS_TYPE_BASICAUTH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getEncoder;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class CredentialsUtilsCredentialStoreAnnotationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void credentialStoreAnnotationWritesToFolderStore() throws Exception {
        // Create a folder that we'll target as the credential store.
        Folder folder = j.jenkins.createProject(Folder.class, "team-a");
        // Ensure folder is fully saved/loaded.
        folder.save();

        // Sanity check: folder has a credentials store.
        CredentialsStore folderStore = CredentialsProvider.lookupStores(folder).iterator().next();
        assertThat(folderStore, is(notNullValue()));

        // Build a basic-auth k8s Secret with the annotation pointing to the folder fullName.
        Secret secret = new Secret();
        ObjectMeta meta = new ObjectMeta();
        meta.setNamespace("ns1");
        meta.setName("mysecret");
        meta.setUid("uid1");

        Map<String, String> annotations = new HashMap<>();
        annotations.put(Annotations.CREDENTIAL_STORE, folder.getFullName());
        meta.setAnnotations(annotations);
        secret.setMetadata(meta);
        secret.setType(OPENSHIFT_SECRETS_TYPE_BASICAUTH);

        Map<String, String> data = new HashMap<>();
        data.put(OPENSHIFT_SECRETS_DATA_USERNAME, getEncoder().encodeToString("bob".getBytes(UTF_8)));
        data.put(OPENSHIFT_SECRETS_DATA_PASSWORD, getEncoder().encodeToString("s3cr3t".getBytes(UTF_8)));
        secret.setData(data);

        String id = CredentialsUtils.upsertCredential(secret);
        assertThat(id, is("ns1-mysecret"));

        // Verify: credential exists in folder store...
        Credentials inFolder = CredentialsProvider.lookupCredentialsInItem(
                        UsernamePasswordCredentialsImpl.class,
                        folder,
                        null,
                        Collections.emptyList())
                .stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst()
                .orElse(null);
        assertThat("credential should be present in the folder store", inFolder, is(notNullValue()));

        // ...and not in the system store.
        Credentials inSystem = CredentialsProvider.lookupCredentials(
                        Credentials.class,
                        j.jenkins,
                        hudson.security.ACL.SYSTEM,
                        Collections.emptyList())
                .stream()
                .filter(c -> id.equals(c.getDescriptor().getId()))
                .findFirst()
                .orElse(null);
        assertThat("credential should not be present in the system store", inSystem, is(nullValue()));

        // Bonus smoke: ensure we didn't accidentally create a job/project (avoids unused imports).
        FreeStyleProject p = j.createFreeStyleProject("smoke");
        assertThat(p, is(notNullValue()));

        // Cleanup: remove from folder store to avoid cross-test contamination.
        folderStore.removeCredentials(Domain.global(), inFolder);
        folderStore.save();
    }
}


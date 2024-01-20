import com.googlesource.gerrit.plugins.replication.*
import com.googlesource.gerrit.plugins.replication.FanoutConfigResource.*
import com.googlesource.gerrit.plugins.replication.FileConfigResource.*

import com.google.inject.*
import com.google.common.collect.*
import com.google.common.flogger.*
import com.google.gerrit.entities.*
import com.google.common.io.Files.*
import com.google.gerrit.extensions.registration.*
import com.google.gerrit.server.config.*
import com.google.gerrit.server.git.*
import com.google.inject.*

import java.io.*
import java.util.*

import org.eclipse.jgit.errors.*
import org.eclipse.jgit.lib.*
import org.eclipse.jgit.lib.FileMode.*

import org.eclipse.jgit.revwalk.*
import org.eclipse.jgit.treewalk.*

class GitReplicationConfigOverrides implements ReplicationConfigOverrides {
  FluentLogger logger = FluentLogger.forEnclosingClass()
  Config EMPTY_CONFIG = new Config()

  def REF_NAME = RefNames.REFS_META + "replication"

  @Inject
  AllProjectsName allProjectsName

  @Inject
  GitRepositoryManager repoManager

  @Override
  Config getConfig() {
    Config config = EMPTY_CONFIG

  	Repository repo = repoManager.openRepository(allProjectsName)
  	RevWalk rw = new RevWalk(repo)
    try {
      Ref ref = repo.exactRef(REF_NAME)
      if (ref) {
        RevTree tree = rw.parseTree(ref.objectId)
        config = addFanoutRemotes(repo, tree, getBaseConfig(repo, tree))
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Cannot read replication config from git repository")
    } catch (ConfigInvalidException e) {
      logger.atWarning().withCause(e).log("Cannot parse replication config from git repository")
    } finally {
    	rw.close()
    	repo.close()
    }

    config
  }

  Config getBaseConfig(Repository repo, RevTree tree) {
    TreeWalk tw = TreeWalk.forPath(repo, FileConfigResource.CONFIG_NAME, tree)
    return tw ? new BlobBasedConfig(new Config(), repo, tw.getObjectId(0)) : EMPTY_CONFIG
  }

  Config addFanoutRemotes(Repository repo, RevTree tree, Config destination)
      throws IOException, ConfigInvalidException {
    TreeWalk tw = TreeWalk.forPath(repo, FanoutConfigResource.CONFIG_DIR, tree)
    if (tw) {
      removeRemotes(destination)

      tw.enterSubtree()
      while (tw.next()) {
        if (tw.fileMode == REGULAR_FILE && tw.nameString.endsWith(".config")) {
          Config remoteConfig = new BlobBasedConfig(new Config(), repo, tw.getObjectId(0))
          addRemoteConfig(tw.nameString, remoteConfig, destination)
        }
      }
    }

    destination
  }

  def removeRemotes(Config config) {
    Set<String> remoteNames = config.getSubsections("remote")
    if (!remoteNames) {
      logger.atSevere().log(
          "When replication directory is present replication.config file cannot contain remote configuration. Ignoring: %s",
          remoteNames.join(","))

      for (String name : remoteNames) {
        config.unsetSection("remote", name)
      }
    }
  }

  def addRemoteConfig(String fileName, Config source, Config destination) {
    String remoteName = Files.getNameWithoutExtension(fileName)
    source.getNames("remote").each { name ->
      destination.setStringList(
          "remote",
          remoteName,
          name,
          Lists.newArrayList(source.getStringList("remote", null, name)))
    }
  }

  @Override
  String getVersion() {
  	Repository repo = repoManager.openRepository(allProjectsName)
    try {
      ObjectId configHead = repo.resolve(REF_NAME)
      return configHead ? configHead.name() : ""
    } catch (IOException e) {
      throw new IllegalStateException("Could not open replication configuration repository", e)
    } finally {
    	repo.close()
    }
  }
}

class GitReplicationConfigModule implements Module {

  @Override
  void configure(Binder binder) {
    DynamicItem.bind(binder, ReplicationConfigOverrides.class)
        .to(GitReplicationConfigOverrides.class)
  }
}

modules = [ GitReplicationConfigModule ]
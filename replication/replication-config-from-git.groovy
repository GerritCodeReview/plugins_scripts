// Copyright (C) 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import com.google.gerrit.common.Nullable
import com.google.gerrit.server.update.context.RefUpdateContext
import com.googlesource.gerrit.plugins.replication.*

import com.google.common.collect.*
import com.google.common.flogger.*
import com.google.common.io.*
import com.google.gerrit.entities.*
import com.google.gerrit.extensions.registration.*
import com.google.gerrit.server.config.*
import com.google.gerrit.server.git.*
import com.google.gerrit.server.*
import com.google.inject.*

import org.eclipse.jgit.errors.*
import org.eclipse.jgit.dircache.*
import org.eclipse.jgit.lib.*

import org.eclipse.jgit.revwalk.*
import org.eclipse.jgit.treewalk.*

import static java.nio.charset.StandardCharsets.*
import static org.eclipse.jgit.dircache.DirCacheEntry.*
import static org.eclipse.jgit.lib.FileMode.*
import static org.eclipse.jgit.lib.RefUpdate.Result.*

class GitReplicationConfigOverrides implements ReplicationConfigOverrides {
    static final FluentLogger logger = FluentLogger.forEnclosingClass()
    Config EMPTY_CONFIG = new Config()

    def REF_NAME = RefNames.REFS_META + "replication"

    @Inject
    AllProjectsName allProjectsName

    @Inject
    GitRepositoryManager repoManager

    @Inject
    Provider<AllProjectsName> allProjectsNameProvider

    @Inject
    @GerritPersonIdent
    PersonIdent gerritPersonIdent

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

    static def removeRemotes(Config config) {
      Set < String > remoteNames = config.getSubsections("remote")
      if (!remoteNames) {
        logger.atSevere().log(
          "When replication directory is present replication.config file cannot contain remote configuration. Ignoring: %s",
          remoteNames.join(","))

        for (String name: remoteNames) {
          config.unsetSection("remote", name)
        }
      }
    }

    static def addRemoteConfig(String fileName, Config source, Config destination) {
      String remoteName = Files.getNameWithoutExtension(fileName)
      source.getNames("remote").each {
        name ->
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

    @Override
    void update(Config config) throws IOException {
      Repository repo = repoManager.openRepository(allProjectsNameProvider.get())
      RefUpdateContext ctx = RefUpdateContext.open(RefUpdateContext.RefUpdateType.PLUGIN)
      RevWalk rw = new RevWalk(repo)
      ObjectReader reader = repo.newObjectReader()
      ObjectInserter inserter = repo.newObjectInserter()
      try {
        ObjectId configHead = repo.resolve(REF_NAME)
        DirCache dirCache = readTree(repo, reader, configHead)
        DirCacheEditor editor = dirCache.editor()
        Config rootConfig = readConfig(FileConfigResource.CONFIG_NAME, repo, rw, configHead)

        for (String section : config.getSections()) {
          if ("remote".equals(section)) {
            updateRemoteConfig(config, repo, rw, configHead, editor, inserter)
          } else {
            updateRootConfig(config, section, rootConfig)
          }
        }
        insertConfig(FileConfigResource.CONFIG_NAME, rootConfig, editor, inserter)
        editor.finish()

        CommitBuilder cb = new CommitBuilder()
        ObjectId newTreeId = dirCache.writeTree(inserter)
        if (configHead != null) {
          ObjectId oldTreeId = repo.parseCommit(configHead).tree
          if (oldTreeId == newTreeId) {
            logger.atInfo().log("No configuration changes were applied, ignoring")
            return;
          }
          cb.setParentId(configHead)
        }
        cb.setAuthor(gerritPersonIdent)
        cb.setCommitter(gerritPersonIdent)
        cb.setTreeId(newTreeId);
        cb.setMessage("Update configuration")
        ObjectId newConfigHead = inserter.insert(cb)
        inserter.flush()
        RefUpdate refUpdate = repo.getRefDatabase().newUpdate(REF_NAME, false)
        refUpdate.setNewObjectId(newConfigHead)
        RefUpdate.Result result = refUpdate.update()
        if (result != FAST_FORWARD && result != NEW) {
          throw new IOException("Updating replication config failed: " + result)
        }
      } finally {
        inserter.close()
        reader.close()
        rw.close()
        ctx.close()
        repo.close()
      }
    }

     Config readConfig(
        String configPath, Repository repo, RevWalk rw, @Nullable ObjectId treeId) {
      if (treeId != null) {
        try {
          RevTree tree = rw.parseTree(treeId)
          TreeWalk tw = TreeWalk.forPath(repo, configPath, tree)
          if (tw != null) {
            return new BlobBasedConfig(new Config(), repo, tw.getObjectId(0))
          }
        } catch (ConfigInvalidException | IOException e) {
          logger.atWarning().withCause(e).log(
              "failed to load replication configuration from branch %s of %s, path %s",
              REF_NAME, allProjectsName.get(), configPath)
        }
      }

      return new Config();
    }

    static DirCache readTree(Repository repo, ObjectReader reader, ObjectId configHead)
        throws IOException {
      DirCache dc = DirCache.newInCore()
      if (configHead != null) {
        RevTree tree = repo.parseCommit(configHead).getTree()
        DirCacheBuilder b = dc.builder()
        b.addTree(new byte[0], STAGE_0, reader, tree)
        b.finish()
      }
      return dc;
    }

    void updateRemoteConfig(
        Config config,
        Repository repo,
        RevWalk rw,
        @Nullable ObjectId refId,
        DirCacheEditor editor,
        ObjectInserter inserter)
        throws IOException {
      for (String remoteName : config.getSubsections("remote")) {
        String configPath = String.format("%s/%s.config", FanoutConfigResource.CONFIG_DIR, remoteName)
        Config baseConfig = readConfig(configPath, repo, rw, refId)

        updateConfigSubSections(config, "remote", remoteName, baseConfig)
        insertConfig(configPath, baseConfig, editor, inserter)
      }
    }

    static void updateRootConfig(Config config, String section, Config rootConfig) {
      for (String subsection : config.getSubsections(section)) {
        updateConfigSubSections(config, section, subsection, rootConfig)
      }

      for (String name : config.getNames(section, true)) {
        List<String> values = Lists.newArrayList(config.getStringList(section, null, name))
        rootConfig.setStringList(section, null, name, values)
      }
    }

    static void updateConfigSubSections(
        Config source, String section, String subsection, Config destination) {
      for (String name : source.getNames(section, subsection, true)) {
        List<String> values = Lists.newArrayList(source.getStringList(section, subsection, name))
        destination.setStringList(section, subsection, name, values)
      }
    }

    static void insertConfig(
        String configPath, Config config, DirCacheEditor editor, ObjectInserter inserter)
        throws IOException {
      String configText = config.toText()
      ObjectId configId = inserter.insert(Constants.OBJ_BLOB, configText.getBytes(UTF_8))
      editor.add(
          new DirCacheEditor.PathEdit(configPath) {
            @Override
            void apply(DirCacheEntry ent) {
              ent.setFileMode(REGULAR_FILE)
              ent.setObjectId(configId)
            }
          });
    }
  }

class GitReplicationConfigModule implements Module {

  @Override
  void configure(Binder binder) {
    DynamicItem.bind(binder, ReplicationConfigOverrides.class)
      .to(GitReplicationConfigOverrides.class)
  }
}

modules = [GitReplicationConfigModule]

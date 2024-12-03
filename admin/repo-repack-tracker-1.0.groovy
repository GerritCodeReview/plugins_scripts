// Copyright (C) 2025 The Android Open Source Project
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

import com.google.common.flogger.FluentLogger
import com.google.gerrit.entities.Project
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.extensions.events.LifecycleListener
import com.google.gerrit.lifecycle.LifecycleModule
import com.google.gerrit.metrics.*
import com.google.gerrit.server.config.*
import com.google.gerrit.server.git.LocalDiskRepositoryManager
import com.google.inject.Singleton
import org.eclipse.jgit.lib.Constants

import javax.inject.Inject
import java.util.concurrent.TimeUnit

import static groovy.io.FileType.FILES

@Singleton
@Listen
class RepoRepackTracker implements LifecycleListener {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass()
  private static final NAME = "is_repack_running_per_project"
  private static final DESCRIPTION = "Check repack running for the project"
  private static final GIT_PACK_FOLDER = "objects/pack"
  private static final TMP_PREFIX = "tmp_"
  private static final TMP_SUFFIX = ".tmp"

  @Inject
  @PluginName
  String pluginName
  @Inject
  PluginConfigFactory configFactory
  @Inject
  MetricMaker metrics
  @Inject
  LocalDiskRepositoryManager repoMgr

  private def tmpFilter = ~/^(${TMP_PREFIX}.*|.*${TMP_SUFFIX})$/
  private long considerStaleAfterMs
  CallbackMetric1<String, Long> projectsAndGcMetric
  List<String> projects

  @Override
  void start() {
    PluginConfig pluginConfig = configFactory.getFromGerritConfig(pluginName)
    long considerStaleAfter = ConfigUtil.getTimeUnit(
        pluginConfig.getString("considerStaleAfter", "60 minutes"),
        60L,
        TimeUnit.MINUTES)
    considerStaleAfterMs = TimeUnit.MILLISECONDS.convert(considerStaleAfter, TimeUnit.MINUTES)

    projects = pluginConfig.getStringList("project")
    projectsAndGcMetric = createCallbackMetric(NAME, DESCRIPTION)
    addMetricsTrigger(projectsAndGcMetric, projects)

    logger.atInfo().log("Plugin %s started (staleAfter %d minutes)",
        pluginName,
        considerStaleAfter)
  }

  CallbackMetric1<String, Long> createCallbackMetric(String name, String description) {
    metrics.newCallbackMetric(
        name,
        Long.class,
        new Description(description).setGauge(),
        Field.ofProjectName("repository_name")
            .description(description)
            .build()
    )
  }

  void addMetricsTrigger(CallbackMetric1<String, Long> projectsAndGcMetric, List<String> projects) {
    metrics.newTrigger(
        projectsAndGcMetric, {
      if (projects.isEmpty()) {
        projectsAndGcMetric.forceCreate("")
      } else {
        projects.each { e ->
          projectsAndGcMetric.set(e, isRepackingRunningForProject(e))
        }
        projectsAndGcMetric.prune()
      }
    })
  }

  long isRepackingRunningForProject(String projectName) {
    def isRepackRunning = 0L
    try {
      def repoDir = getRepoDir(projectName)
      def packDir = new File(repoDir, GIT_PACK_FOLDER)
      if (packDir.exists() && hasRepackTmpFiles(packDir)) {
        isRepackRunning = 1L
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Could not check project %s",  projectName)
    }
    isRepackRunning
  }

  File getRepoDir(String projectName) {
    def name = Project.nameKey(projectName)
    def path = repoMgr.getBasePath(name)
    return path.resolve("${name.get()}${Constants.DOT_GIT_EXT}").toFile()
  }

  boolean hasRepackTmpFiles(folder) {
    def oneHourAgo = System.currentTimeMillis() - considerStaleAfterMs
    def tmpFileFound = false

    folder.traverse(type: FILES, nameFilter: tmpFilter) { file ->
      if (file.lastModified() >= oneHourAgo) {
        tmpFileFound = true
        return tmpFileFound
      }
    }
    return tmpFileFound
  }

  @Override
  void stop() {}
}

class RepoRepackTrackerModule extends LifecycleModule {
    protected void configure() {
        listener().to(RepoRepackTracker)
    }
}

modules = [RepoRepackTrackerModule]

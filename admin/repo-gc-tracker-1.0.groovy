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

import com.google.common.flogger.FluentLogger
import com.google.gerrit.entities.Project
import com.google.gerrit.extensions.annotations.Listen
import com.google.gerrit.extensions.annotations.PluginName
import com.google.gerrit.extensions.events.LifecycleListener
import com.google.gerrit.lifecycle.LifecycleModule
import com.google.gerrit.metrics.CallbackMetric1
import com.google.gerrit.metrics.Description
import com.google.gerrit.metrics.Field
import com.google.gerrit.metrics.MetricMaker
import com.google.gerrit.server.config.ConfigUtil
import com.google.gerrit.server.config.PluginConfig
import com.google.gerrit.server.config.PluginConfigFactory
import com.google.gerrit.server.git.LocalDiskRepositoryManager
import com.google.gerrit.server.git.WorkQueue
import com.google.inject.Singleton
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.Constants

import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import java.util.regex.Pattern

@Singleton
@Listen
class RepoGCTracker implements LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass()
  private static final GC_PID_FILE = "gc.pid"

  @Inject
  @PluginName
  String pluginName
  @Inject
  PluginConfigFactory configFactory
  @Inject
  LocalDiskRepositoryManager repoMgr
  @Inject
  WorkQueue workQueue
  @Inject
  RepoGCTrackerMetrics repoGCTrackerMetrics

  private ScheduledFuture<?> scheduledCheckerTask
  private String[] projectNames
  private Long checkInterval

  @Override
  void start() {
    if (scheduledCheckerTask != null) {
      return;
    }
    PluginConfig pluginConfig = configFactory.getFromGerritConfig(pluginName)
    checkInterval = checkInterval = ConfigUtil.getTimeUnit(pluginConfig.getString("checkInterval"), 30L, TimeUnit.SECONDS)
    projectNames = pluginConfig.getStringList("project")

    scheduledCheckerTask = workQueue.getDefaultQueue().scheduleAtFixedRate({ checkGcRunningForProjects() }, checkInterval, checkInterval, TimeUnit.SECONDS)
    logger.atInfo().log("%s checker started for %d projects (checkInterval=%d seconds)", GC_PID_FILE, projectNames.length, checkInterval)
  }

  @Override
  void stop() {
    logger.atInfo().log("Plugin %s stopping all projects gc running checker ...", pluginName)
    scheduledCheckerTask?.cancel(true)
  }

  def checkGcRunningForProjects() {
    def threadNameOrig = Thread.currentThread().getName()
    try {
      Thread.currentThread().setName("isGCRunning checker")
      projectNames.each { String projectName ->
        logger.atFine().log("%s checking project %s", pluginName, projectName)
          try {
            isGCRunningForProject(projectName)
          } catch (RepositoryNotFoundException rnfe){
            logger.atWarning().log("Plugin %s could not open project %s, if the project was removed you should also update the plugin configuration.", pluginName,  projectName)
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Plugin %s could not check project %s", pluginName,  projectName)
          }
      }
      repoGCTrackerMetrics.updateMetrics()
    } finally {
      Thread.currentThread().setName(threadNameOrig)
    }
  }

  private File getRepoDir(String projectName) {
    def name = Project.nameKey(projectName)
    def path = repoMgr.getBasePath(name)
    def dir = path.resolve(name.get()).toFile()
    if (dir.isDirectory()) {
      return dir
    }
    dir = path.resolve("${name.get()}${Constants.DOT_GIT_EXT}").toFile()
    if (dir.isDirectory()) {
      return dir
    }
    throw new RepositoryNotFoundException(dir)
  }

  private void isGCRunningForProject(String projectName) {
    def repoDir = getRepoDir(projectName)
    def gcPid = new File(repoDir, GC_PID_FILE)
    def hasGc = gcPid.exists() ? 1L : 0L
    repoGCTrackerMetrics.gcRunning[projectName] = hasGc
  }
}

@Singleton
class RepoGCTrackerMetrics implements LifecycleListener {
  private static final NAME = "is_gc_running_per_project"
  private static final DESCRIPTION = "Check Garbage Collection is running for the project"
  private static final VALID_PROJECT_NAME_CHARS = Pattern.compile("[a-zA-Z0-9_-]")

  @com.google.inject.Inject
  MetricMaker metrics

  CallbackMetric1<String, Long> projectsAndGcMetric
  final Map<String, Long> gcRunning = new ConcurrentHashMap()

  void updateMetrics(){
    callMetricsTrigger(projectsAndGcMetric, gcRunning)
  }

  static String sanitizeProjectName(String name) {
    def sanitizedName = new StringBuilder()
    name.each { it ->
      Character c = it as Character
      if (c == '_') {
        sanitizedName.append("__")
      } else if (VALID_PROJECT_NAME_CHARS.matcher(c as String).find()) {
        sanitizedName.append(c)
      } else {
        sanitizedName.append("_").append(Integer.toHexString(c as int))
      }
    }
    return sanitizedName.toString()
  }

  private void callMetricsTrigger(CallbackMetric1<String, Long> projectsAndGcMetric, Map<String, Long> gcRunning) {
    metrics.newTrigger(
        projectsAndGcMetric, {
      if (gcRunning.isEmpty()) {
        projectsAndGcMetric.forceCreate("")
      } else {
        gcRunning.each { e ->
          projectsAndGcMetric.set(sanitizeProjectName(e.key), e.value)
        }
        projectsAndGcMetric.prune()
      }
    })
  }

  CallbackMetric1<String, Long> createCallbackMetric(String name, String description) {
    metrics.newCallbackMetric(
        name,
        Long.class,
        new Description(description).setGauge(),
        Field.ofString("repository_name", { it.projectName } as BiConsumer)
            .description(description)
            .build()
    )
  }

  void start() {
    projectsAndGcMetric = createCallbackMetric(NAME, DESCRIPTION)
    callMetricsTrigger(projectsAndGcMetric, gcRunning)
  }

  void stop() {
    projectsAndGcMetric.remove()
  }
}

class RepoGCTrackerModule extends LifecycleModule {
  protected void configure() {
    listener().to(RepoGCTrackerMetrics)
    listener().to(RepoGCTracker)
  }
}

modules = [RepoGCTrackerModule]
